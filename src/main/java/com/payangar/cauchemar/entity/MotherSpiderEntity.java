package com.payangar.cauchemar.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * The Mother Spider. At this stage the entity only exists and renders: it plays its idle animation
 * and does nothing else. It has no goals, no custom navigation and no combat behaviour on purpose.
 * Movement and AI are designed and added later, one feature at a time.
 */
public class MotherSpiderEntity extends Monster implements GeoEntity {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.idle");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public MotherSpiderEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    /**
     * Provisional attributes: only what a Monster needs to exist. They will be revisited when
     * movement and combat are designed.
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.FOLLOW_RANGE, 24.0);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 5, state -> state.setAndContinue(IDLE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }
}
