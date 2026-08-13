package com.payangar.cauchemar.movement.climber;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * A no-op (pass-through) move control for the climber.
 *
 * <p>The per-tick steering, turning the planned route into heading + speed + corner jumps, now runs in
 * the common {@link com.payangar.cauchemar.movement.Steering} layer during the navigation phase, which
 * applies the result through the locomotion profile ({@code applyMovement}). The vanilla {@link
 * MoveControl#tick()} runs unconditionally right after navigation and would overwrite that output, so
 * it is neutralised here, as the movement API design requires.
 */
public class ClimberMoveController<T extends Mob & IClimberEntity> extends MoveControl {

    public ClimberMoveController(T entity) {
        super(entity);
    }

    @Override
    public void tick() {
        // No-op: Steering drives movement in the navigation phase.
    }
}
