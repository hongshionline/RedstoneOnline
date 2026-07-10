package site.hongshi.redstoneonline;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import site.hongshi.redstoneonline.frp.Frp;

public class RedstoneScreen extends Screen {
    private int serverIdx = 0;
    private boolean cheats = true;
    private Button serverBtn, cheatsBtn, actionBtn;
    private EditBox playerInput;

    public RedstoneScreen() {
        super(Component.literal("§c红石联机"));
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 35;

        serverBtn = Button.builder(serverText(), b -> {
            serverIdx = (serverIdx + 1) % RedstoneOnline.servers.size();
            serverBtn.setMessage(serverText());
        }).bounds(cx - 90, y, 180, 20).build();
        addRenderableWidget(serverBtn);

        cheatsBtn = Button.builder(cheatsText(), b -> {
            cheats = !cheats;
            cheatsBtn.setMessage(cheatsText());
        }).bounds(cx - 90, y + 25, 180, 20).build();
        addRenderableWidget(cheatsBtn);

        Button labelBtn = Button.builder(Component.literal("§7最大人数"), b -> {}).bounds(cx - 90, y + 50, 60, 20).build();
        labelBtn.active = false;
        addRenderableWidget(labelBtn);

        playerInput = new EditBox(font, cx - 25, y + 50, 115, 20, Component.literal(""));
        playerInput.setValue("1");
        playerInput.setFilter(s -> s.matches("\\d*"));
        playerInput.setMaxLength(2);
        addRenderableWidget(playerInput);

        actionBtn = Button.builder(actionText(), b -> toggle())
            .bounds(cx - 90, y + 80, 180, 20).build();
        addRenderableWidget(actionBtn);

        addRenderableWidget(Button.builder(
            Component.literal("§7关闭"), b -> onClose()
        ).bounds(cx - 90, y + 105, 180, 20).build());
    }

    private Component serverText() {
        Server s = RedstoneOnline.servers.get(serverIdx);
        return Component.literal("§7服务器: §a" + s.name);
    }

    private Component cheatsText() {
        return Component.literal("§7作弊: " + (cheats ? "§a开启" : "§c关闭"));
    }

    private Component actionText() {
        return Frp.getAddress() != null
            ? Component.literal("§c关闭隧道")
            : Component.literal("§a开启隧道");
    }

    private void toggle() {
        if (Frp.getAddress() != null) {
            Frp.stop();
            actionBtn.setMessage(Component.literal("§a开启隧道"));
            return;
        }
        int mp;
        try { mp = Math.max(1, Integer.parseInt(playerInput.getValue())); }
        catch (Exception e) { mp = 1; }
        final int maxPlayers = mp;

        Server sv = RedstoneOnline.servers.get(serverIdx);
        RedstoneOnline.chooseServer = serverIdx;
        actionBtn.setMessage(Component.literal("§e等待..."));
        actionBtn.active = false;

        new Thread(() -> {
            Frp.start(sv.address, maxPlayers,
                Minecraft.getInstance().getSingleplayerServer(), cheats, 25565);
            Minecraft.getInstance().execute(() -> {
                actionBtn.active = true;
                String a = Frp.getAddress();
                actionBtn.setMessage(a != null
                    ? Component.literal("§c关闭隧道")
                    : Component.literal("§a开启隧道"));
                if (a != null) {
                    Frp.copyToClipboard(a);
                    Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§a✔ 隧道已开启 §7" + a), true);
                    Minecraft.getInstance().player.displayClientMessage(
                        Component.literal("§c[红石联机]§r地址: §a" + a + " §e已复制到剪切板"), false);
                }
            });
        }).start();
    }
}
