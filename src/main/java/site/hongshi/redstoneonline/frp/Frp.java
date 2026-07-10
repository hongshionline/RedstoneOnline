package site.hongshi.redstoneonline.frp;

import site.hongshi.redstoneonline.RedstoneOnline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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
    private static Socket controlSocket;

    public static int getListenPort() { return listenPort; }
    public static String getAddress() {
        if (listenPort > 0 && !cachedAddress.isEmpty()) return cachedAddress + ":" + listenPort;
        return null;
    }

    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1 && ch != '\n') sb.append((char) ch);
        return sb.toString();
    }

    private static void pipe(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); }
    }

    public static void start(String serverAddress, int serverPort, int localPort) {
        start(serverAddress, serverPort, localPort, 1);
    }

    public static void start(String serverAddress, int serverPort, int localPort, int maxPlayers) {
        try {
            if (RedstoneOnline.apikey == null || RedstoneOnline.apikey.isEmpty())
                throw new Exception("apikey is empty");
            cachedAddress = serverAddress;

            controlSocket = new Socket(serverAddress, 7000);
            controlSocket.setTcpNoDelay(true);
            OutputStream ctrlOut = controlSocket.getOutputStream();
            InputStream ctrlIn = controlSocket.getInputStream();

            ctrlOut.write((RedstoneOnline.apikey + "\n").getBytes(StandardCharsets.UTF_8));
            ctrlOut.flush();
            String response = readLine(ctrlIn);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Init {}:7000 -> {}", serverAddress, response);

            if (response.startsWith("OK TUNNEL ")) {
                HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                    .header("Authorization", RedstoneOnline.apikey).GET().build();
                HttpResponse<String> getResp = HTTP_CLIENT.send(getReq, HttpResponse.BodyHandlers.ofString());
                if (getResp.statusCode() == 200) {
                    JsonObject json = GSON.fromJson(getResp.body(), JsonObject.class);
                    if (json != null && json.has("tunnels") && json.getAsJsonArray("tunnels").size() > 0)
                        listenPort = json.getAsJsonArray("tunnels").get(0).getAsJsonObject().get("listenPort").getAsInt();
                }
            } else {
                URI tunnelUri = URI.create("http://" + serverAddress + ":3000/tunnels?maxPlayers=" + maxPlayers);
                HttpRequest request = HttpRequest.newBuilder().uri(tunnelUri)
                    .header("Authorization", RedstoneOnline.apikey).POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> httpResp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int code = httpResp.statusCode();
                String body = httpResp.body();
                RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {} -> {} {}", tunnelUri, code, body);

                if (code == 429) {
                    HttpRequest delReq = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                        .header("Authorization", RedstoneOnline.apikey).DELETE().build();
                    HTTP_CLIENT.send(delReq, HttpResponse.BodyHandlers.ofString());
                    httpResp = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    code = httpResp.statusCode(); body = httpResp.body();
                    RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {} (retry) -> {} {}", tunnelUri, code, body);
                }

                if (code >= 200 && code < 300) {
                    JsonObject json = GSON.fromJson(body, JsonObject.class);
                    if (json != null && json.has("listenPort")) {
                        listenPort = json.get("listenPort").getAsInt();
                        RedstoneOnline.LOGGER.info("[RedstoneOnline] Tunnel created, listenPort: {}", listenPort);
                    }
                }
                response = readLine(ctrlIn);
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Control response: {}", response);
            }

            running = true;
            controlSocket.setSoTimeout(5000); // 后续读超时5秒，定期检查本地连接
            new Thread(() -> {
                while (running) {
                    try (Socket ls = new Socket("127.0.0.1", localPort)) {
                        ls.setTcpNoDelay(true);
                        ls.setKeepAlive(true);
                        InputStream li0 = ls.getInputStream();
                        OutputStream lo0 = ls.getOutputStream();
                        final InputStream i0 = li0;
                        Thread l2r = new Thread(() -> {
                            try { pipe(i0, ctrlOut); } catch (Exception ignored) {}
                        }, "L2R");
                        l2r.start();

                        Socket currentLs = ls;
                        byte[] buf = new byte[4096];
                        while (running) {
                            int n;
                            try {
                                n = ctrlIn.read(buf);
                                if (n == -1) break;
                            } catch (java.net.SocketTimeoutException e) {
                                // 读超时，检查本地连接并重连
                                if (currentLs.isClosed() || !currentLs.isConnected()) {
                                    throw new Exception("local socket timeout");
                                }
                                continue;
                            }
                            // 检查本地连接是否还存活
                            if (currentLs.isClosed() || !currentLs.isConnected()) {
                                throw new Exception("local socket closed");
                            }
                            for (int retry = 0; retry < 3 && running; retry++) {
                                try {
                                    lo0.write(buf, 0, n); lo0.flush(); break;
                                } catch (Exception e) {
                                    try { Thread.sleep(1000); } catch (InterruptedException ex) { break; }
                                    try {
                                        currentLs.close();
                                    } catch (Exception ignored) {}
                                    try {
                                        currentLs = new Socket("127.0.0.1", localPort);
                                        currentLs.setTcpNoDelay(true);
                                        li0 = currentLs.getInputStream();
                                        lo0 = currentLs.getOutputStream();
                                        l2r.interrupt();
                                        final InputStream fli = li0;
                                        l2r = new Thread(() -> {
                                            try { pipe(fli, ctrlOut); } catch (Exception ignored) {}
                                        }, "L2R");
                                        l2r.start();
                                    } catch (Exception ignored) {}
                                }
                            }
                        }
                    } catch (Exception e) {
                        if (running) try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                    }
                }
                stop();
            }, "R2L-Manager").start();

            RedstoneOnline.LOGGER.info("[RedstoneOnline] 隧道启动完成, listenPort={}, 本地端口={}", listenPort, localPort);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Start failed: {}", e.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { stop(); }, "RedstoneOnline-Shutdown"));
    }

    public static long getGlfwWindow() {
        Object w = net.minecraft.client.Minecraft.getInstance().getWindow();
        for (String name : new String[]{"getWindow", "getGlfwWindow", "getHandle", "window"}) {
            try {
                Method m = w.getClass().getMethod(name);
                Class<?> ret = m.getReturnType();
                if (ret == long.class) return (long) m.invoke(w);
                if (ret == Long.class) { Long val = (Long) m.invoke(w); if (val != null) return val; }
            } catch (Exception ignored) {}
        }
        for (String name : new String[]{"window", "handle", "glfwWindow"}) {
            try {
                java.lang.reflect.Field f = w.getClass().getField(name);
                if (f.getType() == long.class) return f.getLong(w);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static void copyToClipboard(String text) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "echo " + text + " | clip"};
            } else if (os.contains("mac")) {
                cmd = new String[]{"bash", "-c", "echo -n '" + text + "' | pbcopy"};
            } else {
                cmd = new String[]{"bash", "-c", "echo -n '" + text + "' | xclip -selection clipboard 2>/dev/null || echo -n '" + text + "' | xsel -ib"};
            }
            Runtime.getRuntime().exec(cmd);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] 剪切板命令: {}", String.join(" ", cmd));
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] 剪切板失败: {}", e.toString());
        }
    }

    public static void stop() {
        if (stopped) return;
        stopped = true; running = false;
        RedstoneOnline.LOGGER.info("[RedstoneOnline] 关闭隧道");
        try { controlSocket.close(); } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] 关闭控制连接: {}", e.toString());
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + cachedAddress + ":3000/tunnels"))
                .header("Authorization", RedstoneOnline.apikey).DELETE().build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE {}:3000/tunnels -> {} {}", cachedAddress, response.statusCode(), response.body());
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE {}:3000/tunnels failed: {}", cachedAddress, e.getMessage());
        }
    }
}
