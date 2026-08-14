package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's particle types. A type is only a name; which textures it plays and
 * how it behaves are declared in {@code assets/cauchemar/particles/<name>.json} and in the client
 * side provider.
 */
public final class ModParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Cauchemar.MOD_ID);

    /** A small spider skittering on the ground. Decorative for now, spawned by hand for testing. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SPIDER =
            PARTICLE_TYPES.register("spider", () -> new SimpleParticleType(false));

    public static void register(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }

    private ModParticles() {
    }
}
