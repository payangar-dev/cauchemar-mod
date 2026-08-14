package com.payangar.cauchemar.block;

import com.payangar.cauchemar.Cauchemar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.TagKey;
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

    /** Share of hatchings that release a cave spider rather than an ordinary one. */
    private static final float CAVE_SPIDER_SHARE = 0.10F;

    private final VoxelShape shape;
    private final float hatchChance;

    public SpiderEggBlock(Properties properties, VoxelShape shape, float hatchChance) {
        super(properties);
        this.shape = shape;
        this.hatchChance = hatchChance;
    }

    /**
     * Tearing an egg open can release what was growing inside. Modelled on vanilla's infested
     * blocks, which spawn a silverfish the same way.
     *
     * <p>Nothing hatches when the egg is harvested whole, with shears or Silk Touch: there is no
     * broken egg then, and the block itself is in the player's hands.
     */
    @Override
    protected void spawnAfterBreak(BlockState state, ServerLevel level, BlockPos pos, ItemStack stack, boolean dropExperience) {
        super.spawnAfterBreak(state, level, pos, stack, dropExperience);

        if (this.hatchChance <= 0.0F || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) {
            return;
        }
        if (stack.is(Items.SHEARS) || EnchantmentHelper.hasTag(stack, EnchantmentTags.PREVENTS_INFESTED_SPAWNS)) {
            return;
        }
        if (level.random.nextFloat() >= this.hatchChance) {
            return;
        }

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
