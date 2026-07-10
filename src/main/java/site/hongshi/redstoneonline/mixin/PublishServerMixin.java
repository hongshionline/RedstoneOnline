package site.hongshi.redstoneonline.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import site.hongshi.redstoneonline.RedstoneOnline;
import site.hongshi.redstoneonline.RedstoneScreen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

@Mixin(MinecraftServer.class)
public class PublishServerMixin {
    @Unique
    private MinecraftServer redstoneOnline$server;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void onInit(CallbackInfo ci) {
        this.redstoneOnline$server = (MinecraftServer) (Object) this;

        disableOnlineMode();

        new Thread(() -> {
            for (int i = 0; i < 50; i++) {
                try {
                    if (redstoneOnline$server.getCommands() != null) {
                        CommandDispatcher<CommandSourceStack> d =
                            redstoneOnline$server.getCommands().getDispatcher();
                        d.register(Commands.literal("rs")
                            .executes(ctx -> {
                                Minecraft.getInstance().execute(() ->
                                    Minecraft.getInstance().setScreen(new RedstoneScreen()));
                                return 1;
                            })
                        );
                        try { Thread.sleep(2000); } catch (Exception ignored) {}
                        Minecraft.getInstance().execute(() -> {
                            if (Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.displayClientMessage(
                                    Component.literal("§6[§c红石联机§6] §7输入 §e/rs§7 打开控制面板"),
                                    true);
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {}
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        }, "RedstoneOnline-Reg").start();
    }

    @Unique
    private void disableOnlineMode() {
        for (String name : new String[]{"setUsesAuthentication", "setOnlineMode", "setAuthMode"}) {
            try {
                Method m = MinecraftServer.class.getDeclaredMethod(name, boolean.class);
                m.invoke(redstoneOnline$server, false);
                return;
            } catch (Exception ignored) {}
        }
        for (String name : new String[]{"usesAuthentication", "onlineMode"}) {
            try {
                Field f = MinecraftServer.class.getDeclaredField(name);
                f.setAccessible(true); f.set(redstoneOnline$server, false);
                return;
            } catch (Exception ignored) {}
        }
    }
}
