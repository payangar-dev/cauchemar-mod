package com.payangar.cauchemar.movement.climber;

import com.payangar.cauchemar.movement.MoveIntent;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * Contract a climbing entity exposes to the renderer (and later to the movement/pathfinding code).
 *
 * <p>Carries what the renderer needs to tilt the model onto the climbed surface plus the movement
 * accessors the climber controllers read. Extends {@link IAdvancedPathFindingEntity} so the
 * surface-aware navigation (the climbing path finder/processor) can drive this entity.
 */
public interface IClimberEntity extends IAdvancedPathFindingEntity {

    /** Interpolated world-space attachment offset on the given axis (model render translation). */
    float getAttachmentOffset(Direction.Axis axis, float partialTicks);

    /** Small offset pushing the model out along the surface normal so it does not z-fight the floor. */
    float getVerticalOffset(float partialTicks);

    /** The current (server-tick) surface orientation. */
    Orientation getOrientation();

    /** Builds an interpolated orientation for smooth rendering between ticks. */
    Orientation calculateOrientation(float partialTicks);

    void setRenderOrientation(Orientation orientation);

    @Nullable
    Orientation getRenderOrientation();

    // --- Movement (used by the climber controllers) ---

    float getMovementSpeed();

    /** The surface the spider is sticking to: the chosen face + a weighted "down" toward it. */
    Pair<Direction, Vec3> getGroundDirection();

    void setJumpDirection(@Nullable Vec3 dir);

    /** Applies a steering {@link MoveIntent} for this tick (the mob delegates to its locomotion profile). */
    void applyMovement(MoveIntent intent);

    /**
     * Whether the entity is currently attached to (or just barely off) a climbing surface. Stays true
     * through the brief detachments that happen while rounding a step/corner, and only goes false on a
     * genuine multi-tick free fall. Used by the navigator to keep path following alive mid-transition.
     */
    boolean isAttachedToSurface();
}
