package com.payangar.cauchemar.movement.climber;

import net.minecraft.world.phys.Vec3;

/**
 * The contract a climbing mob exposes to its {@link ClimberLocomotion} profile.
 *
 * <p>The profile lives outside the entity's package, so it cannot reach the entity's {@code
 * protected}/{@code super} behaviour directly. These bridges expose exactly what it needs. A mob that
 * uses the Climber profile implements this alongside {@link IClimberEntity}.
 */
public interface ClimberHost {

    /** Runs the vanilla {@code LivingEntity.travel} (the profile's in-fluid fallback). */
    void climberSuperTravel(Vec3 relative);

    /** Runs the entity's animation-state update ({@code calculateEntityAnimation}). */
    void climberUpdateAnimation();

    /** The entity's jump power ({@code getJumpPower}). */
    float climberJumpPower();

    /** Whether the entity is affected by fluids ({@code isAffectedByFluids}). */
    boolean climberAffectedByFluids();
}
