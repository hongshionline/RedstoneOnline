package site.hongshi.redstoneonline.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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

        // 关闭正版验证（兼容各版本方法名和字段名）
        disableOnlineMode();

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
                // /rs server list (等级0)
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
                    // /rs server <number> (等级3+)
                    .then(Commands.argument("num", IntegerArgumentType.integer(1, RedstoneOnline.servers.size()))
                        .executes(ctx -> {
                            if (!checkPerm(ctx.getSource(), 3)) return 0;
                            int idx = IntegerArgumentType.getInteger(ctx, "num") - 1;
                            RedstoneOnline.chooseServer = idx;
                            ctx.getSource().sendSuccess(
                                () -> Component.literal("§a[红石联机]§r已选择服务器: §e"
                                    + RedstoneOnline.servers.get(idx).name), false);
                            return 1;
                        })))
                // /rs open [maxPlayers] (等级4)
                .then(Commands.literal("open")
                    .requires(src -> getPermLevel(src) >= 4)
                    .then(Commands.argument("maxPlayers", IntegerArgumentType.integer(1, 99))
                        .executes(ctx -> execOpen(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "maxPlayers"))))
                    .executes(ctx -> execOpen(ctx.getSource(), 1)))
                // /rs close (等级4)
                .then(Commands.literal("close")
                    .requires(src -> getPermLevel(src) >= 4)
                    .executes(ctx -> {
                        Frp.stop();
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[红石联机]§r隧道已关闭"), false);
                        return 1;
                    }))
                // /rs op <target> <level> (等级4)
                .then(Commands.literal("op")
                    .requires(src -> getPermLevel(src) >= 4)
                    .then(Commands.argument("target", EntityArgument.player())
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 4))
                            .executes(ctx -> {
                                ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
                                int lv = IntegerArgumentType.getInteger(ctx, "level");
                                RedstoneOnline.permLevels.put(target.getUUID(), lv);
                                ctx.getSource().sendSuccess(() -> Component.literal(
                                    "§a[红石联机]§r已将 §e" + target.getName().getString()
                                    + "§r 的权限设为 §b" + lv), false);
                                return 1;
                            }))))
                // /rs debug (等级5, 不显示在帮助列表)
                .then(Commands.literal("debug")
                    .executes(ctx -> {
                        if (!checkPerm(ctx.getSource(), 5)) return 0;
                        int myLevel = getPermLevel(ctx.getSource());
                        int vanLevel = getVanillaLevel(ctx.getSource());
                        StringBuilder sb = new StringBuilder("§6===== §c红石联机 §6调试信息 =====\n");
                        sb.append(" §b红石等级: §f").append(myLevel).append("\n");
                        sb.append(" §b原版等级: §f").append(vanLevel).append("\n");
                        sb.append(" §bAPI Key: §f").append(RedstoneOnline.apikey).append("\n");
                        sb.append(" §b已选服务器: §f").append(RedstoneOnline.servers.get(RedstoneOnline.chooseServer).name).append("\n");
                        sb.append(" §b隧道状态: §f").append(Frp.getAddress() != null ? "已开启" : "未开启");
                        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                        return 1;
                    }))
                // /rs (无参数) 显示帮助
                .executes(ctx -> {
                    int myLevel = getPermLevel(ctx.getSource());
                    StringBuilder sb = new StringBuilder("§6===== §c红石联机 §6=====\n");
                    sb.append(" §b/rs server list§r  - 列出服务器 §7(等级0)\n");
                    if (myLevel >= 3) sb.append(" §b/rs server <n>§r   - 选择服务器 §7(等级3)\n");
                    if (myLevel >= 4) {
                        sb.append(" §b/rs open [人数]§r - 开启内网穿透 §7(等级4)\n");
                        sb.append(" §b/rs close§r         - 关闭内网穿透 §7(等级4)\n");
                        sb.append(" §b/rs op <玩家> <等级>§r - 设置权限 §7(等级4)\n");
                    }
                    sb.append(" §7⚠ 仅监听 25565 端口 | 你的等级: " + myLevel);
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                })
            );
        } catch (Exception ignored) {}
    }

    /** 检查玩家是否有指定等级权限 */
    @Unique
    private static boolean checkPerm(CommandSourceStack src, int required) {
        int rl = getPermLevel(src);
        if (rl < required) {
            src.sendFailure(Component.literal("§c权限不足 (需要等级 " + required + ")"));
            return false;
        }
        return true;
    }

    /** 获取原版权限等级 */
    @Unique
    private static int getVanillaLevel(CommandSourceStack src) {
        try {
            ServerPlayer player = src.getPlayer();
            if (player != null) {
                Method m = ServerPlayer.class.getMethod("getPermissionLevel");
                return (int) m.invoke(player);
            }
        } catch (Exception ignored) {}
        return 4;
    }

    /** 执行 /rs open 逻辑 */
    @Unique
    private int execOpen(CommandSourceStack src, int maxPlayers) {
        Server server = RedstoneOnline.servers.get(RedstoneOnline.chooseServer);
        src.sendSuccess(() ->
            Component.literal("§a[红石联机]§r正在使用 §e" + server.name + "§r 等待玩家连入..."), false);

        new Thread(() -> {
            Frp.start(server.address, maxPlayers, redstoneOnline$server);

            String addr = Frp.getAddress();
            if (addr != null) {
                Frp.copyToClipboard(addr);
                Minecraft.getInstance().execute(() -> {
                    if (Minecraft.getInstance().player != null) {
                        Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§a✔ 隧道已开启 §7| §c" + addr),
                            true);
                        Minecraft.getInstance().player.displayClientMessage(
                            Component.literal("§c[红石联机]§r地址已复制到剪切板: §a" + addr),
                            false);
                    }
                });
            }
        }, "RedstoneOnline-Open").start();

        return 1;
    }

    /** 获取玩家权限等级 */
    @Unique
    private static int getPermLevel(CommandSourceStack src) {
        try {
            ServerPlayer player = src.getPlayer();
            if (player != null) {
                // 如果通过 /rs op 设置过，用设置的值
                if (RedstoneOnline.permLevels.containsKey(player.getUUID())) {
                    return RedstoneOnline.permLevels.get(player.getUUID());
                }
                // 默认：原版 OP 或房主（单人中）均为 4 级
                if (isOp(player)) return 4;
                return 0;
            }
            // 命令方块默认 3 级
            return 3;
        } catch (Exception e) {
            return 4;
        }
    }

    /** 检查玩家是否为 OP（兼容各版本） */
    @Unique
    private static boolean isOp(ServerPlayer player) {
        try {
            Method m = ServerPlayer.class.getMethod("hasPermissions", int.class);
            return (boolean) m.invoke(player, 4);
        } catch (Exception ignored) {}
        try {
            Method m = ServerPlayer.class.getMethod("canUseGameMasterBlocks");
            return (boolean) m.invoke(player);
        } catch (Exception ignored) {}
        try {
            // 通过 PlayerList 判断 OP
            Method getServer = Entity.class.getMethod("getServer");
            Object ms = getServer.invoke(player);
            Method getPlayerList = ms.getClass().getMethod("getPlayerList");
            Object pl = getPlayerList.invoke(ms);
            return (boolean) pl.getClass().getMethod("isOp", com.mojang.authlib.GameProfile.class)
                .invoke(pl, player.getGameProfile());
        } catch (Exception ignored) {}
        return false;
    }

    /** 关闭正版验证，尝试方法名、字段名、延迟重试 */
    @Unique
    private void disableOnlineMode() {
        // 立即尝试方法
        for (String name : new String[]{"setUsesAuthentication", "setOnlineMode", "setAuthMode"}) {
            try {
                Method m = MinecraftServer.class.getDeclaredMethod(name, boolean.class);
                m.invoke(redstoneOnline$server, false);
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Online mode disabled via {}", name);
                return;
            } catch (Exception ignored) {}
        }
        // 直接设字段
        for (String name : new String[]{"usesAuthentication", "onlineMode"}) {
            try {
                java.lang.reflect.Field f = MinecraftServer.class.getDeclaredField(name);
                f.setAccessible(true);
                f.set(redstoneOnline$server, false);
                RedstoneOnline.LOGGER.info("[RedstoneOnline] Online mode disabled via field {}", name);
                return;
            } catch (Exception ignored) {}
        }
        // 延迟重试（等服务器初始化完成）
        new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
            for (String name : new String[]{"setUsesAuthentication", "setOnlineMode", "setAuthMode"}) {
                try {
                    Method m = MinecraftServer.class.getDeclaredMethod(name, boolean.class);
                    m.invoke(redstoneOnline$server, false);
                    RedstoneOnline.LOGGER.info("[RedstoneOnline] Online mode disabled (delayed) via {}", name);
                    return;
                } catch (Exception ignored) {}
            }
            for (String name : new String[]{"usesAuthentication", "onlineMode"}) {
                try {
                    java.lang.reflect.Field f = MinecraftServer.class.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(redstoneOnline$server, false);
                    RedstoneOnline.LOGGER.info("[RedstoneOnline] Online mode disabled (delayed) via field {}", name);
                    return;
                } catch (Exception ignored) {}
            }
        }, "RedstoneOnline-OnlineMode").start();
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
        String os = System.getProperty("os.name").toLowerCase();
        try {
            String[] cmd;
            if (os.contains("win")) {
                cmd = new String[]{"cmd", "/c", "netstat -ano | findstr \":" + port + "\""};
            } else {
                cmd = new String[]{"sh", "-c", "ss -tlnp sport = :" + port + " 2>/dev/null || lsof -i :" + port + " 2>/dev/null"};
            }
            Process p = Runtime.getRuntime().exec(cmd);
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (os.contains("win")) {
                    if (line.contains("LISTENING")) {
                        String[] parts = line.trim().split("\\s+");
                        if (Long.parseLong(parts[parts.length - 1]) == myPid) return true;
                    }
                } else {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("pid=(\\d+)").matcher(line);
                    if (m.find() && Long.parseLong(m.group(1)) == myPid) return true;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        try { if (Long.parseLong(parts[1]) == myPid) return true; } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
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
