package com.payangar.cauchemar.block;

import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.client.SpiderNursery;
import com.payangar.cauchemar.registry.ModParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A clutch of spider eggs wrapped in silk, whether still sealed or already hatched. Both share
 * every mechanic and differ only in shape, which is why it is passed in.
 *
 * <p>That shape covers the egg mass alone. The silk skirt that wraps the model is purely visual:
 * leaving it out means the player neither collides with nor targets the silk, and the outline hugs
 * the eggs. Same approach as vanilla's sniffer egg.
 */
public class SpiderEggBlock extends Block {

    /**
     * Drag applied while an entity overlaps the block: brushing past its sides, jumping against it,
     * falling along it. Milder than a cobweb (0.25 / 0.05 / 0.25), milder than a berry bush too.
     *
     * <p>Standing on top is not covered here, because an entity resting on the block never overlaps
     * it; that case is handled by the block's speed factor instead.
     *
     * <p>Kept this close to 1 because the engine also zeroes the entity's velocity on every tick of
     * contact, so the drag always bites harder than the multiplier alone suggests.
     */
    private static final Vec3 STUCK_MULTIPLIER = new Vec3(0.9, 0.85, 0.9);

    /**
     * Blades that cut silk quickly: swords and shears, matching what both do to a cobweb.
     *
     * <p>A tag of our own rather than an existing one. Vanilla grants that bonus through rules
     * hardcoded onto {@code Blocks.COBWEB} in each tool ({@code minesAndDrops(COBWEB, 15.0F)}), which
     * we cannot join, and NeoForge's {@code c:tools/shear} explicitly warns against being used to
     * decide tool behaviour. This one is also datapack-overridable.
     */
    private static final TagKey<Item> CUTTING_TOOLS = TagKey.create(
            Registries.ITEM, ResourceLocation.fromNamespaceAndPath(Cauchemar.MOD_ID, "cuts_spider_eggs"));

    /** How much faster those tools cut through the eggs. Same factor a cobweb gives them. */
    private static final float CUTTING_SPEED_BONUS = 15.0F;

    /** Chance that tearing a sealed egg open releases a spider. */
    private static final float HATCH_CHANCE = 0.15F;
    /** Share of those hatchings that release a cave spider rather than an ordinary one. */
    private static final float CAVE_SPIDER_SHARE = 0.10F;

    /** Chance that a sealed egg spills a swarm of hatchlings. Purely visual, no entity involved. */
    private static final float SWARM_CHANCE = 1.0F / 3.0F;
    private static final int SWARM_SIZE = 12;
    /**
     * Bearings sent to the hatchlings. The particle reads the length as a pace and clamps it to its
     * own fleeing range, so these only have to sit within it.
     */
    private static final double SWARM_MIN_SPEED = 0.09;
    private static final double SWARM_MAX_SPEED = 0.17;


    private final VoxelShape shape;
    private final boolean sealed;

    public SpiderEggBlock(Properties properties, VoxelShape shape, boolean sealed) {
        super(properties);
        this.shape = shape;
        this.sealed = sealed;
    }

    /**
     * Tearing a sealed egg open disturbs what was growing inside: a swarm of hatchlings may spill
     * out, and a real spider may come with it. The two are rolled apart, so an egg can do either,
     * both or neither. Modelled on vanilla's infested blocks, which spawn a silverfish this way.
     *
     * <p>Nothing happens when the egg is harvested whole, with shears or Silk Touch: there is no
     * broken egg then, and the block itself is in the player's hands.
     */
    @Override
    protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack, boolean dropExperience) {
        super.spawnAfterBreak(state, level, pos, stack, dropExperience);

        if (!this.sealed || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }
        if (stack.is(Items.SHEARS) || EnchantmentHelper.hasTag(stack, EnchantmentTags.PREVENTS_INFESTED_SPAWNS)) {
            return;
        }

        if (level.random.nextFloat() < SWARM_CHANCE) {
            spillSwarm(level, pos);
        }
        if (level.random.nextFloat() < HATCH_CHANCE) {
            hatchSpider(level, pos);
        }
    }

    /**
     * A sealed clutch is never quite still: a hatchling or two wander around it.
     *
     * <p>Client side only, and fired at random for blocks near the player, so there is no way to
     * count what is already out there. The population is steered by birth rate against the
     * particle's own lifetime instead, which lands around one at a time and gives the occasional
     * none or pair.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        // The side check is not decoration: animateTick is a common method that Minecraft merely
        // happens to call from the client, and SpiderNursery is client-only code. Guarding the call
        // keeps a dedicated server from ever resolving that class.
        if (this.sealed && level.isClientSide) {
            SpiderNursery.tendTo(level, pos, random);
        }
    }

    /**
     * Scatters hatchlings outwards from the middle of the block. Each one is sent on its own, with
     * a particle count of zero: that is what turns the packet's offsets into an exact velocity. Any
     * count above zero would have the client scatter them at random instead, which is precisely
     * what we are avoiding here.
     */
    private static void spillSwarm(ServerLevel level, BlockPos pos) {
        // At the very bottom of the block: it is already air by now, and the hatchlings look for
        // their footing downwards from where they are handed.
        double x = pos.getX() + 0.5;
        double y = pos.getY();
        double z = pos.getZ() + 0.5;

        for (int i = 0; i < SWARM_SIZE; i++) {
            double heading = level.random.nextDouble() * Mth.TWO_PI;
            double speed = SWARM_MIN_SPEED + level.random.nextDouble() * (SWARM_MAX_SPEED - SWARM_MIN_SPEED);
            level.sendParticles(ModParticles.SPIDER.get(), x, y, z, 0,
                    Math.cos(heading) * speed, 0.0, Math.sin(heading) * speed, 1.0);
        }
    }

    private static void hatchSpider(ServerLevel level, BlockPos pos) {
        EntityType<? extends Spider> type =
                level.random.nextFloat() < CAVE_SPIDER_SHARE ? EntityType.CAVE_SPIDER : EntityType.SPIDER;
        Spider spider = type.spawn(level, pos, MobSpawnType.TRIGGERED);
        if (spider != null) {
            spider.spawnAnim();
        }
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float progress = super.getDestroyProgress(state, player, level, pos);
        return player.getMainHandItem().is(CUTTING_TOOLS) ? progress * CUTTING_SPEED_BONUS : progress;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return this.shape;
    }

    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        entity.makeStuckInBlock(state, STUCK_MULTIPLIER);
    }

    /**
     * Silk burns. Same odds as wool, which is what vanilla gives its softest flammable blocks.
     *
     * <p>This also makes fire placeable against the block. Fire only survives where the block below
     * has a full top face, or where a neighbour is flammable; the eggs fail the first test, since
     * their shape does not fill the block's top, so being flammable is what allows a torch to the
     * side or on top of a nest to take.
     */
    @Override
    public int getFireSpreadSpeed(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 30;
    }

    @Override
    public int getFlammability(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return 60;
    }
}
