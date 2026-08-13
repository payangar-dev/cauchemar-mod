package com.payangar.cauchemar.movement.climber;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.Pair;

/**
 * An immutable surface-relative coordinate frame for a climbing entity.
 *
 * <p>Built from the surface normal the entity is attached to: {@code localY} is the surface normal
 * (the entity's "up"), {@code localX}/{@code localZ} span the surface plane, and {@code yaw}/
 * {@code pitch} describe how that frame is rotated relative to world space. Ported from Nyf's
 * Spiders (self-contained 1.20.4 implementation).
 */
public class Orientation {
    public final Vec3 normal, localZ, localY, localX;
    public final float componentZ, componentY, componentX, yaw, pitch;

    public Orientation(Vec3 normal, Vec3 localZ, Vec3 localY, Vec3 localX, float componentZ, float componentY, float componentX, float yaw, float pitch) {
        this.normal = normal;
        this.localZ = localZ;
        this.localY = localY;
        this.localX = localX;
        this.componentZ = componentZ;
        this.componentY = componentY;
        this.componentX = componentX;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    /** Transforms a vector from surface-local space to world space. */
    public Vec3 getGlobal(Vec3 local) {
        return this.localX.scale(local.x).add(this.localY.scale(local.y)).add(this.localZ.scale(local.z));
    }

    /** Builds a world-space direction from a surface-local yaw/pitch (degrees). */
    public Vec3 getGlobal(float yaw, float pitch) {
        float cy = Mth.cos(yaw * 0.017453292F);
        float sy = Mth.sin(yaw * 0.017453292F);
        float cp = -Mth.cos(-pitch * 0.017453292F);
        float sp = Mth.sin(-pitch * 0.017453292F);
        return this.localX.scale(sy * cp).add(this.localY.scale(sp)).add(this.localZ.scale(cy * cp));
    }

    /** Transforms a world-space vector into surface-local space. */
    public Vec3 getLocal(Vec3 global) {
        return new Vec3(this.localX.dot(global), this.localY.dot(global), this.localZ.dot(global));
    }

    /** Returns the surface-local (yaw, pitch) in degrees for a world-space direction. */
    public Pair<Float, Float> getLocalRotation(Vec3 global) {
        Vec3 local = this.getLocal(global);

        float yaw = (float) Math.toDegrees(Mth.atan2(local.x, local.z)) + 180.0f;
        float pitch = (float) -Math.toDegrees(Mth.atan2(local.y, Math.sqrt(local.x * local.x + local.z * local.z)));

        return Pair.of(yaw, pitch);
    }
}
