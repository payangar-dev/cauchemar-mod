package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's sound events. A sound event is only a name: which files it plays,
 * and how many variants it picks from, is declared in {@code assets/cauchemar/sounds.json}.
 */
public final class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, Cauchemar.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> SPIDER_EGG_BREAK = register("block.spider_egg.break");
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIDER_EGG_PLACE = register("block.spider_egg.place");
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIDER_EGG_STEP = register("block.spider_egg.step");
    public static final DeferredHolder<SoundEvent, SoundEvent> SPIDER_EGG_HIT = register("block.spider_egg.hit");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(Cauchemar.MOD_ID, name)));
    }

    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }

    private ModSounds() {
    }
}
