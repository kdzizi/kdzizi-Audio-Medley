package de.badaix.snapcast;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import de.badaix.snapcast.utils.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 中控视图（snapnode 扩展）：直接通过 HTTP (:1780) 与 snapnode 服务端交互。
 * <p>
 * 功能：正在播放信息（歌名/歌手/进度）、暂停/继续、±10s 快进快退、
 * 3 段 EQ 预设、在线设备列表（单台音量 / 静音 / 频段角色 2.1 分频）。
 * <p>
 * 与官方 Snapcast 协议无关——仅对接 snapnode 的控制台 API：
 * GET /api/status, /api/pause?p=, /api/seek?sec=, /api/vol?id=&v=,
 * /api/mute?id=, /api/band?id=&band=, /api/eq?preset=
 */
public class ControlActivity extends AppCompatActivity {

    private static final String PREFS = "control_prefs";
    private static final String KEY_HOST = "http_host";
    private static final long POLL_MS = 1000;

    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout deviceList;
    private TextView tvSong, tvArtist, tvPos, tvDur, tvStatus, tvHint;
    private SeekBar sbProgress;
    private Button btnPlay;
    private EditText etHost;

    private String baseUrl = "";
    private volatile boolean dragging = false;
    private boolean paused = false;
    private int lastPosSec = 0;
    private int durationSec = 0;
    private String eqPreset = "";

    private interface JsonCb {
        void onJson(JSONObject o);

        void onFail(String err);
    }

    // ---- 轮询 ----
    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (!baseUrl.isEmpty()) {
                fetch("/api/status", new JsonCb() {
                    @Override
                    public void onJson(JSONObject o) {
                        renderStatus(o);
                    }

                    @Override
                    public void onFail(String err) {
                        setOnline(false, err);
                    }
                });
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        tvSong = findViewById(R.id.tvSong);
        tvArtist = findViewById(R.id.tvArtist);
        tvPos = findViewById(R.id.tvPos);
        tvDur = findViewById(R.id.tvDur);
        tvStatus = findViewById(R.id.tvStatus);
        tvHint = findViewById(R.id.tvHint);
        deviceList = findViewById(R.id.deviceList);
        sbProgress = findViewById(R.id.sbProgress);
        btnPlay = findViewById(R.id.btnPlay);
        etHost = findViewById(R.id.etHost);

        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_HOST, "");
        if (saved.isEmpty()) {
            if (ServerService.isRunning()) {
                // 本机就是服务器：直接连自己
                saved = "127.0.0.1:1780";
            } else {
                // 与主界面 Settings 的 host 共享：snapnode 控制台端口固定 1780
                String snapHost = Settings.getInstance(this).getHost();
                if (snapHost != null && !snapHost.trim().isEmpty())
                    saved = snapHost.trim() + ":1780";
            }
        }
        etHost.setText(saved);
        applyHost(saved, false);

        findViewById(R.id.btnConnect).setOnClickListener(v ->
                applyHost(etHost.getText().toString().trim(), true));

        btnPlay.setOnClickListener(v -> api("/api/pause?p=" + (paused ? "0" : "1")));

        findViewById(R.id.btnBack10).setOnClickListener(v -> api("/api/seek?sec=-10"));
        findViewById(R.id.btnFwd10).setOnClickListener(v -> api("/api/seek?sec=10"));

        bindEq(R.id.btnEqFlat, "flat");
        bindEq(R.id.btnEqBass, "bass");
        bindEq(R.id.btnEqVocal, "vocal");
        bindEq(R.id.btnEqTreble, "treble");
        bindEq(R.id.btnEqWarm, "warm");

        sbProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    tvPos.setText(fmtSec(progress));
                    dragging = true;
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 服务端 seek 是相对跳转（±sec），用当前位置算 delta
                int delta = seekBar.getProgress() - lastPosSec;
                dragging = false;
                api("/api/seek?sec=" + delta);
            }
        });
    }

    private void bindEq(int btnId, final String preset) {
        findViewById(btnId).setOnClickListener(v -> api("/api/eq?preset=" + preset));
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(pollTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(pollTask);
    }

    @SuppressLint("SetTextI18n")
    private void applyHost(String host, boolean toast) {
        if (host == null || host.isEmpty()) return;
        if (!host.startsWith("http://") && !host.startsWith("https://"))
            host = "http://" + host;
        baseUrl = host.endsWith("/") ? host.substring(0, host.length() - 1) : host;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_HOST, baseUrl).apply();
        tvHint.setText("Web 控制台: " + baseUrl + "/  （浏览器打开同样可控）");
        if (toast) Toast.makeText(this, "已连接 " + baseUrl, Toast.LENGTH_SHORT).show();
    }

    // ---- 渲染 ----

    @SuppressLint("SetTextI18n")
    private void setOnline(boolean online, String err) {
        if (online) {
            tvStatus.setText("● 已连接 " + baseUrl);
            tvStatus.setTextColor(0xFF2E7D32);
        } else {
            tvStatus.setText("✕ 未连接（" + err + "）");
            tvStatus.setTextColor(0xFFC62828);
        }
    }

    @SuppressLint("SetTextI18n")
    private void renderStatus(JSONObject o) {
        setOnline(true, null);
        JSONObject media = o.optJSONObject("media");
        if (media != null) {
            String title = media.optString("title", "");
            String name = media.optString("name", "");
            String artist = media.optString("artist", "");
            tvSong.setText(title.isEmpty() ? name : title);
            tvArtist.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);
            tvArtist.setText(artist);
            durationSec = media.optInt("durationSec", 0);
            paused = media.optBoolean("paused", false);
            btnPlay.setText(paused ? "▶ 播放" : "⏸ 暂停");
            int pos = media.optInt("positionSec", 0);
            if (!dragging && durationSec > 0) {
                lastPosSec = pos;
                sbProgress.setMax(durationSec);
                sbProgress.setProgress(pos);
            }
            tvPos.setText(fmtSec(pos));
            tvDur.setText(fmtSec(durationSec));
        }

        JSONObject eq = o.optJSONObject("eq");
        if (eq != null) eqPreset = eq.optString("preset", "");
        highlightEq(R.id.btnEqFlat, "flat");
        highlightEq(R.id.btnEqBass, "bass");
        highlightEq(R.id.btnEqVocal, "vocal");
        highlightEq(R.id.btnEqTreble, "treble");
        highlightEq(R.id.btnEqWarm, "warm");

        renderClients(o.optJSONArray("clients"));
    }

    private void highlightEq(int btnId, String preset) {
        Button b = findViewById(btnId);
        boolean on = preset.equals(eqPreset);
        b.setSelected(on);
        b.setAlpha(on ? 1f : 0.55f);
    }

    @SuppressLint("SetTextI18n")
    private void renderClients(JSONArray clients) {
        if (clients == null) return;
        // 防抖：内容未变不重建（避免音量条被轮询重置、打断拖动）
        StringBuilder sig = new StringBuilder();
        for (int i = 0; i < clients.length(); i++) {
            JSONObject c = clients.optJSONObject(i);
            if (c == null) continue;
            sig.append(c.optInt("id")).append('|').append(c.optString("name")).append('|')
                    .append(c.optInt("volume")).append('|').append(c.optBoolean("muted")).append('|')
                    .append(c.optString("band", "full")).append('|')
                    .append(String.format(Locale.US, "%.2f", c.optDouble("jitter", 0))).append(';');
        }
        String key = sig.toString();
        Object tag = deviceList.getTag();
        if (tag != null && key.equals(tag.toString())) return;
        deviceList.setTag(key);
        deviceList.removeAllViews();

        for (int i = 0; i < clients.length(); i++) {
            final JSONObject c = clients.optJSONObject(i);
            if (c == null) continue;
            final int id = c.optInt("id");
            View card = getLayoutInflater().inflate(R.layout.item_control_client, deviceList, false);
            ((TextView) card.findViewById(R.id.tvName)).setText(
                    c.optString("name", "client-" + id) + "  (" + c.optString("os", "?") + ")");
            ((TextView) card.findViewById(R.id.tvMeta)).setText(
                    "时钟抖动 " + String.format(Locale.US, "%.2f", c.optDouble("jitter", 0))
                            + "ms · 偏移 " + c.optInt("offsetMs", 0) + "ms"
                            + (c.optBoolean("muted") ? " · 已静音" : ""));

            final Button btnBand = card.findViewById(R.id.btnBand);
            btnBand.setTag(c.optString("band", "full"));
            btnBand.setText("声道: " + bandLabel((String) btnBand.getTag()));
            btnBand.setOnClickListener(v ->
                    api("/api/band?id=" + id + "&band=" + nextBand((String) btnBand.getTag())));

            SeekBar vol = card.findViewById(R.id.sbVolume);
            vol.setProgress(c.optInt("volume", 100));
            vol.setOnSeekBarChangeListener(new VolumeCb(baseUrl, id));

            card.findViewById(R.id.btnMute).setOnClickListener(v -> api("/api/mute?id=" + id));
            deviceList.addView(card);
        }
    }

    private static String bandLabel(String band) {
        if ("low".equals(band)) return "低音炮";
        if ("high".equals(band)) return "中高频";
        return "全频";
    }

    private static String nextBand(String cur) {
        if ("full".equals(cur)) return "low";
        if ("low".equals(cur)) return "high";
        return "full";
    }

    private static class VolumeCb implements SeekBar.OnSeekBarChangeListener {
        private final String base;
        private final int id;
        private long lastSent = 0;

        VolumeCb(String baseUrl, int id) {
            this.base = baseUrl;
            this.id = id;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (!fromUser) return;
            long now = System.currentTimeMillis();
            if (now - lastSent > 150) { // 节流：拖动时别刷爆服务端
                lastSent = now;
                final int v = progress;
                new Thread(() -> httpGetQuiet(base + "/api/vol?id=" + id + "&v=" + v)).start();
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            new Thread(() -> httpGetQuiet(base + "/api/vol?id=" + id + "&v=" + seekBar.getProgress())).start();
        }
    }

    private static String fmtSec(int s) {
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---- 网络 ----

    private void api(String path) {
        final String url = baseUrl + path;
        new Thread(() -> httpGetQuiet(url)).start();
    }

    private static void httpGetQuiet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            c.getInputStream().close();
        } catch (Exception ignored) {
        } finally {
            if (c != null) try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private void fetch(final String path, final JsonCb cb) {
        final String url = baseUrl + path;
        new Thread(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(2500);
                c.setReadTimeout(2500);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                r.close();
                JSONObject o = new JSONObject(sb.toString());
                main.post(() -> cb.onJson(o));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                main.post(() -> cb.onFail(msg));
            } finally {
                if (c != null) try {
                    c.disconnect();
                } catch (Exception ignored) {
                }
            }
        }).start();
    }
}
