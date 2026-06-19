package com.payangar.cauchemar;

import com.payangar.cauchemar.client.MotherSpiderRenderer;
import com.payangar.cauchemar.registry.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. This class is not loaded on dedicated servers, so client-only
 * code can be referenced safely here.
 */
@Mod(value = Cauchemar.MOD_ID, dist = Dist.CLIENT)
public class CauchemarClient {

    public CauchemarClient(IEventBus modEventBus, ModContainer container) {
        // Generates an in-game config screen, reachable from Mods > Cauchemar > Config.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(this::registerRenderers);
    }

    private void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.MOTHER_SPIDER.get(), MotherSpiderRenderer::new);
    }
}
