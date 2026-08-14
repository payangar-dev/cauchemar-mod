package com.payangar.cauchemar.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SingleQuadParticle.FacingCameraMode;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.payangar.cauchemar.registry.ModBlocks;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A hatchling crawling over the terrain: along floors, up walls, under ceilings.
 *
 * <p>It does not use the particle physics at all. Vanilla would drop it under gravity and rest it
 * exactly on a block face, which both flattens it to the floor and makes the quad flicker against
 * that face. Instead it holds onto a surface: a normal telling which face it is stuck to, a heading
 * within that face's plane, and a rule for what to do when the surface runs out.
 */
public class SpiderParticle extends TextureSheetParticle {

    /**
     * Frames listed in {@code particles/spider.json}, in order: two of walking, then the dead one.
     * The sprite set maps an index straight through when handed {@code TOTAL_FRAMES - 1} as the max
     * age, since it computes {@code age * (frames - 1) / maxAge}.
     */
    private static final int TOTAL_FRAMES = 3;
    private static final int WALK_FRAMES = 2;
    private static final int DEAD_FRAME = 2;
    /** Ticks each walking frame is held. Low enough to read as movement, not as a pose change. */
    private static final int TICKS_PER_FRAME = 4;

    /** Gap kept from the surface. Enough to stop the quad fighting the block face it lies on. */
    private static final double CLEARANCE = 0.02;
    /** How far past itself the spider feels for a wall ahead or for missing ground. */
    private static final double FEELER = 0.08;
    /** How many blocks down it will look for a foothold when it appears. */
    private static final int FOOTING_SEARCH = 3;

    /** Pace of a hatchling milling about near its egg. */
    private static final double MIN_SPEED = 0.02;
    private static final double MAX_SPEED = 0.055;
    /** Pace of one fleeing a broken egg: about three times as fast. */
    private static final double FLEE_MIN_SPEED = 0.09;
    private static final double FLEE_MAX_SPEED = 0.17;

    /** Ticks of fading before a hatchling is gone, so it thins out instead of blinking off. */
    private static final int FADE_TICKS = 20;

    /** How long a brood hatchling lingers once its egg is gone, fading included. */
    private static final int MIN_ORPHAN_DELAY = 40;
    private static final int MAX_ORPHAN_DELAY = 60;
    /** Checking every tick would cost a block lookup per hatchling for no visible gain. */
    private static final int EGG_CHECK_INTERVAL = 10;

    /** Reach of a footstep around the player's feet, and how far above or below it still counts. */
    private static final double STOMP_RADIUS = 0.45;
    private static final double STOMP_HEIGHT = 0.5;
    /**
     * How far the player must have travelled during the tick for the step to crush anything. This is
     * what makes a spider walking under someone standing still perfectly safe.
     */
    private static final double STOMP_MIN_TRAVEL = 0.005;
    /** Ticks a crushed spider stays plainly visible before it starts to fade. */
    private static final int DEATH_HOLD = 25;

    /**
     * A hatchling sent off with a bearing is one that fled a broken egg: it scatters and is gone
     * shortly after. One released with no bearing belongs to an intact egg's brood and stays until
     * its chunk goes, its count being held by {@link SpiderNursery}.
     */
    private static final int SCATTERED_LIFETIME = 60;
    private static final int ENDLESS = Integer.MAX_VALUE;

    /**
     * Far enough that a two-decimetre sprite cannot be made out, so a brood is never seen winking
     * out. Only a safety net: the real end of a hatchling is its chunk being dropped.
     */
    private static final double FORGET_DISTANCE = 48.0;

    /** How far it will stray from where it appeared before turning back. */
    private static final double LEASH = 6.0;

    /** Odds per tick of stopping, and how long it then stays put. */
    private static final float PAUSE_CHANCE = 0.02F;
    private static final int MIN_PAUSE = 15;
    private static final int MAX_PAUSE = 60;

    /** Slow weave of the heading, so the path curves instead of running straight. */
    private static final float WEAVE_RATE = 0.22F;
    private static final float WEAVE_AMPLITUDE = 0.45F;
    /** Slow surge of the pace, so it scuttles rather than gliding at a fixed speed. */
    private static final float SURGE_RATE = 0.15F;
    private static final float SURGE_AMPLITUDE = 0.45F;

    private final SpriteSet sprites;
    /** Reads the current surface instead of the camera, so the quad lies on whatever it walks on. */
    private final FacingCameraMode surfaceFacing =
            (quaternion, camera, partialTick) -> quaternion.set(this.surfaceRotation());

    /** Phases are per spider: shared ones would make the whole swarm weave in unison. */
    private final float weavePhase;
    private final float surgePhase;

    /** Where it appeared: a leash for a brood hatchling, a place to run from for a fleeing one. */
    private final Vec3 anchor;
    private final boolean fleeing;

    /** Face the spider is stuck to, pointing away from the block supporting it. */
    private Direction surface = Direction.UP;
    /** Direction of travel within that face's plane, in radians. */
    private double heading;
    private double baseSpeed;
    /** Ticks spent moving. Drives the frames and the weaving, so a stopped spider truly stops. */
    private int walkTicks;
    private int pauseTicks;
    private boolean crushed;
    /** The egg this one belongs to, if any. A fleeing hatchling has none: its egg is already gone. */
    @Nullable
    private BlockPos egg;

    protected SpiderParticle(ClientLevel level, double x, double y, double z,
                             double xSpeed, double ySpeed, double zSpeed, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.anchor = new Vec3(x, y, z);
        this.quadSize = 0.2F;
        this.hasPhysics = false;
        this.gravity = 0.0F;

        this.weavePhase = this.random.nextFloat() * Mth.TWO_PI;
        this.surgePhase = this.random.nextFloat() * Mth.TWO_PI;

        // The spawn velocity is read as an intent rather than applied: its bearing sets the initial
        // heading, its length the pace. A spider sent with no velocity simply picks its own.
        double given = Math.sqrt(xSpeed * xSpeed + zSpeed * zSpeed);
        this.fleeing = given > 1.0E-4;
        this.heading = this.fleeing ? Math.atan2(zSpeed, xSpeed) : this.random.nextDouble() * Mth.TWO_PI;
        this.baseSpeed = this.fleeing
                ? Mth.clamp(given, FLEE_MIN_SPEED, FLEE_MAX_SPEED)
                : MIN_SPEED + this.random.nextDouble() * (MAX_SPEED - MIN_SPEED);
        this.lifetime = this.fleeing ? SCATTERED_LIFETIME : ENDLESS;

        if (!this.findFooting()) {
            this.remove();
            return;
        }
        this.setFrame();
    }

    /**
     * Looks for something to stand on at birth, a few blocks down at most.
     *
     * <p>Without this a spider handed a spot in mid-air would slide sideways through the world: it
     * has no falling of its own, and the routine that keeps it against its surface trusts whatever
     * block sits below it. That is exactly what happens where an egg just broke, since the block is
     * already air by the time the swarm is released.
     */
    private boolean findFooting() {
        for (int drop = 0; drop <= FOOTING_SEARCH; drop++) {
            BlockPos candidate = BlockPos.containing(this.x, this.y - drop - CLEARANCE, this.z);
            if (this.level.getBlockState(candidate).isSolid()) {
                this.surface = Direction.UP;
                this.y = candidate.getY() + 1 + CLEARANCE;
                return true;
            }
        }
        return false;
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.oRoll = this.roll;

        if (this.age++ >= this.lifetime || this.outOfReach()) {
            this.remove();
            return;
        }
        if (this.crushed) {
            this.fade();
            return;
        }
        if (this.underfoot()) {
            this.crush();
            return;
        }

        this.checkOnEgg();
        this.fade();

        // Only a settled hatchling ever stops. One running from a broken egg keeps going until it
        // is gone.
        if (!this.fleeing) {
            if (this.pauseTicks > 0) {
                this.pauseTicks--;
                return;
            }
            if (this.random.nextFloat() < PAUSE_CHANCE) {
                this.pauseTicks = MIN_PAUSE + this.random.nextInt(MAX_PAUSE - MIN_PAUSE);
                return;
            }
        }

        this.walkTicks++;
        this.crawl();
        this.setFrame();
    }

    /**
     * Advances by one step within the current surface, and changes surface when the terrain calls
     * for it. Two cases, in this order, because a step can meet both at once and the wall wins:
     * a wall ahead is climbed, and ground that runs out is followed around the edge.
     */
    private void crawl() {
        Vec3 normal = this.normal();
        this.keepBearing(normal);
        Vec3 forward = this.forward(normal);
        Vec3 next = this.position().add(forward.scale(this.currentSpeed()));

        if (this.isSolid(next.add(forward.scale(FEELER)))) {
            // A wall: its facing side becomes the new surface, and the spider keeps going upwards
            // along what used to be its own normal.
            this.moveTo(next);
            this.turnOnto(Direction.getNearest(-forward.x, -forward.y, -forward.z), normal);
        } else if (!this.isSolid(next.subtract(normal.scale(FEELER)))) {
            // The ground ran out: the spider curls around the edge and carries on underneath, its
            // heading becoming the reverse of the surface it is leaving.
            this.moveTo(next);
            this.turnOnto(Direction.getNearest(forward.x, forward.y, forward.z), normal.reverse());
        } else {
            this.moveTo(next);
        }

        // Aim last: a turn may have changed both the surface and the heading just above.
        this.aimAt(this.forward(this.normal()));
    }

    /**
     * A hatchling ends with the chunk that carried it, never on a timer, so a brood is never seen
     * vanishing. The distance check behind it only guards against ones left behind in a chunk that
     * stays loaded while the player walks away.
     */
    private boolean outOfReach() {
        BlockPos here = BlockPos.containing(this.x, this.y, this.z);
        if (!this.level.hasChunkAt(here)) {
            return true;
        }
        Player player = Minecraft.getInstance().player;
        return player != null && player.distanceToSqr(this.x, this.y, this.z) > FORGET_DISTANCE * FORGET_DISTANCE;
    }

    /**
     * Points the spider's head along its travel. The roll has to be measured against the quad's own
     * axes once laid on the surface, not against the internal heading: that heading lives in a
     * tangent basis picked per face, which lines up with the sprite only by accident.
     *
     * <p>The head sits at the bottom of the texture, hence the alignment with the quad's downward
     * axis rather than its upward one.
     */
    private void aimAt(Vec3 forward) {
        Quaternionf laid = this.surfaceRotation();
        Vector3f right = laid.transform(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = laid.transform(new Vector3f(0.0F, 1.0F, 0.0F));

        double alongRight = forward.x * right.x() + forward.y * right.y() + forward.z * right.z();
        double alongUp = forward.x * up.x() + forward.y * up.y() + forward.z * up.z();
        this.roll = (float) Math.atan2(alongRight, -alongUp);
    }

    /**
     * Whether a moving player's foot has just come down on this spider.
     *
     * <p>The travel test is the whole point of the rule: it is the player's own movement that kills,
     * so a spider wandering under someone standing still walks away unharmed. Testing the player
     * rather than the spider is what tells the two situations apart.
     */
    private boolean underfoot() {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }
        double travelX = player.getX() - player.xOld;
        double travelZ = player.getZ() - player.zOld;
        if (travelX * travelX + travelZ * travelZ < STOMP_MIN_TRAVEL * STOMP_MIN_TRAVEL) {
            return false;
        }
        if (Math.abs(this.y - player.getY()) > STOMP_HEIGHT) {
            return false;
        }
        double dx = this.x - player.getX();
        double dz = this.z - player.getZ();
        return dx * dx + dz * dz < STOMP_RADIUS * STOMP_RADIUS;
    }

    /** Freezes the spider on its dead frame and lets it fade. Its egg may then replace it. */
    private void crush() {
        this.crushed = true;
        this.lifetime = this.age + DEATH_HOLD + FADE_TICKS;
        this.setSprite(this.sprites.get(DEAD_FRAME, TOTAL_FRAMES - 1));
    }

    /**
     * Ties the hatchling to its egg: once that egg is gone, it has a couple of seconds left.
     *
     * <p>The watching has to be done here rather than in {@link SpiderNursery}, whose only trigger
     * is the block's own animate tick. A broken egg stops being ticked altogether, so the nursery
     * would never hear of its own disappearance.
     *
     * <p>Giving it a finite lifetime is all it takes: the fade then happens on its own.
     */
    private void checkOnEgg() {
        if (this.egg == null || this.lifetime != ENDLESS || this.age % EGG_CHECK_INTERVAL != 0) {
            return;
        }
        if (!this.level.getBlockState(this.egg).is(ModBlocks.SPIDER_EGG.get())) {
            this.lifetime = this.age + MIN_ORPHAN_DELAY
                    + this.random.nextInt(MAX_ORPHAN_DELAY - MIN_ORPHAN_DELAY);
        }
    }

    /** Told by the nursery which egg released it, so it can notice that egg being broken. */
    void belongsTo(BlockPos egg) {
        this.egg = egg;
    }

    /** Thins out over the last ticks of a hatchling's life, whatever ended it. */
    private void fade() {
        if (this.lifetime == ENDLESS) {
            return;
        }
        int left = this.lifetime - this.age;
        this.alpha = left >= FADE_TICKS ? 1.0F : Math.max(0.0F, left / (float) FADE_TICKS);
    }

    /**
     * Holds each kind to its ground: a brood hatchling is drawn back once it strays too far from
     * its egg, a fleeing one is never allowed to turn back towards the one that broke.
     *
     * <p>Aiming at a point rather than simply reversing keeps them from bouncing back and forth
     * along the same line.
     */
    private void keepBearing(Vec3 normal) {
        Vec3 fromAnchor = this.position().subtract(this.anchor);
        if (this.fleeing) {
            if (fromAnchor.lengthSqr() > 1.0E-6 && this.forward(normal).dot(fromAnchor.normalize()) < 0.0) {
                this.aimHeading(normal, fromAnchor);
            }
        } else if (fromAnchor.lengthSqr() > LEASH * LEASH) {
            this.aimHeading(normal, fromAnchor.reverse());
        }
    }

    /** Sets the heading so the spider walks towards a direction, expressed in the surface's plane. */
    private void aimHeading(Vec3 normal, Vec3 target) {
        Vec3 u = this.tangent(normal);
        Vec3 v = normal.cross(u);
        this.heading = Math.atan2(target.dot(v), target.dot(u));
    }

    /** Switches to a new surface and re-expresses the heading in that surface's own plane. */
    private void turnOnto(Direction newSurface, Vec3 newForward) {
        this.surface = newSurface;
        Vec3 normal = this.normal();
        Vec3 u = this.tangent(normal);
        Vec3 v = normal.cross(u);
        this.heading = Math.atan2(newForward.dot(v), newForward.dot(u));
        this.settleOnSurface();
    }

    /** Sits the spider a fixed gap off the face it walks on, which is what keeps it from flickering. */
    private void settleOnSurface() {
        Vec3 normal = this.normal();
        BlockPos support = BlockPos.containing(this.position().subtract(normal.scale(FEELER)));
        double face = switch (this.surface) {
            case UP -> support.getY() + 1;
            case DOWN -> support.getY();
            case EAST -> support.getX() + 1;
            case WEST -> support.getX();
            case SOUTH -> support.getZ() + 1;
            case NORTH -> support.getZ();
        };

        switch (this.surface.getAxis()) {
            case X -> this.x = face + normal.x * CLEARANCE;
            case Y -> this.y = face + normal.y * CLEARANCE;
            case Z -> this.z = face + normal.z * CLEARANCE;
        }
    }

    /** Heading and pace both weave, out of phase, so the path never reads as a straight glide. */
    private Vec3 forward(Vec3 normal) {
        double woven = this.heading + Mth.sin(this.walkTicks * WEAVE_RATE + this.weavePhase) * WEAVE_AMPLITUDE;
        Vec3 u = this.tangent(normal);
        Vec3 v = normal.cross(u);
        return u.scale(Math.cos(woven)).add(v.scale(Math.sin(woven)));
    }

    private double currentSpeed() {
        return this.baseSpeed * (1.0 + Mth.sin(this.walkTicks * SURGE_RATE + this.surgePhase) * SURGE_AMPLITUDE);
    }

    /** Any vector at right angles to the normal will do; it only has to be stable for a given face. */
    private Vec3 tangent(Vec3 normal) {
        Vec3 reference = Math.abs(normal.y) > 0.5 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        return normal.cross(reference).normalize();
    }

    private Vec3 normal() {
        return Vec3.atLowerCornerOf(this.surface.getNormal());
    }

    private Vec3 position() {
        return new Vec3(this.x, this.y, this.z);
    }

    private void moveTo(Vec3 to) {
        this.x = to.x;
        this.y = to.y;
        this.z = to.z;
    }

    private boolean isSolid(Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        return this.level.getBlockState(pos).isSolid();
    }

    /**
     * Loops the frames rather than spreading them across the lifetime, which is what the built-in
     * {@code setSpriteFromAge} does: a single pass would read as one pose change, not as crawling.
     * Passing {@code FRAME_COUNT - 1} as the max age makes the sprite set map the index straight
     * through, since it computes {@code age * (frames - 1) / maxAge}.
     */
    private void setFrame() {
        this.setSprite(this.sprites.get((this.walkTicks / TICKS_PER_FRAME) % WALK_FRAMES, TOTAL_FRAMES - 1));
    }

    /** Turns the quad from camera-facing to lying on the current surface. */
    private Quaternionf surfaceRotation() {
        return switch (this.surface) {
            case UP -> new Quaternionf().rotationX(-Mth.HALF_PI);
            case DOWN -> new Quaternionf().rotationX(Mth.HALF_PI);
            case SOUTH -> new Quaternionf();
            case NORTH -> new Quaternionf().rotationY(Mth.PI);
            case EAST -> new Quaternionf().rotationY(Mth.HALF_PI);
            case WEST -> new Quaternionf().rotationY(-Mth.HALF_PI);
        };
    }

    @Override
    public FacingCameraMode getFacingCameraMode() {
        return this.surfaceFacing;
    }

    @Override
    public ParticleRenderType getRenderType() {
        // Translucent and not lit, despite the name: the lit sheet disables blending outright, which
        // makes alpha meaningless and the fade impossible. Both sheets light the sprite the same way.
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<SimpleParticleType> {

        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Nullable
        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level,
                                       double x, double y, double z,
                                       double xSpeed, double ySpeed, double zSpeed) {
            return new SpiderParticle(level, x, y, z, xSpeed, ySpeed, zSpeed, this.sprites);
        }
    }
}
