package com.payangar.cauchemar.client;

import com.payangar.cauchemar.entity.MotherSpiderEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for the Mother Spider. Geometry, texture and animations all come from
 * {@link MotherSpiderModel}; nothing is adjusted at render time.
 */
public class MotherSpiderRenderer extends GeoEntityRenderer<MotherSpiderEntity> {

    public MotherSpiderRenderer(EntityRendererProvider.Context context) {
        super(context, new MotherSpiderModel());
    }
}
