package com.payangar.cauchemar.registry;

import com.payangar.cauchemar.Cauchemar;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry for the mod's items. Declare items as static fields on {@link #ITEMS},
 * then call {@link #register(IEventBus)} from the main mod constructor.
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cauchemar.MOD_ID);

    // Example:
    // public static final DeferredItem<Item> EXAMPLE_ITEM =
    //         ITEMS.registerSimpleItem("example_item", new Item.Properties());

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {
    }
}
