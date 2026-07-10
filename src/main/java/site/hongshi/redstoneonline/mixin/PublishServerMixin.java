package site.hongshi.redstoneonline.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import site.hongshi.redstoneonline.RedstoneOnline;
import site.hongshi.redstoneonline.Server;
import site.hongshi.redstoneonline.frp.Frp;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@Mixin(MinecraftServer.class)
public class PublishServerMixin {
    @Unique
    private MinecraftServer redstoneOnline$server;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.redstoneOnline$server = (MinecraftServer) (Object) this;

        try {
            Method setAuth = MinecraftServer.class.getDeclaredMethod("setUsesAuthentication", boolean.class);
            setAuth.invoke(redstoneOnline$server, false);
        } catch (Exception ignored) {
        }

        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                try {
                    if (redstoneOnline$server.getCommands() != null) {
                        registerCommands();
                        // 命令注册成功后输出欢迎提示
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    Component.literal("§6[§c红石联机§6] §7输入 §e/rs§7 查看帮助 | §7仅监听 25565 端口"),
                                    true);
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "RedstoneOnline-CommandReg").start();
    }

    @Unique
    private void registerCommands() {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher =
                redstoneOnline$server.getCommands().getDispatcher();

            dispatcher.register(Commands.literal("rs")
                // /rs server list
                .then(Commands.literal("server")
                    .then(Commands.literal("list")
                        .executes(ctx -> {
                            StringBuilder sb = new StringBuilder("§6===== §e服务器列表 §6=====");
                            int i = 1;
                            for (Server s : RedstoneOnline.servers) {
                                sb.append("\n §b").append(i++).append("§r. ").append(s.name)
                                    .append(" §7(").append(s.address).append(")");
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                            return 1;
                        }))
                    // /rs server <number>
                    .then(Commands.argument("num", IntegerArgumentType.integer(1, RedstoneOnline.servers.size()))
                        .executes(ctx -> {
                            int idx = IntegerArgumentType.getInteger(ctx, "num") - 1;
                            RedstoneOnline.chooseServer = idx;
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("§a[红石联机]§r已选择服务器: §e"
                                    + RedstoneOnline.servers.get(idx).name), false);
                            return 1;
                        })))
                // /rs open
                .then(Commands.literal("open")
                    .executes(ctx -> {
                        // 检查局域网是否已开启
                        int port = getLanPort();
                        if (port <= 0) {
                            ctx.getSource().sendFailure(
                                Component.literal("§c未检测到局域网房间 (端口 25565)"));
                            return 0;
                        }

                        Server server = RedstoneOnline.servers.get(RedstoneOnline.chooseServer);
                        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] /rs open: server={}, addr={}, port={}",
                            server.name, server.address, port);
                        ctx.getSource().sendSuccess(() ->
                            Component.literal("§a[红石联机]§r正在使用 §e" + server.name + "§r 创建隧道..."), false);

                        // 在后台线程启动隧道，不阻塞命令
                        new Thread(() -> {
                            Frp.start(server.address, port, port);

                            String addr = Frp.getAddress();
                            if (addr != null) {
                                Frp.copyToClipboard(addr);
                                Minecraft.getInstance().execute(() -> {
                                    if (Minecraft.getInstance().player != null) {
                                        // ActionBar
                                        Minecraft.getInstance().player.displayClientMessage(
                                            Component.literal("§a✔ 隧道已开启 §7| §c" + addr),
                                            true);
                                        // 聊天栏详细
                                        Minecraft.getInstance().player.displayClientMessage(
                                            Component.literal("§c[红石联机]§r地址已复制到剪切板: §a" + addr),
                                            false);
                                    }
                                });
                            }
                        }, "RedstoneOnline-Open").start();

                        return 1;
                    }))
                // /rs close
                .then(Commands.literal("close")
                    .executes(ctx -> {
                        Frp.stop();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[红石联机]§r隧道已关闭"), false);
                        return 1;
                    }))
                // /rs (无参数) 显示帮助
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                        "§6===== §c红石联机 §6=====\n"
                        + " §b/rs server list§r  - 列出服务器\n"
                        + " §b/rs server <n>§r   - 选择服务器\n"
                        + " §b/rs open§r          - 开启内网穿透\n"
                        + " §b/rs close§r         - 关闭内网穿透\n"
                        + " §7⚠ 仅监听 25565 端口"), false);
                    return 1;
                })
            );
        } catch (Exception ignored) {}
    }

    /** 通过反射获取局域网端口，未开启返回 -1 */
    @Unique
    private int getLanPort() {
        try {
            // 先检查 25565 是否被当前 JVM 进程占用
            if (!isOwnPort(25565)) {
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Port 25565 not owned by Minecraft");
                return -1;
            }

            // 方法一：isPublished + getPort
            Method isPublished = findMethod("isPublished", "isLanPublished");
            if (isPublished != null && (boolean) isPublished.invoke(redstoneOnline$server)) {
                Method getPort = findMethod("getPort", "getLanPort");
                if (getPort != null) return (int) getPort.invoke(redstoneOnline$server);
            }

            // 方法二：扫描 int 字段看哪个是 25565
            for (Field f : MinecraftServer.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    if (f.getInt(redstoneOnline$server) == 25565) return 25565;
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 检查端口是否被当前 JVM 进程占用（跨平台） */
    @Unique
    private static boolean isOwnPort(int port) {
        long myPid = ProcessHandle.current().pid();
        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 本进程PID: {}", myPid);
        String os = System.getProperty("os.name").toLowerCase();
        try {
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "netstat -ano | findstr \":" + port + "\""};
            } else {
                cmd = new String[]{"sh", "-c", "ss -tlnp sport = :" + port + " 2>/dev/null || lsof -i :" + port + " 2>/dev/null"};
            }
            RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 执行命令: {}", String.join(" ", cmd));

            Process p = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            boolean found=false;
            while ((line = reader.readLine()) != null) {
                RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] netstat输出: {}", line);
                if (os.contains("win")) {
                    if (line.contains("LISTENING")) {
                        found=true;
                        String[] parts = line.trim().split("\\s+");
                        String pidStr = parts[parts.length - 1];
                        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 解析到PID: {}", pidStr);
                        if (Long.parseLong(pidStr) == myPid) return true;
                    }
                } else {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(line);
                    if (m.find()) {
                        found=true;
                        String foundPid = m.group(1);
                        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 解析到PID: {}", foundPid);
                        if (Long.parseLong(foundPid) == myPid) return true;
                    }
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try {
                            long pid = Long.parseLong(parts[1]);
                            if (pid == myPid) return true;
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            if(!found) RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 未找到端口 {} 的相关记录", port);
        } catch (Exception e) {
            RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] netstat执行异常: {}", e.toString());
        }
        RedstoneOnline.LOGGER.warn("[RedstoneOnline Debug] 端口 {} 不属于本进程", port);
        return false;
    }

    @Unique
    private static Method findMethod(String... names) {
        for (Method m : MinecraftServer.class.getDeclaredMethods()) {
            for (String n : names) {
                if (m.getName().equals(n)) return m;
            }
        }
        return null;
    }
}
