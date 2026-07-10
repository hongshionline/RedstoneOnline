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

    public static void start(String serverAddress, int maxPlayers, Object mcServer) {
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
            }

            controlSocket.setSoTimeout(0);
            byte[] fb = new byte[4096];
            int fl = ctrlIn.read(fb);
            if (fl <= 0) { stop(); return; }

            int port = openLan(mcServer);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] LAN opened on port {}", port);

            controlSocket.setSoTimeout(10000);
            running = true;
            Socket ls0 = new Socket("127.0.0.1", port);
            ls0.setTcpNoDelay(true);
            ls0.getOutputStream().write(fb, 0, fl);
            ls0.getOutputStream().flush();

            InputStream li = ls0.getInputStream();
            new Thread(() -> { try { pipe(li, ctrlOut); } catch (Exception ignored) {} }, "L2R").start();

            Socket[] currentLs = {ls0};
            new Thread(() -> {
                try {
                    byte[] buf = new byte[4096];
                    while (running) {
                        int n;
                        try { n = ctrlIn.read(buf); if (n == -1) break; }
                        catch (java.net.SocketTimeoutException e) { continue; }
                        currentLs[0].close();
                        currentLs[0] = new Socket("127.0.0.1", port);
                        currentLs[0].setTcpNoDelay(true);
                        currentLs[0].getOutputStream().write(buf, 0, n);
                        currentLs[0].getOutputStream().flush();
                    }
                } catch (Exception ignored) {}
                stop();
            }, "R2L").start();

            RedstoneOnline.LOGGER.info("[RedstoneOnline] Tunnel {}, localPort={}", listenPort, port);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Start failed: {}", e.toString());
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { stop(); }, "RedstoneOnline-Shutdown"));
    }

    private static int openLan(Object mcServer) {
        try {
            Object commands = mcServer.getClass().getMethod("getCommands").invoke(mcServer);
            CommandDispatcher<CommandSourceStack> d = (CommandDispatcher<CommandSourceStack>)
                commands.getClass().getMethod("getDispatcher").invoke(commands);
            CommandSourceStack src = (CommandSourceStack)
                mcServer.getClass().getMethod("createCommandSourceStack").invoke(mcServer);
            d.execute(new StringReader("publish 25565"), src);

            try {
                Method getPort = mcServer.getClass().getMethod("getPort");
                return (int) getPort.invoke(mcServer);
            } catch (Exception ignored) {}
            return 25565;
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] openLan failed: {}", e.toString());
            return 25565;
        }
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
