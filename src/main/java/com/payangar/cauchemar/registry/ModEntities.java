package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's entity types. Declare entities as static fields on
 * {@link #ENTITY_TYPES}, then call {@link #register(IEventBus)} from the main mod constructor.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Cauchemar.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<MotherSpiderEntity>> MOTHER_SPIDER =
            ENTITY_TYPES.register("mother_spider", () -> EntityType.Builder
                    .<MotherSpiderEntity>of(MotherSpiderEntity::new, MobCategory.MONSTER)
                    // Small collision box around the body only: the long legs overhang it (immersion
                    // comes from rendering, not collision). Kept under 1.0 on both axes so the
                    // pathfinding footprint is floor(size+1) = 1x1x1, the cleanest case for the
                    // surface-aware climbing nav (far fewer stuck/erratic paths than a 2-wide box).
                    // The multipart hit boxes and the foot IK are positioned off the body, not this
                    // box, so shrinking it does not move them; the visual model is unchanged.
                    .sized(0.9f, 0.8f)
                    .clientTrackingRange(64)
                    .build(ResourceLocation.fromNamespaceAndPath(Cauchemar.MOD_ID, "mother_spider").toString()));

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::onAttributeCreation);
    }

    private static void onAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(MOTHER_SPIDER.get(), MotherSpiderEntity.createAttributes().build());
    }

    private ModEntities() {
    }
}
