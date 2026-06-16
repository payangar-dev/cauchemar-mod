package com.payangar.cauchemar;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entry point. This class is not loaded on dedicated servers, so client-only
 * code can be referenced safely here.
 */
@Mod(value = Cauchemar.MOD_ID, dist = Dist.CLIENT)
public class CauchemarClient {

    public CauchemarClient(ModContainer container) {
        // Generates an in-game config screen, reachable from Mods > Cauchemar > Config.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
