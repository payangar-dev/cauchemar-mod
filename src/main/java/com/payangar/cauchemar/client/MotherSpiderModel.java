package com.payangar.cauchemar.client;

import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import net.minecraft.resources.ResourceLocation;
import software.bernie.geckolib.model.DefaultedEntityGeoModel;

/**
 * GeoModel for the Mother Spider. Using {@link DefaultedEntityGeoModel} derives the asset paths
 * from the given id by convention:
 * <ul>
 *     <li>model:     {@code assets/cauchemar/geo/entity/mother_spider.geo.json}</li>
 *     <li>texture:   {@code assets/cauchemar/textures/entity/mother_spider.png}</li>
 *     <li>animation: {@code assets/cauchemar/animations/entity/mother_spider.animation.json}</li>
 * </ul>
 */
public class MotherSpiderModel extends DefaultedEntityGeoModel<MotherSpiderEntity> {

    public MotherSpiderModel() {
        super(ResourceLocation.fromNamespaceAndPath(Cauchemar.MOD_ID, "mother_spider"));
    }
}
