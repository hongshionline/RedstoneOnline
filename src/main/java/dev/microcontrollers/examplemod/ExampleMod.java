package dev.microcontrollers.examplemod;

import dev.kikugie.fletching_table.annotation.fabric.Entrypoint;
import dev.microcontrollers.examplemod.config.ExampleConfig;
//? if fabric
import net.fabricmc.api.ModInitializer;
//? if neoforge {
/*import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
*///?}


//? if neoforge {
/*@Mod(value = "@MODID@", dist = Dist.CLIENT)
*///?} else {
@Entrypoint
//?}
public class ExampleMod /*? if fabric {*/ implements ModInitializer /*?}*/ {
    //? if fabric {
    @Override
    public void onInitialize() {
        ExampleConfig.CONFIG.load();
    }
    //?}

    //? if neoforge {
    /*public ExampleMod() {
        ExampleConfig.CONFIG.load();
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (client, parent) -> ExampleConfig.configScreen(parent));
    }
	*///?}
}
