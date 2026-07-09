package site.hongshi.redstoneonline.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import site.hongshi.redstoneonline.RedstoneOnline;
import site.hongshi.redstoneonline.Server;
import site.hongshi.redstoneonline.frp.Frp;

import java.lang.reflect.Method;

//============================================================
// 无法用 Mixin(@Inject) 直接拦截方法名
//
// Minecraft 运行时类名是混淆的，但开发环境用
// Mojang 映射名。Mixin 在类加载时处理，此时
// 类尚未重映射，所以 @Inject(method = "publishServer") 找不到目标。
//
// 而 <init> 在各映射方案下都是 <init>，所以能匹配。
// 利用这个切入点启动后台线程，等线程执行时类已被 Loom 重映射为
// Mojang 名，此时通过反射就可以用 Mojang 名调用方法。
//============================================================

@Mixin(MinecraftServer.class)
public class PublishServerMixin {
    @Unique
    private MinecraftServer redstoneOnline$server;

    // 注入 MinecraftServer 构造器（在所有映射方案下都能工作）
    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.redstoneOnline$server = (MinecraftServer) (Object) this;

        // 关闭正版验证，使非正版玩家能连接局域网
        try {
            Method setAuth = MinecraftServer.class.getDeclaredMethod("setUsesAuthentication", boolean.class);
            setAuth.invoke(redstoneOnline$server, false);
        } catch (Exception ignored) {
        }

        // 启动后台线程轮询局域网状态
        new Thread(() -> {
            try {
                // 通过反射获取 Mojang 映射名的方法（此时类已重映射）
                Method isPublished = MinecraftServer.class.getDeclaredMethod("isPublished");
                Method isRunning = MinecraftServer.class.getDeclaredMethod("isRunning");
                Method getPort = MinecraftServer.class.getDeclaredMethod("getPort");

                while (true) {
                    // 检测是否开启局域网房间
                    if ((boolean) isPublished.invoke(redstoneOnline$server)) {
                        int port = (int) getPort.invoke(redstoneOnline$server);
                        RedstoneOnline.LOGGER.info("[RedstoneOnline] LAN opened on port: {}", port);

                        // 注册命令 /rs <数字> 用于选择服务器
                        registerCommand();
                        showServerList();

                        // 等待玩家用 /rs 命令选择服务器，30秒超时
                        int selected = RedstoneOnline.waitForCommand(30);
                        if (selected < 0) {
                            selected = selectLowestLatency();
                        }
                        RedstoneOnline.chooseServer = selected;
                        Server server = RedstoneOnline.servers.get(selected);

                        // 输出选择结果到聊天栏
                        final int idx = selected;
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    Component.literal("§a[红石联机]§r已选择服务器: §e" + RedstoneOnline.servers.get(idx).name),
                                    false
                                );
                            }
                        });

                        // 启动内网穿透
                        Frp.start(server.address, port, port);

                        // 输出隧道地址到聊天栏并复制到剪切板
                        String tunnelAddr = Frp.getAddress();
                        if (tunnelAddr != null) {
                            Minecraft.getInstance().execute(() -> {
                                try {
                                    long window = Frp.getGlfwWindow();
                                    if (window != 0) {
                                        GLFW.glfwSetClipboardString(window, tunnelAddr);
                                    }
                                } catch (Exception ignored) {}
                                if (Minecraft.getInstance().player != null) {
                                    Minecraft.getInstance().player.displayClientMessage(
                                        Component.literal("§c[红石联机]§r房间已开启，地址:§a" + tunnelAddr + "§r §e已复制到剪切板"),
                                        false
                                    );
                                }
                            });
                        }

                        // 等待服务器停止（回到主菜单或游戏关闭）
                        while ((boolean) isRunning.invoke(redstoneOnline$server)) {
                            Thread.sleep(500);
                        }
                        // 服务器已停止，关闭隧道
                        Frp.stop();
                        RedstoneOnline.LOGGER.info("[RedstoneOnline] Server stopped, tunnel closed");
                        return;
                    }
                    Thread.sleep(500);
                }
            } catch (Exception e) {
                RedstoneOnline.LOGGER.error("[RedstoneOnline] Error in LAN thread: {}", e.toString());
            }
        }, "RedstoneOnline-LAN").start();
    }

    // 注册 /rs <数字> 命令，用于选择服务器
    @Unique
    private void registerCommand() {
        try {
            CommandDispatcher<CommandSourceStack> dispatcher = redstoneOnline$server.getCommands().getDispatcher();
            dispatcher.register(Commands.literal("rs")
                // /rs <数字> 选择服务器
                .then(Commands.argument("num", IntegerArgumentType.integer(1, RedstoneOnline.servers.size()))
                    .executes(ctx -> {
                        int idx = IntegerArgumentType.getInteger(ctx, "num") - 1;
                        RedstoneOnline.setCommandResult(idx);
                        ctx.getSource().sendSuccess(
                            () -> Component.literal("§a[红石联机]§r已选择服务器: §e" + RedstoneOnline.servers.get(idx).name),
                            false
                        );
                        return 1;
                    })
                )
                // /rs 列出服务器列表
                .executes(ctx -> {
                    StringBuilder sb = new StringBuilder("§6===== §e服务器列表 §6=====");
                    int i = 1;
                    for (Server s : RedstoneOnline.servers) {
                        sb.append("\n §b").append(i++).append("§r. ").append(s.name).append(" §7(").append(s.address).append(")");
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
                    return 1;
                })
            );
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Command /rs registered");
        } catch (Exception e) {
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Command register failed: {}", e.toString());
        }
    }

    // 在聊天栏显示服务器列表
    @Unique
    private void showServerList() {
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player == null) return;
            StringBuilder sb = new StringBuilder("§6===== §e服务器列表 §6=====");
            int i = 1;
            for (Server s : RedstoneOnline.servers) {
                sb.append("\n §b").append(i++).append("§r. ").append(s.name).append(" §7(").append(s.address).append(")");
            }
            sb.append("\n§a请输入 §e/rs <数字> §a选择服务器，30秒超时自动选延迟最低的");
            Minecraft.getInstance().player.displayClientMessage(Component.literal(sb.toString()), false);
        });
    }

    // 通过 TCP 连接延迟选择延迟最低的服务器
    @Unique
    private int selectLowestLatency() {
        int best = 0;
        long bestLatency = Long.MAX_VALUE;
        for (int i = 0; i < RedstoneOnline.servers.size(); i++) {
            long latency = pingServer(RedstoneOnline.servers.get(i).address, 7000);
            RedstoneOnline.LOGGER.info("[RedstoneOnline] Ping {}: {}ms", RedstoneOnline.servers.get(i).name, latency);
            if (latency >= 0 && latency < bestLatency) {
                bestLatency = latency;
                best = i;
            }
        }
        RedstoneOnline.LOGGER.info("[RedstoneOnline] Selected server: {} ({}ms)", RedstoneOnline.servers.get(best).name, bestLatency);
        return best;
    }

    // TCP 连接延迟测试（连接 7000 端口测响应时间）
    @Unique
    private long pingServer(String address, int port) {
        long start = System.currentTimeMillis();
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(address, port), 3000);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }
}
