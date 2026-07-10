package site.hongshi.redstoneonline.frp;

import site.hongshi.redstoneonline.RedstoneOnline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import org.lwjgl.glfw.GLFW;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public class Frp {
    private static volatile boolean running = false;
    private static volatile boolean stopped = false;
    private static String cachedAddress = "";
    private static int listenPort = 10000;
    // 与 frp 服务器 7000 的控制/数据连接
    private static Socket controlSocket;

    public static int getListenPort() {
        return listenPort;
    }

    public static String getAddress() {
        if (listenPort > 0 && !cachedAddress.isEmpty()) {
            return cachedAddress + ":" + listenPort;
        }
        return null;
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    /**
     * 从 InputStream 读取一行（直到 \n），返回去掉 \n 的字符串
     */
    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1 && ch != '\n') {
            sb.append((char) ch);
        }
        return sb.toString();
    }

    /**
     * 单方向字节搬运，任一端断开时 read() 返回 -1，循环结束
     */
    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    /**
     * 启动内网穿透：
     * 1. 连接 7000 注册
     * 2. 创建隧道
     * 3. 消费 OK TUNNEL 消息
     * 4. 双向 pipe：7000 ↔ 本地 Minecraft
     */
    public static void start(String serverAddress, int serverPort, int localPort) {
        try {
            if (RedstoneOnline.apikey == null || RedstoneOnline.apikey.isEmpty()) {
                throw new Exception("apikey is empty");
            }
            cachedAddress = serverAddress;

            // 连接 7000 控制端口
            controlSocket = new Socket(serverAddress, 7000);
            controlSocket.setTcpNoDelay(true);
            OutputStream ctrlOut = controlSocket.getOutputStream();
            InputStream ctrlIn = controlSocket.getInputStream();

            // 发送 apikey\n 注册
            ctrlOut.write((RedstoneOnline.apikey + "\n").getBytes(StandardCharsets.UTF_8));
            ctrlOut.flush();

            // 读取服务端回复
            String response = readLine(ctrlIn);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Init {}:7000 -> {}", serverAddress, response);

            // 解析回复
            if (response.startsWith("OK TUNNEL ")) {
                // 已有活跃隧道，通过 API 查询端口
                HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                    .header("Authorization", RedstoneOnline.apikey)
                    .GET()
                    .build();
                HttpResponse<String> getResp = HTTP_CLIENT.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(getResp.body(), JsonObject.class);
                    if (json != null && json.has("tunnels") && json.getAsJsonArray("tunnels").size() > 0) {
                        listenPort = json.getAsJsonArray("tunnels").get(0).getAsJsonObject().get("listenPort").getAsInt();
                    }
                }
            } else {
                // 没有已有隧道，通过 HTTP 创建
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                    .header("Authorization", RedstoneOnline.apikey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
                HttpResponse<String> httpResp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int code = httpResp.statusCode();
                String body = httpResp.body();
                RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {}:3000/tunnels -> {} {}", serverAddress, code, body);

                if (code == 429) {
                    // 隧道已存在，先删后重建
                    HttpRequest delReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                        .header("Authorization", RedstoneOnline.apikey)
                        .DELETE()
                        .build();
                    HTTP_CLIENT.send(delReq, HttpResponse.BodyHandlers.ofString());

                    httpResp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    code = httpResp.statusCode();
                    body = httpResp.body();
                    RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {}:3000/tunnels (retry) -> {} {}", serverAddress, code, body);
                }

                if (code >= 200 && code < 300) {
                    JsonObject json = GSON.fromJson(body, JsonObject.class);
                    if (json != null && json.has("listenPort")) {
                        listenPort = json.get("listenPort").getAsInt();
                        RedstoneOnline.LOGGER.info("[RedstoneOnline] Tunnel created, listenPort: {}", listenPort);
                    }
                }

                // 创建后服务端会在 7000 连接上发 OK TUNNEL {id}\n，消费掉
                response = readLine(ctrlIn);
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Control response: {}", response);
            }

            running = true;

            // 共享本地 socket，R2L 和 L2R 走同一条连接
            new Thread(() -> {
                while (running) {
                    Socket ls = null;
                    try {
                        ls = new Socket("127.0.0.1", localPort);
                        ls.setTcpNoDelay(true);
                        InputStream localIn = ls.getInputStream();
                        OutputStream localOut = ls.getOutputStream();

                        Thread r2l = new Thread(() -> {
                            try { pipe(ctrlIn, localOut); } catch (Exception ignored) {}
                        }, "R2L");
                        Thread l2r = new Thread(() -> {
                            try { pipe(localIn, ctrlOut); } catch (Exception ignored) {}
                        }, "L2R");

                        r2l.start();
                        l2r.start();
                        try { r2l.join(); } catch (Exception ignored) {}
                        try { l2r.interrupt(); l2r.join(); } catch (Exception ignored) {}
                    } catch (Exception e) {
                        RedstoneOnline.LOGGER.info("[RedstoneOnline] Connection pipe: {}", e.toString());
                        try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
                    } finally {
                        try { if (ls != null) ls.close(); } catch (Exception ignored) {}
                    }
                }
                stop();
            }, "R2L-Manager").start();

            RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 隧道启动完成, listenPort={}, 本地端口={}", listenPort, localPort);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Start failed: {}", e.toString());
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            stop();
        }, "RedstoneOnline-Shutdown"));
    }

    /**
     * 获取 GLFW 窗口句柄，兼容不同 MC 版本的 API 差异
     */
    public static long getGlfwWindow() {
        Object w = net.minecraft.client.Minecraft.getInstance().getWindow();
        for (String name : new String[]{"getWindow", "getGlfwWindow", "window"}) {
            try {
                Method m = w.getClass().getMethod(name);
                if (m.getReturnType() == long.class) {
                    return (long) m.invoke(w);
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    /**
     * 停止穿透：关闭控制连接，删除隧道
     */
    public static void stop() {
        if (stopped) return;
        stopped = true;
        running = false;

        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 关闭隧道: 开始清理");
        try { controlSocket.close(); } catch (Exception e) {
            RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 关闭控制连接: {}", e.toString());
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + cachedAddress + ":3000/tunnels"))
                .header("Authorization", RedstoneOnline.apikey)
                .DELETE()
                .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            String resp = response.body();
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE {}:3000/tunnels -> {} {}", cachedAddress, code, resp);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE {}:3000/tunnels failed: {}", cachedAddress, e.getMessage());
        }
    }
}
