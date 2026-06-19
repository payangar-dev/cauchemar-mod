package com.payangar.cauchemar.entity;

import com.payangar.cauchemar.entity.climber.Orientation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * An invisible hit box that tracks one body part of the {@link MotherSpiderEntity} (head,
 * cephalothorax or abdomen). It exists only so the large model stays properly hittable despite the
 * small collision box: damage dealt to a part is forwarded to the parent, and the part itself never
 * collides or moves the spider. Parts follow the body in the parent's surface frame (via its {@link
 * Orientation}), so they stay aligned while the spider climbs walls and ceilings. Modeled on Iron's
 * Spells 'n Spellbooks' ice spider.
 *
 * <p>Offsets are body-local in blocks: {@code right} (+ = the spider's right), {@code up} (+ = away
 * from the surface, along the normal) and {@code forward} (+ = the direction the spider faces / walks
 * head-first).
 */
public class MotherSpiderPartEntity extends PartEntity<MotherSpiderEntity> {

    private final MotherSpiderEntity parentMob;
    private final EntityDimensions size;
    private final double bodyRight;
    private final double bodyUp;
    private final double bodyForward;

    public MotherSpiderPartEntity(MotherSpiderEntity parent, double right, double up, double forward, float width, float height) {
        super(parent);
        this.parentMob = parent;
        this.size = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
        this.bodyRight = right;
        this.bodyUp = up;
        this.bodyForward = forward;
    }

    /** Repositions this part to follow the parent's body in its current surface orientation. */
    public void positionSelf() {
        Orientation orientation = this.parentMob.getOrientation();
        float yaw = this.parentMob.yBodyRot;

        // Body axes in world space, derived the same way the climbing travel code builds them, so the
        // parts track the model across floor/wall/ceiling.
        Vec3 forward = orientation.getGlobal(yaw, 0);
        Vec3 right = orientation.getGlobal(yaw + 90.0f, 0);
        Vec3 up = orientation.getGlobal(yaw, -90.0f);

        Vec3 offset = right.scale(this.bodyRight).add(up.scale(this.bodyUp)).add(forward.scale(this.bodyForward));

        // position() is the parent's bottom-center; setPos places this box by its own bottom-center,
        // so drop by half the height to center the box on the body-part point.
        Vec3 center = this.parentMob.position().add(offset);
        this.hardSetPos(center.x, center.y - this.size.height() / 2.0, center.z);
    }

    private void hardSetPos(double x, double y, double z) {
        this.setPos(x, y, z);
        this.xo = this.xOld = x;
        this.yo = this.yOld = y;
        this.zo = this.zOld = z;
    }

    @Override
    public boolean canBeCollidedWith() {
        // Hit detection only; never blocks movement.
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return this.parentMob.hurt(this, source, amount);
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || this.parentMob == entity;
    }

    @Override
    public ItemStack getPickResult() {
        return this.parentMob.getPickResult();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
