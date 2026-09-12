package io.github.fnyoat.tipismine.ui;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;

import io.github.fnyoat.tipismine.hook.Config;
import io.github.fnyoat.tipismine.hook.ConfigAccess;

/**
 * 轻量 HTTP(S) 服务器，供外部应用通过网络直接更新模块配置（纯 ServerSocket 实现，
 * Android 无 {@code com.sun.net.httpserver}）。
 *
 * <p>端点：{@code POST /config}，Body 为 JSON，Key 对应 {@link Config} 的 KEY_* 常量。
 * 认证：请求头 {@code Authorization: Bearer <api_key>}（api_key 为空则免认证）。
 * 绑定：{@code api_bind_ip}（默认 0.0.0.0，可填 127.0.0.1 仅本机）；
 * 协议：{@code api_scheme} 可选 http / https（https 用构建期内置的自签名证书）。
 *
 * <p>示例：
 * <pre>
 * curl -k -X POST https://&lt;ip&gt;:8080/config \
 *   -H "Content-Type: application/json" \
 *   -H "Authorization: Bearer my_secret" \
 *   -d '{"owner_mode":"show","owner_text":"我","vpn_mode":"default"}'
 * </pre>
 */
final class ConfigHttpServer {

    private static final String TAG = "TipIsMine.HTTP";

    /** 内置自签名证书（PKCS12，构建时由 gradle 任务生成）的存储密码。 */
    private static final char[] KEYSTORE_PASS = "tipismine".toCharArray();
    private static final String KEYSTORE_ASSET = "tipismine_https.p12";

    /** 请求 body 上限：配置 JSON 极小，防御异常的大包（OOM / 攻击）。 */
    private static final int MAX_BODY_BYTES = 64 * 1024;
    /** 同时在处理的连接上限：防御恶意客户端以海量连接耗线程（ANR / 拒绝服务）。 */
    private static final int MAX_CONCURRENT = 8;

    private ServerSocket server;
    private Thread acceptThread;
    private final Context appContext;
    private final ConfigAccess store;
    private final int port;
    private final String apiKey;
    private final InetAddress bindAddr;
    private final boolean https;
    private final Semaphore connSlots = new Semaphore(MAX_CONCURRENT);

    ConfigHttpServer(Context context, ConfigAccess store, int port, String apiKey,
                     String bindIp, String scheme) {
        this.appContext = context.getApplicationContext();
        this.store = store;
        this.port = port;
        this.apiKey = apiKey;
        this.bindAddr = resolveBind(bindIp);
        this.https = "https".equalsIgnoreCase(scheme);
    }

    private static InetAddress resolveBind(String bindIp) {
        if (bindIp == null || bindIp.trim().isEmpty()) return null; // 全接口
        try {
            return InetAddress.getByName(bindIp.trim());
        } catch (UnknownHostException e) {
            Log.e(TAG, "bad bind ip: " + bindIp + ", fallback all interfaces");
            return null;
        }
    }

    /** 启动 HTTP(S) 服务；已启动时静默忽略。 */
    synchronized void start() {
        if (server != null) return;
        try {
            server = createServerSocket();
            server.setReuseAddress(true);
            if (server instanceof SSLServerSocket) {
                SSLServerSocket ssl = (SSLServerSocket) server;
                ssl.setNeedClientAuth(false);
                Log.i(TAG, "https started on port " + port
                        + (bindAddr != null ? " bind " + bindAddr.getHostAddress() : ""));
            } else {
                Log.i(TAG, "http started on port " + port
                        + (bindAddr != null ? " bind " + bindAddr.getHostAddress() : ""));
            }
            acceptThread = new Thread(this::acceptLoop, "tipismine-http");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            closeQuietly(server);
            server = null;
        }
    }

    private ServerSocket createServerSocket() throws Exception {
        if (https) {
            SSLContext ctx = sslContext();
            return ctx.getServerSocketFactory().createServerSocket(port, 50, bindAddr);
        }
        if (bindAddr != null) {
            return new ServerSocket(port, 50, bindAddr);
        }
        return new ServerSocket(port);
    }

    private SSLContext sslContext() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = appContext.getAssets().open(KEYSTORE_ASSET)) {
            ks.load(in, KEYSTORE_PASS);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PASS);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return ctx;
    }

    /** 停止 HTTP 服务；未启动时静默忽略。 */
    synchronized void stop() {
        if (server != null) {
            closeQuietly(server);
            server = null;
        }
        Thread t = acceptThread;
        if (t != null) {
            t.interrupt();
            acceptThread = null;
        }
    }

    synchronized boolean isRunning() {
        return server != null;
    }

    private void acceptLoop() {
        while (true) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                return; // 服务已停止
            }
            final Socket c = client;
            Thread w = new Thread(() -> {
                try {
                    try {
                        connSlots.acquire();
                    } catch (InterruptedException ie) {
                        closeQuietly(c);
                        return;
                    }
                    try {
                        handle(c);
                    } finally {
                        connSlots.release();
                    }
                } finally {
                    closeQuietly(c);
                }
            }, "tipismine-http-c");
            w.setDaemon(true);
            w.start();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 单请求处理                                                           */
    /* ------------------------------------------------------------------ */

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

            /* 请求行 */
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                send(socket, 400, "{\"error\":\"empty request\"}");
                return;
            }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "";
            if (!"POST".equalsIgnoreCase(method)) {
                send(socket, 405, "{\"error\":\"POST only\"}");
                return;
            }
            if (!"/config".equals(path)) {
                send(socket, 404, "{\"error\":\"not found\"}");
                return;
            }

            /* Headers */
            int contentLength = -1;
            String authorization = null;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int ci = line.indexOf(':');
                if (ci < 0) continue;
                String name = line.substring(0, ci).trim();
                String value = line.substring(ci + 1).trim();
                if ("Content-Length".equalsIgnoreCase(name)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                    }
                } else if ("Authorization".equalsIgnoreCase(name)) {
                    authorization = value;
                }
            }

            /* 认证（恒定时间比较，防时序侧信道） */
            if (apiKey != null && !apiKey.isEmpty()) {
                if (authorization == null
                        || !constantTimeEquals("Bearer " + apiKey, authorization)) {
                    send(socket, 401, "{\"error\":\"unauthorized\"}");
                    return;
                }
            }

            /* Body */
            if (contentLength < 0) {
                send(socket, 400, "{\"error\":\"missing Content-Length\"}");
                return;
            }
            if (contentLength > MAX_BODY_BYTES) {
                send(socket, 413, "{\"error\":\"payload too large\"}");
                return;
            }
            byte[] body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int n = in.read(body, off, contentLength - off);
                if (n < 0) break;
                off += n;
            }
            JSONObject json = new JSONObject(new String(body, 0, off, StandardCharsets.UTF_8));

            /* 写入 */
            Map<String, Object> values = new HashMap<>();
            java.util.Iterator<String> it = json.keys();
            while (it.hasNext()) {
                String key = it.next();
                values.put(key, json.get(key));
            }
            store.write(appContext, values);

            send(socket, 200, "{\"ok\":true}");
        } catch (Exception e) {
            Log.e(TAG, "handle error", e);
            try {
                send(socket, 400, "{\"error\":\"" + sanitize(e.getMessage()) + "\"}");
            } catch (IOException ignored) {
            }
        } finally {
            closeQuietly(socket);
        }
    }

    private static String sanitize(String s) {
        if (s == null) return "bad request";
        return s.replace("\"", "'").replace("\n", " ");
    }

    /** 恒定时间字符串比较：按位异或累计，长度不一致也不提前返回，防时序侧信道。 */
    private static boolean constantTimeEquals(String a, String b) {
        int diff = a.length() ^ b.length();
        int n = Math.max(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            int ca = i < a.length() ? a.charAt(i) : 0;
            int cb = i < b.length() ? b.charAt(i) : 0;
            diff |= ca ^ cb;
        }
        return diff == 0;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void send(Socket socket, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        header.append("Content-Type: application/json; charset=utf-8\r\n");
        header.append("Content-Length: ").append(bytes.length).append("\r\n");
        header.append("Connection: close\r\n");
        header.append("\r\n");
        OutputStream os = socket.getOutputStream();
        os.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        os.write(bytes);
        os.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }

    private static void closeQuietly(Object closeable) {
        if (!(closeable instanceof java.io.Closeable)) return;
        try {
            ((java.io.Closeable) closeable).close();
        } catch (IOException ignored) {
        }
    }
}