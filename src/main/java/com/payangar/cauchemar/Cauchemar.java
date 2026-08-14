package com.payangar.cauchemar;

import com.mojang.logging.LogUtils;
import com.payangar.cauchemar.registry.ModBlocks;
import com.payangar.cauchemar.registry.ModEntities;
import com.payangar.cauchemar.registry.ModItems;
import com.payangar.cauchemar.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import org.slf4j.Logger;

/**
 * Main entry point of the mod. The value passed to {@link Mod} must match the {@code modId}
 * declared in {@code META-INF/neoforge.mods.toml}.
 */
@Mod(Cauchemar.MOD_ID)
public class Cauchemar {

    public static final String MOD_ID = "cauchemar";
    public static final Logger LOGGER = LogUtils.getLogger();

    // FML injects the mod event bus and the mod container automatically.
    public Cauchemar(IEventBus modEventBus, ModContainer modContainer) {
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        ModSounds.register(modEventBus);

        modEventBus.addListener(this::commonSetup);

        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        LOGGER.info("Cauchemar common setup complete");
    }
}
