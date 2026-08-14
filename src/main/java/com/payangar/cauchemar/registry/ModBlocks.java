package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.block.SpiderEggBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.util.DeferredSoundType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's blocks. Declare blocks as static fields on {@link #BLOCKS},
 * then call {@link #register(IEventBus)} from the main mod constructor.
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cauchemar.MOD_ID);

    /**
     * Custom sounds for everything but falling. {@link DeferredSoundType} takes suppliers rather
     * than sound events: a block needs its sound type while it is being created, which happens
     * before the sound registry is populated.
     *
     * <p>The hit sound reuses the step clips. Mining replays it every 4 ticks at a 0.5 pitch, which
     * stretches a 0.41s clip to 0.82s, so about four instances overlap at any time. That layering
     * is intentional here, but it is the reason a hit sound has to be a short clip.
     */
    private static final SoundType SPIDER_EGG_SOUNDS = new DeferredSoundType(1.0F, 1.0F,
            ModSounds.SPIDER_EGG_BREAK,
            ModSounds.SPIDER_EGG_STEP,
            ModSounds.SPIDER_EGG_PLACE,
            ModSounds.SPIDER_EGG_HIT,
            SoundType.WOOL::getFallSound);

    /**
     * Shared by both eggs. {@code noOcclusion} is required: neither model is a full cube, and
     * without it neighbouring blocks would cull their own faces against them and the gap would show
     * straight through the world.
     */
    private static BlockBehaviour.Properties eggProperties() {
        return BlockBehaviour.Properties.of()
                // Hardness just under a cobweb's 4.0, but almost no blast resistance: silk gives way
                // to any explosion. strength(x) would set both to the same value, hence the pair.
                // Deliberately without requiresCorrectToolForDrops: vanilla marks a sword as the
                // cobweb's correct tool through a rule on the tool itself, which we cannot join, so
                // requiring one here would mean the block never drops at all.
                .strength(3.75F, 0.5F)
                .sound(SPIDER_EGG_SOUNDS)
                // Drag while walking on top. The block's own entityInside covers every other
                // contact, but never this one: an entity standing on the block does not overlap it.
                .speedFactor(0.75F)
                .noOcclusion();
    }

    /**
     * A clutch of spider eggs wrapped in silk. Shape is the bounding box of the model's mass and
     * dome. Tearing one open has a 15% chance of releasing a spider.
     */
    public static final DeferredBlock<SpiderEggBlock> SPIDER_EGG = BLOCKS.registerBlock("spider_egg",
            props -> new SpiderEggBlock(props, Block.box(1.0, 0.0, 2.0, 15.0, 16.0, 14.0), true),
            eggProperties());

    /** The same clutch after hatching: collapsed, no dome, and nothing left inside to come out. */
    public static final DeferredBlock<SpiderEggBlock> SPIDER_EGG_HATCHED = BLOCKS.registerBlock("spider_egg_hatched",
            props -> new SpiderEggBlock(props, Block.box(1.0, 0.0, 2.0, 15.0, 10.0, 14.0), false),
            eggProperties());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    private ModBlocks() {
    }
}
