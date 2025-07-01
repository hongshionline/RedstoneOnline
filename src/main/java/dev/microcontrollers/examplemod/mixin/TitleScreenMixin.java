package dev.microcontrollers.examplemod.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;

@Mixin(TitleScreen.class)
public class TitleScreenMixin extends Screen  {
    protected TitleScreenMixin(Component component) {
        super(component);
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void exampleMixin(GuiGraphics guiGraphics, int i, int j, float f, CallbackInfo ci) {
        guiGraphics.drawString(this.font, "Example Mod",  this.width / 2, this.height / 2, Color.WHITE.getRGB(), true);

    }
}
