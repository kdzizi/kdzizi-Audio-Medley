package de.badaix.snapcast.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.core.content.ContextCompat;

import de.badaix.snapcast.ServerService;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务端角色移交（与 snapnode 的 /api/who、/api/handover、/api/resume 配对）。
 * <p>
 * 同一局域网只能有一个"源"：PC 的 node server 和手机的 ServerService 都是
 * 1704 放流 + 1780 控制，同时开会打架（客户端自动发现时连到谁全看运气）。
 * <p>
 * 移交的顺序必须是 <b>先立后废</b>：
 * <pre>
 *   接管（手机 ← PC）：读 PC 进度 → 本机起流 → 等本机 1704 就绪 → 叫 PC /api/handover 退位
 *   交还（手机 → PC）：叫 PC /api/resume 起流 → 等 PC 1704 就绪 → 停本机服务
 * </pre>
 * 反过来（先关再开）会造出一段"没有源"的真空期，客户端会静音 + 重连抖动。
 */
public class ServerHandover {

    private static final String TAG = "ServerHandover";

    public static final int STREAM_PORT = 1704;
    public static final int HTTP_PORT = 1780;

    public interface Cb {
        /** 回调在后台线程，调用方自己切回 UI 线程 */
        void done(boolean ok, String msg);
    }

    public interface ProbeCb {
        /** ip == null 表示局域网里没有别的服务端 */
        void onResult(String ip);
    }

    private ServerHandover() {
    }

    // ---------------- 探测 ----------------

    /** 扫局域网有没有别的服务端（就是 LanScanner 的阻塞版包装） */
    public static void probe(int port, final ProbeCb cb) {
        final AtomicReference<String> hit = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        LanScanner sc = new LanScanner(port, new LanScanner.Listener() {
            @Override
            public void onFound(String host, int p) {
                hit.compareAndSet(null, host);
            }

            @Override
            public void onFinished(boolean found) {
                done.countDown();
            }
        });
        sc.start();
        new Thread(() -> {
            try {
                done.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            cb.onResult(hit.get());
        }, "handover-probe").start();
    }

    // ---------------- 接管 ----------------

    /** 让本机接管播放权：带进度起流，然后叫远端退位 */
    public static void takeOver(Context ctx, String remoteIp, int remoteHttpPort, Uri track, Cb cb) {
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            if (track == null) {
                cb.done(false, "没有选择音频文件");
                return;
            }
            double pos = fetchPositionSec(remoteIp, remoteHttpPort);
            Log.i(TAG, "take over from " + remoteIp + " at " + pos + "s");

            // 1) 先立：本机起流（带上远端进度，做到"进度无缝"）
            Intent i = new Intent(appCtx, ServerService.class);
            i.putExtra(ServerService.EXTRA_TRACK_URI, track.toString());
            if (pos > 0) i.putExtra(ServerService.EXTRA_SEEK_SEC, pos);
            try {
                ContextCompat.startForegroundService(appCtx, i);
            } catch (Exception e) {
                cb.done(false, "启动本机服务失败: " + e.getMessage());
                return;
            }
            if (!waitTcp("127.0.0.1", STREAM_PORT, 8000)) {
                cb.done(false, "本机 1704 没起来，已放弃接管（远端保持原状）");
                return;
            }

            // 2) 后废：远端把播放权交出来（它会断开自己的客户端并停流）
            String me = LanScanner.localIpv4();
            String url = base(remoteIp, remoteHttpPort) + "/api/handover?to=" + me + "&http=" + HTTP_PORT;
            String r = httpGet(url);
            if (r == null) {
                cb.done(true, "本机已接管，但远端 " + remoteIp + " 没响应退位（可能已离线）");
            } else {
                cb.done(true, "已接管播放权，客户端几秒内自动切到本机（" + me + "）");
            }
        }, "handover-take").start();
    }

    // ---------------- 交还 ----------------

    /** 把播放权交还给远端：先让远端起流，再停本机 */
    public static void handBack(Context ctx, String remoteIp, int remoteHttpPort, Cb cb) {
        final Context appCtx = ctx.getApplicationContext();
        new Thread(() -> {
            String url = base(remoteIp, remoteHttpPort) + "/api/resume";
            String r = httpGet(url);
            if (r == null) {
                cb.done(false, "远端 " + remoteIp + " 没响应，仍由本机放流");
                return;
            }
            // 等远端真的开始发流了再停自己，避免出现没有源的真空期
            waitTcp(remoteIp, STREAM_PORT, 6000);
            appCtx.stopService(new Intent(appCtx, ServerService.class));
            cb.done(true, "播放权已交还 " + remoteIp + "，本机转为中控/客户端");
        }, "handover-back").start();
    }

    // ---------------- 小工具 ----------------

    public static String base(String ip, int httpPort) {
        return "http://" + ip + ":" + httpPort;
    }

    /** 读远端 /api/status 里的播放进度（秒）；拿不到返回 -1 */
    public static double fetchPositionSec(String ip, int httpPort) {
        String body = httpGet(base(ip, httpPort) + "/api/status");
        if (body == null) return -1;
        try {
            JSONObject o = new JSONObject(body);
            JSONObject media = o.optJSONObject("media");
            if (media == null) return -1;
            return media.optDouble("positionSec", -1);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 远端是不是活的（能出 /api/who 就算） */
    public static boolean ping(String ip, int httpPort) {
        return httpGet(base(ip, httpPort) + "/api/who") != null;
    }

    public static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(4000);
            if (c.getResponseCode() / 100 != 2) return null;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "httpGet " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (c != null) try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    /** 等某个 TCP 端口能连上（服务端就绪信号） */
    public static boolean waitTcp(String ip, int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(ip, port), 300);
                return true;
            } catch (Exception ignored) {
            } finally {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }
}
