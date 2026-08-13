package com.payangar.cauchemar.movement.climber;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * Look control that aims the head in surface-local space, so a climbing spider looks at targets
 * correctly while on walls/ceilings. Ported from Nyf's Spiders.
 */
public class ClimberLookController<T extends Mob & IClimberEntity> extends LookControl {
    protected final IClimberEntity climber;

    public ClimberLookController(T entity) {
        super(entity);
        this.climber = entity;
    }

    @Override
    protected @NotNull Optional<Float> getXRotD() {
        Vec3 dir = new Vec3(this.wantedX - this.mob.getX(), this.wantedY - this.mob.getEyeY(), this.wantedZ - this.mob.getZ());
        return Optional.of(this.climber.getOrientation().getLocalRotation(dir).getRight());
    }

    @Override
    protected @NotNull Optional<Float> getYRotD() {
        Vec3 dir = new Vec3(this.wantedX - this.mob.getX(), this.wantedY - this.mob.getEyeY(), this.wantedZ - this.mob.getZ());
        return Optional.of(this.climber.getOrientation().getLocalRotation(dir).getLeft());
    }
}
