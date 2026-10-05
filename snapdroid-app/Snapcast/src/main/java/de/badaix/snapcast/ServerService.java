package de.badaix.snapcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 手机版 Snapcast 服务端（snapnode 的 Java 移植）。
 * <p>
 * 户外场景：一台手机当"控制中心"，其余手机装同一 APK 当客户端连过来。
 * <p>
 * 实现 Snapcast 二进制协议 v2（与 snapdroid 0.29 / snapclient 0.29~0.35 兼容）：
 * - TCP 1704 : 音频流 + Hello/TIME 握手
 * - TCP 1780 : HTTP 控制 API（与 snapnode 完全同构，中控界面直接可用）
 * <p>
 * 音源：本机音乐文件（MediaExtractor+MediaCodec 解码为 PCM，循环播放）。
 * 注意 Android 系统限制：无法抓取其他 App（网易云等）正在播放的音频。
 * <p>
 * 移植自 snapnode/server.js，协议要点：
 * - 26 字节帧头，全部小端；
 * - ServerSettings.refersTo 必须 = Hello 请求 id（客户端 2s 超时匹配）；
 * - 所有时间戳是单调时钟（不能用 Unix 时间，int32 会溢出）；
 * - msgId 取模 65536 防回绕。
 */
public class ServerService extends Service {

    private static final String TAG = "ServerService";
    public static final String EXTRA_TRACK_URI = "track_uri";
    public static final String NOTIFICATION_CHANNEL_ID = "snapserver";

    private static final int T_CODEC_HEADER = 1, T_WIRE_CHUNK = 2, T_SERVER_SETTINGS = 3,
            T_TIME = 4, T_HELLO = 5, T_CLIENT_INFO = 7;

    private static final int STREAM_PORT = 1704;
    private static final int HTTP_PORT = 1780;
    private static final int CHUNK_MS = 20;
    private static final int BUFFER_MS = 1000;

    /** 中控判断"本机就是服务器"用 */
    public static volatile boolean RUNNING = false;

    // ---- 音源状态 ----
    private volatile Uri trackUri = null;
    private String trackName = "";
    private String trackArtist = "";
    private volatile int sampleRate = 48000;
    private volatile int channels = 2;
    private volatile long durationUs = 0;
    private volatile boolean paused = false;
    private volatile double basePositionSec = 0;
    private volatile long consumedBytes = 0;
    private volatile long seekRequestUs = -1; // >=0 表示请求跳转

    // ---- 调度 ----
    private long procStartNs;
    private long msgId = 0;
    private long seq = 0;
    private double startUs = 0;
    private volatile boolean running = false;

    private final LinkedBlockingQueue<byte[]> pcmQueue = new LinkedBlockingQueue<>(200);
    private final ConcurrentHashMap<Socket, ClientInfo> clients = new ConcurrentHashMap<>();

    private ServerSocket streamServer;
    private ServerSocket httpServer;
    private Thread tickThread, decoderThread, acceptThread, httpThread;

    // ---- EQ（RBJ biquad，移植自 snapnode）----
    private final float[] eq = {0, 0, 0}; // low, mid, high (dB)
    private String eqPreset = "flat";
    private Biquad[] chainL = null, chainR = null;

    // ================= 生命周期 =================

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        procStartNs = System.nanoTime();
        rebuildEq();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundWithType();
        RUNNING = true;
        if (intent != null && intent.getStringExtra(EXTRA_TRACK_URI) != null) {
            try {
                trackUri = Uri.parse(intent.getStringExtra(EXTRA_TRACK_URI));
            } catch (Exception e) {
                Log.e(TAG, "bad track uri", e);
            }
        }
        if (trackUri == null) {
            Log.e(TAG, "no track uri, stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) {
            // 已在播：换曲 = 重新解码 + 重置调度
            restartDecoder();
        } else {
            startAll();
        }
        return START_STICKY;
    }

    private void startForegroundWithType() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "Snapcast 服务器", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_media_play)
                .setContentTitle("Snapcast 服务器运行中")
                .setContentText("其他设备连接本机 1704 端口即可同步播放")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(456, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(456, n);
        }
    }

    @Override
    public void onDestroy() {
        stopAll();
        RUNNING = false;
        super.onDestroy();
    }

    private synchronized void startAll() {
        if (running) return;
        running = true;
        msgId = 0;
        seq = 0;
        paused = false;
        basePositionSec = 0;
        consumedBytes = 0;
        clients.clear();
        pcmQueue.clear();
        rebuildEq();
        startDecoder();
        startStreamServer();
        startHttpServer();
        startTick();
    }

    private synchronized void stopAll() {
        running = false;
        try {
            if (streamServer != null) streamServer.close();
            if (httpServer != null) httpServer.close();
        } catch (Exception ignored) {
        }
        for (Socket s : clients.keySet()) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        clients.clear();
        if (decoderThread != null) decoderThread.interrupt();
        if (tickThread != null) tickThread.interrupt();
        Log.i(TAG, "server stopped");
    }

    private synchronized void restartDecoder() {
        pcmQueue.clear();
        basePositionSec = 0;
        consumedBytes = 0;
        paused = false;
        seq = 0;
        startUs = nowUs() + 300000;
        startDecoder();
    }

    public static boolean isRunning() {
        return RUNNING;
    }

    // ================= 音源解码 =================

    private void startDecoder() {
        final Uri uri = trackUri;
        if (uri == null) return;
        decoderThread = new Thread(() -> decodeLoop(uri), "ss-decoder");
        decoderThread.start();
    }

    private void decodeLoop(Uri uri) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(this, uri, null);
            int trackIdx = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIdx = i;
                    fmt = f;
                    break;
                }
            }
            if (trackIdx < 0) throw new IllegalStateException("no audio track");
            extractor.selectTrack(trackIdx);
            sampleRate = fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            channels = fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            durationUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : 0;
            String mime = fmt.getString(MediaFormat.KEY_MIME);
            trackName = queryTrackName();

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();

            int chunkBytes = chunkBytes();
            ByteArrayOutputStream pending = new ByteArrayOutputStream(chunkBytes * 4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEos = false;

            while (running) {
                // 处理跳转请求
                if (seekRequestUs >= 0) {
                    long target = seekRequestUs;
                    seekRequestUs = -1;
                    pcmQueue.clear();
                    pending.reset();
                    basePositionSec = target / 1000000.0;
                    consumedBytes = 0;
                    extractor.seekTo(target, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    codec.flush();
                    inputEos = false;
                }

                if (!inputEos) {
                    int inIdx = codec.dequeueInputBuffer(10000);
                    if (inIdx >= 0) {
                        ByteBuffer ib = codec.getInputBuffer(inIdx);
                        if (ib != null) {
                            int sz = extractor.readSampleData(ib, 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            } else {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.getSampleTime(), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10000);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    sampleRate = of.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sampleRate;
                    channels = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                    rebuildEq();
                } else if (outIdx >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(outIdx);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        boolean isFloat = false;
                        MediaFormat of = codec.getOutputFormat();
                        if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                                && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT)
                            isFloat = true;
                        if (isFloat) {
                            // float -> s16
                            int n = ob.remaining() / 4;
                            ByteBuffer s16 = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
                            for (int i = 0; i < n; i++) {
                                float v = ob.getFloat();
                                int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
                                s16.putShort((short) s);
                            }
                            pending.write(s16.array());
                        } else {
                            byte[] b = new byte[info.size];
                            ob.get(b);
                            pending.write(b);
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIdx, false);

                    // 凑齐整块就入队
                    byte[] acc = pending.toByteArray();
                    while (acc.length >= chunkBytes) {
                        byte[] chunk = new byte[chunkBytes];
                        System.arraycopy(acc, 0, chunk, 0, chunkBytes);
                        byte[] rest = new byte[acc.length - chunkBytes];
                        System.arraycopy(acc, chunkBytes, rest, 0, rest.length);
                        pending.reset();
                        pending.write(rest);
                        acc = rest;
                        if (running) pcmQueue.put(chunk);
                    }
                    if (eos) {
                        // 循环播放
                        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                        codec.flush();
                        inputEos = false;
                    }
                }
            }
        } catch (InterruptedException ie) {
            // 正常停止
        } catch (Exception e) {
            Log.e(TAG, "decoder error", e);
        } finally {
            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }
            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    private String queryTrackName() {
        try {
            android.database.Cursor c = getContentResolver().query(trackUri, null, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) {
                    int ti = c.getColumnIndex(android.provider.MediaStore.Audio.AudioColumns.TITLE);
                    int ai = c.getColumnIndex(android.provider.MediaStore.Audio.AudioColumns.ARTIST);
                    if (ti >= 0) trackName = c.getString(ti);
                    if (ai >= 0) trackArtist = c.getString(ai) == null ? "" : c.getString(ai);
                }
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (trackName == null || trackName.isEmpty())
            trackName = trackUri.getLastPathSegment();
        return trackName;
    }

    private int chunkBytes() {
        return sampleRate * channels * 2 * CHUNK_MS / 1000;
    }

    private double bytesPerMs() {
        return sampleRate * channels * 2 / 1000.0;
    }

    // ================= 调度（tick）=================

    private void startTick() {
        startUs = nowUs() + 300000;
        tickThread = new Thread(() -> {
            while (running) {
                try {
                    double targetUs = startUs + seq * CHUNK_MS * 1000;
                    long waitMs = (long) ((targetUs - nowUs()) / 1000);
                    if (waitMs > 0) Thread.sleep(waitMs);
                    else Thread.sleep(1);
                    if (!running) break;

                    byte[] audio;
                    if (paused) {
                        audio = new byte[chunkBytes()]; // 静音块，不推进位置（同 snapnode）
                    } else {
                        audio = pcmQueue.take();
                        consumedBytes += audio.length;
                    }
                    audio = applyGlobalEq(audio);
                    long chunkSeq = seq;
                    for (java.util.Map.Entry<Socket, ClientInfo> en : clients.entrySet()) {
                        ClientInfo ci = en.getValue();
                        long[] ts = usToTv(targetUs + ci.offsetMs * 1000);
                        byte[] pcm = applyBand(ci, audio);
                        sendMsg(en.getKey(), T_WIRE_CHUNK, nextMsgId(), 0,
                                payChunk(ts, pcm));
                    }
                    seq = chunkSeq + 1;
                } catch (InterruptedException ie) {
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "tick error", e);
                }
            }
        }, "ss-tick");
        tickThread.start();
    }

    // ================= 时间 =================

    private double nowUs() {
        return (System.nanoTime() - procStartNs) / 1000.0;
    }

    private long[] nowTv() {
        return usToTv(nowUs());
    }

    private static long[] usToTv(double us) {
        long u = Math.round(us);
        return new long[]{u / 1000000, u % 1000000};
    }

    // ================= 协议帧 =================

    private synchronized int nextMsgId() {
        msgId = (msgId + 1) % 65536;
        return (int) msgId;
    }

    private byte[] buildBase(int type, int id, int refersTo, byte[] payload) {
        long[] sent = nowTv();
        ByteBuffer b = ByteBuffer.allocate(26 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) type);
        b.putShort((short) id);
        b.putShort((short) refersTo);
        b.putInt((int) sent[0]);
        b.putInt((int) sent[1]);
        b.putInt(0);
        b.putInt(0);
        b.putInt(payload.length);
        b.put(payload);
        return b.array();
    }

    private byte[] payJson(JSONObject o) {
        byte[] s = o.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer p = ByteBuffer.allocate(4 + s.length).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(s.length);
        p.put(s);
        return p.array();
    }

    private byte[] payCodecHeader(String codec, byte[] header) {
        byte[] c = codec.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer p = ByteBuffer.allocate(4 + c.length + 4 + header.length).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt(c.length);
        p.put(c);
        p.putInt(header.length);
        p.put(header);
        return p.array();
    }

    private byte[] payChunk(long[] ts, byte[] audio) {
        ByteBuffer p = ByteBuffer.allocate(12 + audio.length).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt((int) ts[0]);
        p.putInt((int) ts[1]);
        p.putInt(audio.length);
        p.put(audio);
        return p.array();
    }

    private byte[] payTime(long sec, int usec) {
        ByteBuffer p = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        p.putInt((int) sec);
        p.putInt(usec);
        return p.array();
    }

    /** data size 置 0 的 WAV 头（snapcast 惯例） */
    private byte[] wavHeader() {
        int rate = sampleRate, ch = channels, bits = 16;
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        h.putInt(36);
        h.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) ch);
        h.putInt(rate);
        h.putInt(rate * ch * bits / 8);
        h.putShort((short) (ch * bits / 8));
        h.putShort((short) bits);
        h.put("data".getBytes(StandardCharsets.US_ASCII));
        h.putInt(0);
        return h.array();
    }

    private JSONObject settingsJson(ClientInfo ci) {
        JSONObject o = new JSONObject();
        try {
            o.put("bufferMs", BUFFER_MS);
            o.put("latency", 0);
            o.put("muted", ci.muted);
            o.put("volume", ci.volume);
        } catch (Exception ignored) {
        }
        return o;
    }

    private void sendSettings(Socket sock, ClientInfo ci, int refersTo) {
        sendMsg(sock, T_SERVER_SETTINGS, nextMsgId(), refersTo, payJson(settingsJson(ci)));
    }

    private void sendMsg(Socket sock, int type, int id, int refersTo, byte[] payload) {
        try {
            sock.getOutputStream().write(buildBase(type, id, refersTo, payload));
            sock.getOutputStream().flush();
        } catch (Exception ignored) {
        }
    }

    // ================= stream 端口（1704）=================

    private void startStreamServer() {
        try {
            streamServer = new ServerSocket(STREAM_PORT);
        } catch (Exception e) {
            Log.e(TAG, "cannot listen 1704", e);
            return;
        }
        acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket sock = streamServer.accept();
                    sock.setTcpNoDelay(true);
                    ClientInfo ci = new ClientInfo();
                    clients.put(sock, ci);
                    Log.i(TAG, "[+] client " + sock.getRemoteSocketAddress() + " (共 " + clients.size() + ")");
                    Thread t = new Thread(() -> clientLoop(sock, ci), "ss-client");
                    t.start();
                } catch (SocketException se) {
                    break;
                } catch (Exception e) {
                    if (running) Log.e(TAG, "accept error", e);
                }
            }
        }, "ss-accept");
        acceptThread.start();
    }

    private void clientLoop(Socket sock, ClientInfo ci) {
        try {
            java.io.InputStream in = sock.getInputStream();
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            byte[] tmp = new byte[16 * 1024];
            while (running) {
                int n = in.read(tmp);
                if (n < 0) break;
                buf.put(tmp, 0, n);
                processFrames(sock, ci, buf);
            }
        } catch (Exception ignored) {
        } finally {
            clients.remove(sock);
            try {
                sock.close();
            } catch (Exception ignored) {
            }
            Log.i(TAG, "[-] client " + ci.name + " 断开 (剩余 " + clients.size() + ")");
        }
    }

    private void processFrames(Socket sock, ClientInfo ci, ByteBuffer buf) throws Exception {
        buf.flip();
        while (buf.remaining() >= 26) {
            buf.mark();
            int type = buf.getShort() & 0xFFFF;
            int id = buf.getShort() & 0xFFFF;
            int refersTo = buf.getShort() & 0xFFFF;
            buf.getInt(); // sent sec
            buf.getInt(); // sent usec
            buf.getInt(); // recv sec
            buf.getInt(); // recv usec
            int size = buf.getInt();
            if (size < 0 || size > 8 * 1024 * 1024) {
                buf.clear();
                return;
            }
            if (buf.remaining() < size) {
                buf.reset();
                break;
            }
            byte[] payload = new byte[size];
            buf.get(payload);
            handleMsg(sock, ci, type, id, payload);
        }
        buf.compact();
    }

    private void handleMsg(Socket sock, ClientInfo ci, int type, int id, byte[] payload) {
        if (type == T_HELLO) {
            try {
                if (payload.length > 4) {
                    JSONObject h = new JSONObject(new String(payload, 4, payload.length - 4, StandardCharsets.UTF_8));
                    ci.name = h.optString("HostName", h.optString("ClientName", "?"));
                    ci.version = h.optString("Version", "?");
                    ci.os = h.optString("OS", "?");
                }
            } catch (Exception ignored) {
            }
            Log.i(TAG, "    hello from " + ci.name + " v" + ci.version + " " + ci.os);
            // 关键：refersTo = Hello 请求 id，否则客户端 2s 超时断连
            sendSettings(sock, ci, id);
            byte[] ch = payCodecHeader("pcm", wavHeader());
            sendMsg(sock, T_CODEC_HEADER, nextMsgId(), 0, ch);
        } else if (type == T_TIME) {
            // payload: client sent sec/usec
            ByteBuffer p = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            long sentSec = p.getInt();
            long sentUsec = p.getInt();
            long[] recv = nowTv();
            long sec = recv[0] - sentSec;
            long usec = recv[1] - sentUsec;
            while (usec < 0) {
                usec += 1000000;
                sec -= 1;
            }
            while (usec >= 1000000) {
                usec -= 1000000;
                sec += 1;
            }
            double d = sec * 1000 + usec / 1000.0;
            synchronized (ci.diffs) {
                if (!ci.diffs.isEmpty())
                    ci.jitter = Math.abs(d - ci.diffs.get(ci.diffs.size() - 1));
                ci.diffs.add(d);
                if (ci.diffs.size() > 60) ci.diffs.remove(0);
                ci.drift = ci.diffs.get(ci.diffs.size() - 1) - ci.diffs.get(0);
            }
            sendMsg(sock, T_TIME, nextMsgId(), id, payTime(sec, (int) usec));
        }
        // T_CLIENT_INFO 等其他类型：忽略
    }

    // ================= DSP：EQ + 分频 =================

    private static class Coef {
        float b0, b1, b2, a1, a2;
    }

    private static class Biquad {
        final Coef c;
        float x1, x2, y1, y2;

        Biquad(Coef c) {
            this.c = c;
        }
    }

    private static Coef shelfCoef(String type, double f0, double gainDb, double rate, double q) {
        double A = Math.pow(10, gainDb / 40);
        double w0 = 2 * Math.PI * f0 / rate;
        double cw = Math.cos(w0), alpha = Math.sin(w0) / (2 * q);
        double b0, b1, b2, a0, a1, a2;
        if ("lowshelf".equals(type)) {
            double sq = 2 * Math.sqrt(A) * alpha;
            b0 = A * ((A + 1) - (A - 1) * cw + sq);
            b1 = 2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) - (A - 1) * cw - sq);
            a0 = (A + 1) + (A - 1) * cw + sq;
            a1 = -2 * ((A - 1) + (A + 1) * cw);
            a2 = (A + 1) + (A - 1) * cw - sq;
        } else if ("highshelf".equals(type)) {
            double sq = 2 * Math.sqrt(A) * alpha;
            b0 = A * ((A + 1) + (A - 1) * cw + sq);
            b1 = -2 * A * ((A - 1) - (A + 1) * cw);
            b2 = A * ((A + 1) + (A - 1) * cw - sq);
            a0 = (A + 1) - (A - 1) * cw + sq;
            a1 = 2 * ((A - 1) - (A + 1) * cw);
            a2 = (A + 1) - (A - 1) * cw - sq;
        } else if ("peaking".equals(type)) {
            b0 = 1 + alpha * A;
            b1 = -2 * cw;
            b2 = 1 - alpha * A;
            a0 = 1 + alpha / A;
            a1 = -2 * cw;
            a2 = 1 - alpha / A;
        } else {
            b0 = (1 - cw) / 2;
            b1 = 1 - cw;
            b2 = (1 - cw) / 2;
            a0 = 1 + alpha;
            a1 = -2 * cw;
            a2 = 1 - alpha;
            if ("highpass".equals(type)) {
                b0 = (1 + cw) / 2;
                b1 = -(1 + cw);
                b2 = (1 + cw) / 2;
            }
        }
        Coef c = new Coef();
        c.b0 = (float) (b0 / a0);
        c.b1 = (float) (b1 / a0);
        c.b2 = (float) (b2 / a0);
        c.a1 = (float) (a1 / a0);
        c.a2 = (float) (a2 / a0);
        return c;
    }

    private static float biq(Biquad f, float x) {
        Coef c = f.c;
        float y = c.b0 * x + c.b1 * f.x1 + c.b2 * f.x2 - c.a1 * f.y1 - c.a2 * f.y2;
        f.x2 = f.x1;
        f.x1 = x;
        f.y2 = f.y1;
        f.y1 = y;
        return y;
    }

    private void rebuildEq() {
        double r = sampleRate;
        chainL = new Biquad[]{
                new Biquad(shelfCoef("lowshelf", 200, eq[0], r, 0.707)),
                new Biquad(shelfCoef("peaking", 1000, eq[1], r, 1.0)),
                new Biquad(shelfCoef("highshelf", 4000, eq[2], r, 0.707))};
        chainR = new Biquad[]{
                new Biquad(shelfCoef("lowshelf", 200, eq[0], r, 0.707)),
                new Biquad(shelfCoef("peaking", 1000, eq[1], r, 1.0)),
                new Biquad(shelfCoef("highshelf", 4000, eq[2], r, 0.707))};
    }

    private byte[] applyGlobalEq(byte[] pcm) {
        if (eq[0] == 0 && eq[1] == 0 && eq[2] == 0) return pcm;
        int n = pcm.length >> 1;
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        byte[] out = new byte[pcm.length];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float x = in.getShort();
            Biquad[] chain = (i % 2 == 0) ? chainL : chainR;
            for (Biquad f : chain) x = biq(f, x);
            ob.putShort((short) Math.max(-32768, Math.min(32767, Math.round(x))));
        }
        return out;
    }

    private byte[] applyBand(ClientInfo ci, byte[] pcm) {
        if (ci.band == null || "full".equals(ci.band)) return pcm;
        if (ci.bandF == null || ci.rateAtBand != sampleRate) {
            Coef coef = shelfCoef("low".equals(ci.band) ? "lowpass" : "highpass", 300, 0, sampleRate, 0.707);
            ci.bandF = new Biquad[]{new Biquad(coef), new Biquad(coef)};
            ci.rateAtBand = sampleRate;
        }
        int n = pcm.length >> 1;
        ByteBuffer in = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        byte[] out = new byte[pcm.length];
        ByteBuffer ob = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < n; i++) {
            float x = in.getShort();
            x = biq(ci.bandF[i % 2], x);
            ob.putShort((short) Math.max(-32768, Math.min(32767, Math.round(x))));
        }
        return out;
    }

    // ================= HTTP 控制端口（1780）=================

    private String localIp() {
        try {
            java.util.ArrayList<String> all = new java.util.ArrayList<>();
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (Enumeration<java.net.InetAddress> ia = ni.getInetAddresses(); ia.hasMoreElements(); ) {
                    java.net.InetAddress a = ia.nextElement();
                    if (!a.isLoopbackAddress() && a.getAddress().length == 4)
                        all.add(a.getHostAddress());
                }
            }
            for (String ip : all) if (ip.startsWith("192.168.")) return ip;
            return all.isEmpty() ? "127.0.0.1" : all.get(0);
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private double positionSec() {
        return basePositionSec + consumedBytes / bytesPerMs() / 1000.0;
    }

    private void startHttpServer() {
        try {
            httpServer = new ServerSocket(HTTP_PORT);
        } catch (Exception e) {
            Log.e(TAG, "cannot listen 1780", e);
            return;
        }
        httpThread = new Thread(() -> {
            while (running) {
                try {
                    Socket sock = httpServer.accept();
                    Thread t = new Thread(() -> httpLoop(sock), "ss-http");
                    t.start();
                } catch (SocketException se) {
                    break;
                } catch (Exception e) {
                    if (running) Log.e(TAG, "http accept error", e);
                }
            }
        }, "ss-http-accept");
        httpThread.start();
    }

    private void httpLoop(Socket sock) {
        try {
            sock.setSoTimeout(3000);
            BufferedReader r = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.US_ASCII));
            String line = r.readLine();
            if (line == null || !line.startsWith("GET")) {
                sock.close();
                return;
            }
            String path = line.split(" ")[1];
            String hdr;
            int contentLen = 0;
            while ((hdr = r.readLine()) != null && !hdr.isEmpty()) {
                if (hdr.toLowerCase().startsWith("content-length:"))
                    contentLen = Integer.parseInt(hdr.substring(15).trim());
            }
            // 吞掉 body（POST 兼容）
            if (contentLen > 0) {
                char[] body = new char[contentLen];
                r.read(body);
            }

            String resp = route(path);
            byte[] body = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream os = sock.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Access-Control-Allow-Origin: *\r\nConnection: close\r\nContent-Length: "
                    + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            os.write(body);
            os.flush();
            sock.close();
        } catch (SocketTimeoutException ignored) {
        } catch (Exception e) {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    private String route(String rawPath) throws Exception {
        String path = rawPath;
        java.util.HashMap<String, String> q = new java.util.HashMap<>();
        int qi = path.indexOf('?');
        if (qi >= 0) {
            String qs = path.substring(qi + 1);
            path = path.substring(0, qi);
            for (String kv : qs.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0)
                    q.put(java.net.URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                            java.net.URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
            }
        }

        if ("/api/status".equals(path)) {
            JSONObject o = new JSONObject();
            JSONObject srv = new JSONObject();
            srv.put("host", localIp());
            srv.put("port", STREAM_PORT);
            srv.put("source", trackName);
            o.put("server", srv);
            JSONObject media = new JSONObject();
            media.put("name", trackName);
            media.put("title", trackName);
            media.put("artist", trackArtist);
            media.put("durationSec", (int) (durationUs / 1000000));
            media.put("positionSec", (int) positionSec());
            media.put("paused", paused);
            o.put("media", media);
            JSONObject e = new JSONObject();
            e.put("low", eq[0]);
            e.put("mid", eq[1]);
            e.put("high", eq[2]);
            e.put("preset", eqPreset);
            o.put("eq", e);
            JSONArray arr = new JSONArray();
            for (ClientInfo ci : clients.values()) {
                JSONObject c = new JSONObject();
                c.put("id", ci.id);
                c.put("name", ci.name);
                c.put("os", ci.os);
                c.put("version", ci.version);
                c.put("volume", ci.volume);
                c.put("muted", ci.muted);
                c.put("jitter", Math.round(ci.jitter * 100) / 100.0);
                c.put("drift", Math.round(ci.drift * 100) / 100.0);
                c.put("samples", ci.diffs.size());
                c.put("offsetMs", ci.offsetMs);
                c.put("band", ci.band);
                arr.put(c);
            }
            o.put("clients", arr);
            return o.toString();
        }

        if ("/api/pause".equals(path)) {
            paused = "1".equals(q.get("p"));
            return new JSONObject().put("ok", true).put("paused", paused).toString();
        }

        if ("/api/seek".equals(path)) {
            double sec = Double.parseDouble(q.containsKey("sec") ? q.get("sec") : "0");
            long targetUs = (long) ((positionSec() + sec) * 1000000);
            if (targetUs < 0) targetUs = 0;
            if (durationUs > 0 && targetUs > durationUs) targetUs = durationUs - 1000000;
            seekRequestUs = targetUs;
            return new JSONObject().put("ok", true).toString();
        }

        if ("/api/vol".equals(path)) {
            int v = Integer.parseInt(q.containsKey("v") ? q.get("v") : "100");
            v = Math.max(0, Math.min(100, v));
            String id = q.get("id");
            if (id != null) {
                ClientInfo ci = findById(id);
                if (ci != null) {
                    ci.volume = v;
                    resocketSendSettings(ci);
                }
            } else {
                for (ClientInfo ci : clients.values()) {
                    ci.volume = v;
                    resocketSendSettings(ci);
                }
            }
            return new JSONObject().put("ok", true).toString();
        }

        if ("/api/mute".equals(path)) {
            String id = q.get("id");
            if (id != null) {
                ClientInfo ci = findById(id);
                if (ci != null) {
                    ci.muted = q.containsKey("m") ? ("1".equals(q.get("m")) || "true".equals(q.get("m"))) : !ci.muted;
                    resocketSendSettings(ci);
                }
            } else {
                boolean m = q.containsKey("m") && ("1".equals(q.get("m")) || "true".equals(q.get("m")));
                for (ClientInfo ci : clients.values()) {
                    ci.muted = m;
                    resocketSendSettings(ci);
                }
            }
            return new JSONObject().put("ok", true).toString();
        }

        if ("/api/eq".equals(path)) {
            java.util.Map<String, float[]> presets = new java.util.HashMap<>();
            presets.put("flat", new float[]{0, 0, 0});
            presets.put("bass", new float[]{6, 0, -1});
            presets.put("vocal", new float[]{-2, 4, 1});
            presets.put("treble", new float[]{-1, 0, 6});
            presets.put("warm", new float[]{4, 1, -2});
            String p = q.get("preset");
            if (p != null && presets.containsKey(p)) {
                float[] v = presets.get(p);
                eq[0] = v[0];
                eq[1] = v[1];
                eq[2] = v[2];
                eqPreset = p;
            }
            for (String k : new String[]{"low", "mid", "high"}) {
                if (q.containsKey(k)) {
                    try {
                        float v = Float.parseFloat(q.get(k));
                        eq("low".equals(k) ? 0 : "mid".equals(k) ? 1 : 2, v);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            rebuildEq();
            JSONObject e = new JSONObject();
            e.put("ok", true);
            e.put("low", eq[0]);
            e.put("mid", eq[1]);
            e.put("high", eq[2]);
            e.put("preset", eqPreset);
            return e.toString();
        }

        if ("/api/band".equals(path)) {
            ClientInfo ci = findById(q.get("id"));
            String band = q.get("band");
            if (ci != null && ("full".equals(band) || "low".equals(band) || "high".equals(band))) {
                ci.band = band;
                ci.bandF = null; // 下个 tick 重建滤波器
            }
            return new JSONObject().put("ok", ci != null).toString();
        }

        if ("/api/offset".equals(path)) {
            ClientInfo ci = findById(q.get("id"));
            int ms = 0;
            try {
                ms = Integer.parseInt(q.containsKey("ms") ? q.get("ms") : "0");
            } catch (NumberFormatException ignored) {
            }
            ms = Math.max(-200, Math.min(500, ms));
            if (ci != null) ci.offsetMs = ms;
            return new JSONObject().put("ok", ci != null).toString();
        }

        JSONObject nf = new JSONObject();
        nf.put("error", "not found");
        return nf.toString();
    }

    private void eq(int idx, float v) {
        eq[idx] = Math.max(-12, Math.min(12, v));
    }

    private ClientInfo findById(String id) {
        if (id == null) return null;
        for (ClientInfo ci : clients.values())
            if (String.valueOf(ci.id).equals(id)) return ci;
        return null;
    }

    private void resocketSendSettings(ClientInfo ci) {
        for (java.util.Map.Entry<Socket, ClientInfo> en : clients.entrySet())
            if (en.getValue() == ci) sendSettings(en.getKey(), ci, 0);
    }

    // ================= 客户端信息 =================

    private static class ClientInfo {
        static int seqCounter = 0;
        final int id = ++seqCounter;
        String name = "?", os = "?", version = "?";
        int volume = 100;
        boolean muted = false;
        int offsetMs = 0;
        String band = "full";
        Biquad[] bandF = null;
        int rateAtBand = 0;
        double jitter = 0, drift = 0;
        final java.util.ArrayList<Double> diffs = new java.util.ArrayList<>();
    }
}
