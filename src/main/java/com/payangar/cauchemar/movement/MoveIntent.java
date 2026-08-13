package com.payangar.cauchemar.movement;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The per-tick movement intent produced by the steering layer and consumed by a locomotion profile's
 * {@code applyMovement} (today {@link com.payangar.cauchemar.movement.climber.ClimberLocomotion}).
 *
 * <p>Intentionally minimal: it carries just what the surface-relative execution needs, keeping the
 * contract a narrow waist between steering and execution. It is expected to grow (e.g. a frame-local
 * direction, a face-transition hint) as the steering layer is generalised across profiles.
 *
 * @param inPlaneMove the desired move projected onto the current locomotion plane, in world space and
 *                    NOT normalised. Its length carries how far/strongly to move (the profile uses it
 *                    to decide whether the heading is reliable enough to re-aim). On the ground this is
 *                    simply the horizontal move.
 * @param speed       the base movement speed (an already-resolved {@code MOVEMENT_SPEED} multiple).
 * @param jumpDir     optional directional jump (rounding a corner onto a perpendicular face); null on
 *                    the ground profile.
 */
public record MoveIntent(Vec3 inPlaneMove, double speed, @Nullable Vec3 jumpDir) {
}
