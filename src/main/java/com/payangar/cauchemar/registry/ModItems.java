package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's items. Declare items as static fields on {@link #ITEMS},
 * then call {@link #register(IEventBus)} from the main mod constructor.
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cauchemar.MOD_ID);

    /** Lets the block be held, placed and picked up. A block without a BlockItem cannot be obtained. */
    public static final DeferredItem<BlockItem> SPIDER_EGG = ITEMS.registerSimpleBlockItem(ModBlocks.SPIDER_EGG);
    public static final DeferredItem<BlockItem> SPIDER_EGG_HATCHED = ITEMS.registerSimpleBlockItem(ModBlocks.SPIDER_EGG_HATCHED);

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(ModItems::onBuildCreativeTabs);
    }

    private static void onBuildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.NATURAL_BLOCKS) {
            event.accept(SPIDER_EGG);
            event.accept(SPIDER_EGG_HATCHED);
        }
    }

    private ModItems() {
    }
}
