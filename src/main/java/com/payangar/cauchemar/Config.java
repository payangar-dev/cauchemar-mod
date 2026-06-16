package com.payangar.cauchemar;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Mod configuration. Built with NeoForge's {@link ModConfigSpec} and registered in
 * {@link Cauchemar}. Replace the example value below with the mod's real settings.
 */
public final class Config {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue EXAMPLE_TOGGLE = BUILDER
            .comment("Example toggle")
            .define("exampleToggle", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
