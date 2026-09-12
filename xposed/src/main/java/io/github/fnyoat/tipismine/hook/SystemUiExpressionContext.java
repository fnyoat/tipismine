package io.github.fnyoat.tipismine.hook;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * SystemUI 侧的表达式上下文——读取电池 / WiFi / 蓝牙等设备状态，执行 HTTP 请求。
 *
 * <p>在 SystemUI 进程中实例化（hook 侧），避免跨进程通信。
 *
 * <p>get/post 走后台预取缓存：求值线程（主线程）只读缓存，绝不阻塞；HTTP 由单线程
 * 后台执行器完成，且仅在提示可见（未被暂停）时才会发起请求。
 */
public final class SystemUiExpressionContext implements ExpressionContext {

    private final Context context;
    private final HttpCache cache = new HttpCache();

    public SystemUiExpressionContext(Context context) {
        this.context = context;
    }

    /** 由刷新器控制：提示不可见时暂停预取（缓存保留旧值，恢复可见后自动续刷）。 */
    public void setPaused(boolean paused) {
        this.cache.setPaused(paused);
    }

    /** 设置预取过期周期（毫秒），配合刷新间隔使用。 */
    public void setPrefetchTtlMs(long ttlMs) {
        this.cache.setTtlMs(ttlMs);
    }

    @Override
    public String getVariable(String name) {
        try {
            switch (name) {
                case "battery":
                    return String.valueOf(getBatteryLevel());
                case "charging":
                    return String.valueOf(isCharging());
                case "wifi":
                    return getWifiSsid();
                case "bt":
                    return getBluetoothName();
                case "time":
                    return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
                case "date":
                    return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
                default:
                    return "";
            }
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public String executeGet(String url) {
        return cache.fetch("GET", url, null, 0);
    }

    @Override
    public String executePost(String url, String body) {
        return cache.fetch("POST", url, body, 0);
    }

    @Override
    public String executeGet(String url, long ttl) {
        return cache.fetch("GET", url, null, ttl);
    }

    @Override
    public String executePost(String url, String body, long ttl) {
        return cache.fetch("POST", url, body, ttl);
    }

    @Override
    public void clearCache() {
        cache.clear();
    }

    /** hook 侧每次拿到最新配置时调用：配置签名变化（点击"应用"写入新文案等）即清空 HTTP 缓存。
     *  CachedConfigStore 1s 内返回同一 Config 实例 → 引用相同直接跳过，避免热路径重复拼串。 */
    public void checkConfig(Config cfg) {
        if (cfg == null) return;
        if (cfg == lastCheckedConfig) return;
        lastCheckedConfig = cfg;
        String sig = cfg.exprSignature();
        String last = lastExprSignature;
        if (last != null && !last.equals(sig)) {
            cache.clear();
        }
        lastExprSignature = sig;
    }

    private volatile Config lastCheckedConfig;
    private volatile String lastExprSignature;

    /* ------------------------------------------------------------------ */
    /* 设备状态                                                            */
    /* ------------------------------------------------------------------ */

    private int getBatteryLevel() {
        BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (bm != null) return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return -1;
    }

    private boolean isCharging() {
        // BatteryManager.isCharging() 对部分 ROM 值不可靠（SystemUI 环境尤甚），
        // 直接用 sticky 广播 ACTION_BATTERY_CHANGED 读取充电状态，跟随系统真实值。
        Intent b = context.getApplicationContext()
                .registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return false;
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        return status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
    }

    private String getWifiSsid() {
        try {
            WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
            if (wm == null) return "";
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return "";
            String ssid = info.getSSID();
            // SSID 可能带引号
            if (ssid != null && ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid != null ? ssid : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private String getBluetoothName() {
        try {
            BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            if (bm == null) return "";
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return "";
            // 获取已配对设备中最先连接的（简化：返回第一个非空名）
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null || bonded.isEmpty()) return "";
            for (BluetoothDevice d : bonded) {
                String name = d.getName();
                if (name != null && !name.isEmpty()) return name;
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    /* ------------------------------------------------------------------ */
    /* HTTP 后台预取缓存                                                    */
    /* ------------------------------------------------------------------ */

    private static final int TIMEOUT_MS = 5000;
    /** 响应体上限（字节）：防止恶意/异常服务器把 SystemUI 进程撑爆内存。 */
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;

    /**
     * get/post 的缓存层：求值线程只读，后台单线程预取刷新；去重、暂停、TTL 过期。
     */
    private static final class HttpCache {

        private static final int MAX_ENTRIES = 16;
        private static final char SEP = '\u0001';

        private final ConcurrentHashMap<String, String> values = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> fetchedAt = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> ttlByKey = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

        private final ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tipismine-http");
                t.setDaemon(true);
                return t;
            }
        });

        private volatile boolean paused;
        private volatile long defaultTtlMs = 1000L;

        void setPaused(boolean p) {
            paused = p;
        }

        void setTtlMs(long t) {
            if (t > 0) defaultTtlMs = t;
        }

        /** 各 key 完全独立缓存；返回缓存值（过期/缺失时先返回旧值，并发发起后台刷新）；无缓存时返回空串。
         *  @param ttlMs 表达式内显式 ttl>0 时使用该值，否则用默认周期 ttl。 */
        String fetch(String method, String url, String body, long ttlMs) {
            String key = method + SEP + url + SEP + (body == null ? "" : body);
            if (ttlMs > 0) {
                ttlByKey.put(key, ttlMs); // 表达式显式 ttl 覆盖该 key 的缓存周期，之后沿用
            }
            Long keyTtl = ttlByKey.get(key);
            long effTtl = keyTtl != null && keyTtl > 0 ? keyTtl : defaultTtlMs;
            String current = values.get(key);
            Long at = fetchedAt.get(key);
            long age = at == null ? Long.MAX_VALUE : System.currentTimeMillis() - at;
            if (age >= effTtl) {
                request(key, method, url, body);
            }
            return current == null ? "" : current;
        }

        void clear() {
            values.clear();
            fetchedAt.clear();
            ttlByKey.clear();
            inFlight.clear();
        }

        /** 简单清理：超出上限时清空全部（后台线程低频触发；全清避免 LRU 实现复杂度）。 */
        void discourageOverflow() {
            if (values.size() > MAX_ENTRIES) {
                values.clear();
                fetchedAt.clear();
                ttlByKey.clear();
                inFlight.clear();
            }
        }

        private void request(String key, String method, String url, String body) {
            if (paused) return;
            if (inFlight.putIfAbsent(key, Boolean.TRUE) != null) return; // 去重
            executor.submit(() -> {
                try {
                    String v = doHttp(url, method, body);
                    if (v != null && !v.isEmpty()) {
                        values.put(key, v);
                        fetchedAt.put(key, System.currentTimeMillis());
                        discourageOverflow();
                    }
                } finally {
                    inFlight.remove(key);
                }
            });
        }
    }

    private static String doHttp(String urlStr, String method, String body) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            // 仅允许 http/https：SystemUI 高权限进程不得经表达式访问 file:// 等本地协议。
            String scheme = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(Locale.US);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return "";
            }
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            if ("POST".equals(method) && body != null) {
                conn.setDoOutput(true);
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Content-Length", String.valueOf(data.length));
                OutputStream os = conn.getOutputStream();
                os.write(data);
                os.close();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[4096];
                int total = 0;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int n;
                while ((n = is.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_RESPONSE_BYTES) {
                        return ""; // 超限：丢弃，避免内存耗尽
                    }
                    out.write(buf, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return "";
        } catch (Throwable t) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}