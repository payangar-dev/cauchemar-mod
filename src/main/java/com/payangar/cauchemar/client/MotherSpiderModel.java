package com.payangar.cauchemar.client;

import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.entity.climber.Orientation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

/**
 * GeoModel for the Mother Spider. Using {@link DefaultedEntityGeoModel} derives the asset paths
 * from the given id by convention:
 * <ul>
 *     <li>model:     {@code assets/cauchemar/geo/entity/mother_spider.geo.json}</li>
 *     <li>texture:   {@code assets/cauchemar/textures/entity/mother_spider.png}</li>
 *     <li>animation: {@code assets/cauchemar/animations/entity/mother_spider.animation.json}</li>
 * </ul>
 *
 * <p>Head tracking is handled here manually instead of via the built-in {@code turnsHead} flag.
 * The built-in version <em>overwrites</em> the head bone's rotation each frame, which throws away
 * any head pose set by an animation (e.g. the {@code observe} clip tilts the head back to keep it
 * level while the cephalothorax pitches forward). Adding the look rotation on top preserves those.
 */
public class MotherSpiderModel extends DefaultedEntityGeoModel<MotherSpiderEntity> {

    public MotherSpiderModel() {
        super(ResourceLocation.fromNamespaceAndPath(Cauchemar.MOD_ID, "mother_spider"));
    }

    /** Maximum head deviation from the body, in degrees, on each axis. */
    private static final float MAX_HEAD_YAW = 75.0F;
    private static final float MAX_HEAD_PITCH = 45.0F;
    /**
     * Fixed upward tilt (degrees) for the head's resting level so it does not look at the ground
     * (its model "straight ahead" sits a bit low, very visible once crouched in observe).
     * Tune to taste; flip the sign if the head tilts down instead of up.
     */
    private static final float HEAD_PITCH_RAISE = 10.0F;

    // --- Leg IK, milestones 4-5: corrective foot IK blended with the animation, on any surface -----
    // The walk/idle animation drives the legs (gait, stride, lift); the IK only CORRECTS foot contact
    // onto the real surface, and only while the foot is planted. Per leg: read where the animation
    // currently puts the foot (forward kinematics from the animated bones), raycast the surface under
    // it, then blend the foot's vertical target between the surface (planted) and the animation
    // (lifted) using a contact weight derived from the foot's own height. So on flat ground it looks
    // exactly like the animation, on stairs the planted feet snap to the steps, and the lift during a
    // step is never flattened. SIGN constants are flippable if a foot tracks the wrong spot.

    /** Per-leg rest foot position in the body frame (blocks): right (+ = spider's right), forward (+ = head). */
    private record LegAnchor(String name, double right, double forward) {}

    private static final LegAnchor[] LEG_ANCHORS = {
            new LegAnchor("leg_l1", -1.183, 1.949),
            new LegAnchor("leg_l2", -2.031, 0.882),
            new LegAnchor("leg_l3", -2.031, -0.632),
            new LegAnchor("leg_l4", -1.183, -1.699),
            new LegAnchor("leg_r1", 1.183, 1.949),
            new LegAnchor("leg_r2", 2.031, 0.882),
            new LegAnchor("leg_r3", 2.031, -0.632),
            new LegAnchor("leg_r4", 1.183, -1.699),
    };

    /** Leg-plane vertical (model units) of a foot resting at world ground level (the contact baseline). */
    private static final float FOOT_REST_V = -10.97F;
    /** Contact gating: at/below this leg-plane height the foot is planted; +LIFT_RANGE above = full swing. */
    private static final float FOOT_PLANTED_V = -10.0F;
    private static final float FOOT_LIFT_RANGE = 6.0F;
    /** How far above/below the rest foot to search for the surface (blocks). */
    private static final double RAY_UP = 1.0;
    private static final double RAY_DOWN = 1.5;
    /** Flip if feet track the wrong side / wrong front-back (the body-frame axis handedness). */
    private static final double RIGHT_SIGN = 1.0;
    private static final double FORWARD_SIGN = 1.0;
    /** Per-frame easing of the surface target (1 = instant/teleport, lower = smoother). */
    private static final float FOOT_SMOOTHING = 0.35F;
    /** Resting knee Z (left-side convention, radians); used for FK while idle (the _lower isn't keyframed then). */
    private static final float LOWER_REST_Z = (float) (Math.PI / 2.0);

    @Override
    public void setCustomAnimations(MotherSpiderEntity animatable, long instanceId, AnimationState<MotherSpiderEntity> animationState) {
        GeoBone head = getAnimationProcessor().getBone("head");
        if (head != null) {
            EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
            float yaw = Mth.clamp(Mth.wrapDegrees(data.netHeadYaw()), -MAX_HEAD_YAW, MAX_HEAD_YAW);
            float pitch = Mth.clamp(data.headPitch(), -MAX_HEAD_PITCH, MAX_HEAD_PITCH);
            // ASSIGN both channels (never read getRot + add). GeckoLib does not reliably re-apply
            // the head bone's rotation each frame here, so any '+=' kept building on the previous
            // frame's value and the head spun up out of control. Assigning an absolute value each
            // frame is idempotent: it cannot accumulate. Trade-off: this overwrites the head pose
            // from the animations (e.g. observe's level compensation); the raise stands in for it.
            head.setRotY(yaw * Mth.DEG_TO_RAD);
            head.setRotX((pitch + HEAD_PITCH_RAISE) * Mth.DEG_TO_RAD);
        }

        applyFootIk(animatable);
    }

    /**
     * Milestone 4: correct each foot's contact onto the real surface while keeping the animation's
     * gait and lift. Reads where the animation places the foot (forward kinematics), raycasts the
     * surface under it, and blends the foot's vertical target from the surface (when planted) to the
     * animation (when lifted), so the step's lift is preserved and only the planted phase snaps to
     * terrain. Re-solves the 2-bone IK and writes the leg bones.
     *
     * <p>The {@code _lower} bones are only keyframed by the walk clip, so while idle their read value
     * is our own stale output from last frame. Reading it would feed back into a curl (the legs tuck
     * under the body), so when the spider is not moving we take the knee from its rest angle instead.
     */
    private void applyFootIk(MotherSpiderEntity spider) {
        Orientation orientation = spider.getOrientation();
        float bodyYaw = spider.yBodyRot;

        Vec3 right = orientation.getGlobal(bodyYaw + 90.0f, 0).scale(RIGHT_SIGN);
        Vec3 forward = orientation.getGlobal(bodyYaw, 0).scale(FORWARD_SIGN);
        // The surface normal is the legs' "up": raycast along it (not world-down) so feet plant on
        // walls/ceilings too. On the floor this is (0,1,0) and reduces to the previous behaviour.
        Vec3 normal = orientation.normal;
        Vec3 origin = spider.position();

        // Same held "moving" signal as the walk animation, so the knee read and the anim agree and
        // neither flickers on micro-stops.
        boolean moving = spider.isMovingForAnimation();

        for (int i = 0; i < LEG_ANCHORS.length; i++) {
            LegAnchor leg = LEG_ANCHORS[i];
            GeoBone upper = getAnimationProcessor().getBone(leg.name() + "_upper");
            GeoBone lower = getAnimationProcessor().getBone(leg.name() + "_lower");
            if (upper == null || lower == null) {
                continue;
            }
            float mirror = leg.name().startsWith("leg_l") ? 1.0f : -1.0f; // bone Z mirror (validated)

            // Where the animation currently places this foot (un-mirror to the left-side convention).
            // The upper is always keyframed (idle + walk); the knee is read only while moving, else
            // taken from rest so the idle feedback loop (curl under the body) can't happen.
            float animLowerZ = moving ? (mirror * lower.getRotZ()) : LOWER_REST_Z;
            SpiderLegIk.Foot anim = SpiderLegIk.forward(mirror * upper.getRotZ(), animLowerZ);

            // Real surface under the foot: raycast along the normal (works on floor, walls, ceilings).
            Vec3 footRest = origin.add(right.scale(leg.right())).add(forward.scale(leg.forward()));
            BlockHitResult hit = spider.level().clip(new ClipContext(
                    footRest.add(normal.scale(RAY_UP)), footRest.subtract(normal.scale(RAY_DOWN)),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, spider));
            float surfaceV = hit.getType() == HitResult.Type.BLOCK
                    ? FOOT_REST_V + (float) (hit.getLocation().subtract(footRest).dot(normal) * 16.0)
                    : FOOT_REST_V;
            float smoothedSurfaceV = spider.smoothLegFootV(i, surfaceV, FOOT_SMOOTHING);

            // Contact weight: 0 while the foot is planted (pull to the surface), 1 at full lift (keep
            // the animation's foot height so the step is never flattened).
            float swing = Mth.clamp((anim.v() - FOOT_PLANTED_V) / FOOT_LIFT_RANGE, 0.0f, 1.0f);
            float correctedV = Mth.lerp(swing, smoothedSurfaceV, anim.v());

            // Keep the animation's horizontal stride; only the vertical contact is corrected.
            SpiderLegIk.LegPose pose = SpiderLegIk.solve(anim.h(), correctedV);
            upper.setRotZ(mirror * pose.upperZ());
            lower.setRotZ(mirror * pose.lowerZ());
        }
    }
}
