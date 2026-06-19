package com.payangar.cauchemar.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.entity.climber.Orientation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * Renderer for the Mother Spider. Delegates geometry/texture/animation to {@link MotherSpiderModel},
 * and tilts the whole model onto the climbed surface in {@link #preRender} using the entity's
 * {@link Orientation} (ported from Nyf's Spiders).
 */
public class MotherSpiderRenderer extends GeoEntityRenderer<MotherSpiderEntity> {

    public MotherSpiderRenderer(EntityRendererProvider.Context context) {
        super(context, new MotherSpiderModel());
    }

    @Override
    public void preRender(PoseStack poseStack, MotherSpiderEntity entity, BakedGeoModel model, @Nullable MultiBufferSource bufferSource,
                          @Nullable VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        super.preRender(poseStack, entity, model, bufferSource, buffer, isReRender, partialTick, packedLight, packedOverlay, colour);

        // Rotate the whole model so its up-axis aligns with the surface normal (floor/wall/ceiling).
        Orientation orientation = entity.getOrientation();
        Orientation renderOrientation = entity.calculateOrientation(partialTick);
        entity.setRenderOrientation(renderOrientation);

        float verticalOffset = entity.getVerticalOffset(partialTick);
        float x = entity.getAttachmentOffset(Direction.Axis.X, partialTick) - (float) renderOrientation.normal.x * verticalOffset;
        float y = entity.getAttachmentOffset(Direction.Axis.Y, partialTick) - (float) renderOrientation.normal.y * verticalOffset;
        float z = entity.getAttachmentOffset(Direction.Axis.Z, partialTick) - (float) renderOrientation.normal.z * verticalOffset;

        poseStack.translate(x, y, z);
        poseStack.mulPose(Axis.YP.rotationDegrees(renderOrientation.yaw));
        poseStack.mulPose(Axis.XP.rotationDegrees(renderOrientation.pitch));
        poseStack.mulPose(Axis.YP.rotationDegrees(
                (float) Math.signum(0.5f - orientation.componentY - orientation.componentZ - orientation.componentX) * renderOrientation.yaw));
    }
}
