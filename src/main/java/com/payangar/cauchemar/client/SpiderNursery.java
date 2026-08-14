package com.payangar.cauchemar.client;

import com.payangar.cauchemar.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of how many hatchlings each sealed egg has out, so an egg tops itself up to a fixed
 * number instead of releasing an endless stream.
 *
 * <p>Lives entirely on the client, and deliberately so: these are particles, they are worth nothing
 * to the game state and must never be saved or synchronised. A brood simply disappears with the
 * chunk it belongs to, and the egg starts again from scratch the next time the player comes near.
 */
public final class SpiderNursery {

    /** How many hatchlings a single egg keeps out at once. */
    private static final int MAX_PER_EGG = 2;
    /** Ticks between two attempts, while the egg is short of its count. */
    private static final int ATTEMPT_INTERVAL = 100;
    private static final float ATTEMPT_CHANCE = 0.30F;

    /** How far around the egg a hatchling may appear, in blocks. */
    private static final int SPREAD = 2;
    private static final int PLACEMENT_TRIES = 8;

    /** Broods with nothing alive and no chunk left behind them are dropped this often. */
    private static final int SWEEP_INTERVAL = 600;

    private static final Map<BlockPos, Brood> BROODS = new HashMap<>();
    private static long lastSweep;

    private static final class Brood {
        private final List<SpiderParticle> alive = new ArrayList<>();
        private long nextAttempt;
    }

    /**
     * Called from the egg's animate tick, which the client fires at random for blocks near the
     * player. That randomness only decides when we look; the interval and the count are ours.
     */
    public static void tendTo(Level level, BlockPos pos, RandomSource random) {
        // Frozen straight away: the position handed to animateTick is a single mutable instance that
        // vanilla repositions hundreds of times per tick. Anything kept beyond this call must be a
        // copy, or it silently ends up pointing at some other block.
        BlockPos egg = pos.immutable();

        long now = level.getGameTime();
        sweep(level, now);

        Brood brood = BROODS.computeIfAbsent(egg, key -> new Brood());
        brood.alive.removeIf(spider -> !spider.isAlive());

        if (brood.alive.size() >= MAX_PER_EGG || now < brood.nextAttempt) {
            return;
        }
        brood.nextAttempt = now + ATTEMPT_INTERVAL;

        if (random.nextFloat() >= ATTEMPT_CHANCE) {
            return;
        }
        SpiderParticle hatchling = release(level, egg, random);
        if (hatchling != null) {
            brood.alive.add(hatchling);
        }
    }

    /**
     * The particle walks on surfaces rather than falling, so it has to be handed a standable spot:
     * an empty block with something solid under it, looked for around and just below the egg.
     */
    @Nullable
    private static SpiderParticle release(Level level, BlockPos egg, RandomSource random) {
        for (int attempt = 0; attempt < PLACEMENT_TRIES; attempt++) {
            int dx = random.nextInt(SPREAD * 2 + 1) - SPREAD;
            int dz = random.nextInt(SPREAD * 2 + 1) - SPREAD;

            for (int drop = 0; drop <= SPREAD; drop++) {
                BlockPos spot = egg.offset(dx, 1 - drop, dz);
                if (level.getBlockState(spot).isAir() && level.getBlockState(spot.below()).isSolid()) {
                    Particle particle = Minecraft.getInstance().particleEngine.createParticle(
                            ModParticles.SPIDER.get(),
                            spot.getX() + random.nextDouble(),
                            spot.getY(),
                            spot.getZ() + random.nextDouble(),
                            0.0, 0.0, 0.0);
                    if (particle instanceof SpiderParticle spider) {
                        spider.belongsTo(egg);
                        return spider;
                    }
                    return null;
                }
            }
        }
        return null;
    }

    /** Drops the bookkeeping for eggs whose chunk is gone, so the map does not grow forever. */
    private static void sweep(Level level, long now) {
        if (now - lastSweep < SWEEP_INTERVAL) {
            return;
        }
        lastSweep = now;
        BROODS.entrySet().removeIf(entry ->
                entry.getValue().alive.isEmpty() && !level.hasChunkAt(entry.getKey()));
    }

    private SpiderNursery() {
    }
}
