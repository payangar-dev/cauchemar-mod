package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's blocks. Declare blocks as static fields on {@link #BLOCKS},
 * then call {@link #register(IEventBus)} from the main mod constructor.
 */
public final class ModBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cauchemar.MOD_ID);

    // Example:
    // public static final DeferredBlock<Block> EXAMPLE_BLOCK =
    //         BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    private ModBlocks() {
    }
}
