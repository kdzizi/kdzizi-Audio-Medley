package de.badaix.snapcast.utils;

import android.util.Log;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 局域网扫描兜底：mDNS 在多播被禁的网络（手机热点、部分路由器开了 AP 隔离）里收不到应答，
 * 这时直接并发探测本机所在子网的 stream 端口，谁开着 1704 谁就是 Snapserver。
 *
 * 典型 /24 子网 254 个地址、64 并发、400ms 超时 —— 一秒内出结果。
 */
public class LanScanner {

    private static final String TAG = "LanScanner";
    private static final int TIMEOUT_MS = 400;
    private static final int THREADS = 64;
    private static final int MAX_HOSTS = 1024;

    public interface Listener {
        void onFound(String host, int port);

        void onFinished(boolean found);
    }

    private final int port;
    private final Listener listener;
    private final AtomicBoolean found = new AtomicBoolean(false);
    private volatile boolean cancelled = false;
    private ExecutorService pool = null;

    public LanScanner(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    public void start() {
        final List<String> candidates = candidates();
        if (candidates.isEmpty()) {
            if (listener != null)
                listener.onFinished(false);
            return;
        }
        Log.i(TAG, "scanning " + candidates.size() + " addresses on port " + port);
        pool = Executors.newFixedThreadPool(THREADS);
        for (final String ip : candidates) {
            pool.submit(new Runnable() {
                @Override
                public void run() {
                    if (cancelled || found.get())
                        return;
                    Socket s = new Socket();
                    try {
                        s.connect(new InetSocketAddress(ip, port), TIMEOUT_MS);
                        if (s.isConnected() && found.compareAndSet(false, true)) {
                            Log.i(TAG, "found snapserver at " + ip + ":" + port);
                            if (listener != null)
                                listener.onFound(ip, port);
                        }
                    } catch (IOException e) {
                        // 这个地址没开服务，正常
                    } catch (Exception e) {
                        Log.w(TAG, "probe " + ip + ": " + e.getMessage());
                    } finally {
                        try {
                            s.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
        }
        pool.shutdown();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    pool.awaitTermination(20, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                if (listener != null)
                    listener.onFinished(found.get());
            }
        }).start();
    }

    public void cancel() {
        cancelled = true;
        if (pool != null)
            pool.shutdownNow();
    }

    /** 本机所有 IPv4 接口所在的子网，展开成候选地址（跳过自身） */
    private List<String> candidates() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(en)) {
                if (!ni.isUp() || ni.isLoopback())
                    continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress())
                        continue;
                    int prefix = ia.getNetworkPrefixLength();
                    if ((prefix < 8) || (prefix > 30))
                        prefix = 24;
                    int hosts = (1 << (32 - prefix)) - 2;
                    if (hosts > MAX_HOSTS)
                        hosts = MAX_HOSTS;
                    int base = ipToInt(addr) & netmask(prefix);
                    for (int i = 1; i <= hosts; i++) {
                        String ip = intToIp(base + i);
                        if (!ip.equals(addr.getHostAddress()))
                            out.add(ip);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "enum interfaces: " + e.getMessage());
        }
        return out;
    }

    private static int ipToInt(InetAddress a) {
        byte[] b = a.getAddress();
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16) | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static String intToIp(int v) {
        return ((v >> 24) & 0xff) + "." + ((v >> 16) & 0xff) + "." + ((v >> 8) & 0xff) + "." + (v & 0xff);
    }

    private static int netmask(int prefix) {
        return (int) (0xffffffffL << (32 - prefix));
    }
}
