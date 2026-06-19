package com.payangar.cauchemar.client;

import net.minecraft.util.Mth;

/**
 * Analytic two-bone inverse kinematics for one spider leg, solved in the leg's own vertical plane
 * (the {@code leg_*} base bone's fan yaw orients that plane; this only solves the in-plane reach via
 * the {@code _upper} and {@code _lower} bones, which both rotate on Z).
 *
 * <p>Coordinates are the leg-local plane: {@code h} = horizontal reach away from the body (the leg's
 * "out" direction), {@code v} = vertical offset of the foot relative to the shoulder ({@code +} up).
 * Lengths and positions are in model units (16 = 1 block). Output Z rotations are for the LEFT side;
 * the right side mirrors them (negate), matching the geo's mirrored rest rotations.
 *
 * <p>Convention was derived from the rig and checked against the rest pose: rest angles
 * (upperZ {@code -35deg}, lowerZ {@code +90deg}) place the foot at ground level (Y ~= 0), the femur
 * up-and-out and the tibia back down ("knee above body"). See {@link #solve}.
 */
public final class SpiderLegIk {

    /** Upper segment (femur) length, model units: shoulder pivot -> knee pivot. */
    public static final float UPPER_LENGTH = 18.0f;
    /** Lower segment (tibia) length, model units: knee pivot -> foot tip. */
    public static final float LOWER_LENGTH = 26.0f;

    /** Solved local Z rotations (radians) for the upper and lower bones, for the LEFT side. */
    public record LegPose(float upperZ, float lowerZ) {}

    /** A foot position in the leg plane: {@code h} = horizontal reach, {@code v} = vertical (+ up). */
    public record Foot(float h, float v) {}

    private SpiderLegIk() {
    }

    /**
     * Forward kinematics: given the LEFT-side bone Z rotations (radians), returns where the foot ends
     * up in the leg plane. Inverse of {@link #solve}. Used to read where the animation places a foot
     * (so the IK can correct only the contact, keeping the animation's gait and lift).
     */
    public static Foot forward(float upperZ, float lowerZ) {
        float a1 = -upperZ;          // upper segment angle above horizontal (see solve())
        float a2 = a1 - lowerZ;      // lower segment angle above horizontal
        float h = UPPER_LENGTH * Mth.cos(a1) + LOWER_LENGTH * Mth.cos(a2);
        float v = UPPER_LENGTH * Mth.sin(a1) + LOWER_LENGTH * Mth.sin(a2);
        return new Foot(h, v);
    }

    /**
     * Solves the two-bone chain so the foot reaches {@code (h, v)} in the leg plane.
     *
     * @param h horizontal reach from the shoulder (out direction), model units
     * @param v vertical offset from the shoulder ({@code +} up), model units
     * @return left-side {@code upperZ}/{@code lowerZ} in radians (negate for the right side)
     */
    public static LegPose solve(float h, float v) {
        float l1 = UPPER_LENGTH;
        float l2 = LOWER_LENGTH;

        // Distance shoulder -> target, clamped to the reachable annulus (avoids NaN at the extremes).
        float d = Mth.clamp(Mth.sqrt(h * h + v * v), Math.abs(l1 - l2) + 0.01f, l1 + l2 - 0.01f);

        float alpha = (float) Math.atan2(v, h);                              // direction to the target
        float cosBeta = (l1 * l1 + d * d - l2 * l2) / (2.0f * l1 * d);       // upper vs target line
        float beta = (float) Math.acos(Mth.clamp(cosBeta, -1.0f, 1.0f));

        // Upper segment elevation above the "out" horizontal. alpha + beta gives the knee-up config
        // (femur raised above the straight shoulder->foot line), which is the spider silhouette.
        float a1 = alpha + beta;

        float cosGamma = (l1 * l1 + l2 * l2 - d * d) / (2.0f * l1 * l2);     // interior knee angle
        float gamma = (float) Math.acos(Mth.clamp(cosGamma, -1.0f, 1.0f));
        float kneeBend = (float) Math.PI - gamma;                           // how far the knee folds

        // Map plane angles back to the bones (verified against rest: a1=+35deg -> upperZ=-35deg,
        // kneeBend=90deg -> lowerZ=+90deg).
        return new LegPose(-a1, kneeBend);
    }
}
