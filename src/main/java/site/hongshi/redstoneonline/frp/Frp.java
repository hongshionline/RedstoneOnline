package site.hongshi.redstoneonline.frp;

import site.hongshi.redstoneonline.RedstoneOnline;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import net.minecraft.commands.CommandSourceStack;

import java.io.InputStream;
import java.io.OutputStream;
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

    public static void start(String serverAddress, int maxPlayers, Object mcServer, boolean cheats, int port) {
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
            String resp = readLine(ctrlIn);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Init {}:7000 -> {}", serverAddress, resp);

            cachedAddress = serverAddress;

            if (!resp.startsWith("OK TUNNEL ")) {
                URI tu = URI.create("http://" + serverAddress + ":3000/tunnels?maxPlayers=" + maxPlayers);
                HttpRequest req = HttpRequest.newBuilder().uri(tu)
                    .header("Authorization", RedstoneOnline.apikey).POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> hr = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                int code = hr.statusCode(); String body = hr.body();
                RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {} -> {} {}", tu, code, body);
                if (code == 429) {
                    HTTP_CLIENT.send(HttpRequest.newBuilder().uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                        .header("Authorization", RedstoneOnline.apikey).DELETE().build(), HttpResponse.BodyHandlers.ofString());
                    hr = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                    code = hr.statusCode(); body = hr.body();
                    RedstoneOnline.LOGGER.info("[RedstoneOnline] POST {} (retry) -> {} {}", tu, code, body);
                }
                if (code >= 200 && code < 300) {
                    JsonObject j = GSON.fromJson(body, JsonObject.class);
                    if (j != null && j.has("listenPort")) { listenPort = j.get("listenPort").getAsInt(); }
                }
                resp = readLine(ctrlIn);
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Control: {}", resp);
            } else {
                // 已有隧道，通过 API 查询端口
                try {
                    HttpResponse<String> gr = HTTP_CLIENT.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://" + serverAddress + ":3000/tunnels"))
                        .header("Authorization", RedstoneOnline.apikey).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                    if (gr.statusCode() == 200) {
                        JsonObject j = GSON.fromJson(gr.body(), JsonObject.class);
                        if (j != null && j.has("tunnels") && j.getAsJsonArray("tunnels").size() > 0) {
                            listenPort = j.getAsJsonArray("tunnels").get(0).getAsJsonObject().get("listenPort").getAsInt();
                        }
                    }
                } catch (Exception ignored) {}
            }

            // 隧道已创建，立即显示联机地址
            showAddress();

            controlSocket.setSoTimeout(0);
            byte[] fb = new byte[4096];
            int fl = ctrlIn.read(fb);
            if (fl <= 0) { stop(); return; }

            int lanPort = openLan(mcServer, cheats, port);
            if (lanPort <= 0) { stop(); return; }
            RedstoneOnline.LOGGER.info("[RedstoneOnline] LAN opened on port {}", lanPort);

            controlSocket.setSoTimeout(10000);
            running = true;
            Socket ls0 = new Socket("127.0.0.1", lanPort);
            ls0.setTcpNoDelay(true);
            final InputStream li0 = ls0.getInputStream();
            OutputStream lo0 = ls0.getOutputStream();
            lo0.write(fb, 0, fl);
            lo0.flush();

            // L2R: 读本地 MC → 写 7000（持久连接）
            final InputStream fli0 = li0;
            new Thread(() -> { try { pipe(fli0, ctrlOut); } catch (Exception ignored) {} }, "L2R").start();

            // R2L: 读 7000 → 写本地 MC
            Socket[] persist = {ls0};
            new Thread(() -> {
                byte[] buf = new byte[4096];
                while (running) {
                    int n;
                    try { n = ctrlIn.read(buf); if (n == -1) break; }
                    catch (Exception e) { continue; } // 任意IO异常都不关隧道
                    for (int retry = 0; retry < 3; retry++) {
                        try {
                            persist[0].getOutputStream().write(buf, 0, n);
                            persist[0].getOutputStream().flush();
                            break;
                        } catch (Exception e) {
                            try { Thread.sleep(500); } catch (InterruptedException ex) { break; }
                            try {
                                persist[0].close();
                                Socket ns = new Socket("127.0.0.1", lanPort);
                                ns.setTcpNoDelay(true);
                                persist[0] = ns;
                                final InputStream nli = ns.getInputStream();
                                new Thread(() -> { try { pipe(nli, ctrlOut); } catch (Exception ignored) {} }, "L2R").start();
                            } catch (Exception ignored) {}
                        }
                    }
                }
                stop();
            }, "R2L").start();

            RedstoneOnline.LOGGER.info("[RedstoneOnline] Tunnel {}, localPort={}", listenPort, lanPort);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Start failed: {}", e.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { stop(); }, "RedstoneOnline-Shutdown"));
    }

    private static int openLan(Object mcServer, boolean cheats, int port) {
        try {
            Object commands = mcServer.getClass().getMethod("getCommands").invoke(mcServer);
            CommandDispatcher<CommandSourceStack> d = (CommandDispatcher<CommandSourceStack>)
                commands.getClass().getMethod("getDispatcher").invoke(commands);
            CommandSourceStack src = (CommandSourceStack)
                mcServer.getClass().getMethod("createCommandSourceStack").invoke(mcServer);
            String cheatsStr = cheats ? "true" : "false";
            d.execute(new StringReader("publish " + cheatsStr + " survival " + port), src);
            // 获取端口
            try {
                Method getPort = mcServer.getClass().getMethod("getPort");
                return (int) getPort.invoke(mcServer);
            } catch (Exception ignored) {}
            return port;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("already")) {
                RedstoneOnline.LOGGER.info("[RedstoneOnline] 局域网已在运行中，请关闭或重启游戏");
            } else {
                RedstoneOnline.LOGGER.info("[RedstoneOnline] publish命令失败: {}", msg);
            }
            return -1;
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] openLan failed: {}", e.toString());
            return -1;
        }
    }

    private static void showAddress() {
        String addr = getAddress();
        if (addr == null) return;
        copyToClipboard(addr);
        net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (net.minecraft.client.Minecraft.getInstance().player != null) {
                net.minecraft.client.Minecraft.getInstance().player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§c[红石联机]§r联机地址: §a" + addr + " §e已复制到剪切板"),
                    false);
            }
        });
    }

    public static long getGlfwWindow() {
        Object w = net.minecraft.client.Minecraft.getInstance().getWindow();
        for (String n : new String[]{"getWindow","getGlfwWindow","getHandle","window"}) {
            try {
                Method m = w.getClass().getMethod(n);
                Class<?> r = m.getReturnType();
                if (r == long.class) return (long) m.invoke(w);
                if (r == Long.class) { Long v = (Long) m.invoke(w); if (v != null) return v; }
            } catch (Exception ignored) {}
        }
        for (String n : new String[]{"window","handle","glfwWindow"}) {
            try { java.lang.reflect.Field f = w.getClass().getField(n);
                if (f.getType() == long.class) return f.getLong(w);
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public static void copyToClipboard(String text) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;
            if (os.contains("win")) cmd = new String[]{"cmd","/c","echo " + text + " | clip"};
            else if (os.contains("mac")) cmd = new String[]{"bash","-c","echo -n '" + text + "' | pbcopy"};
            else cmd = new String[]{"bash","-c","echo -n '" + text + "' | xclip -selection clipboard 2>/dev/null || echo -n '" + text + "' | xsel -ib"};
            Runtime.getRuntime().exec(cmd);
        } catch (Exception ignored) {}
    }

    public static void stop() {
        if (stopped) return;
        stopped = true; running = false;
        try { controlSocket.close(); } catch (Exception ignored) {}
        try {
            HttpResponse<String> r = HTTP_CLIENT.send(HttpRequest.newBuilder()
                .uri(URI.create("http://" + cachedAddress + ":3000/tunnels"))
                .header("Authorization", RedstoneOnline.apikey).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE -> {} {}", r.statusCode(), r.body());
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] DELETE failed: {}", e.getMessage());
        }
    }
}
