package kr.huntcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import kr.huntcraft.entity.RubbleEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.FallingBlockRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.RenderShape;

/** 던진 잔해 — 블록 모델을 크게 (scale) 그리고 날아가는 동안 굴림 (떨어지는 블록 렌더와 같은 방식) */
public class RubbleRenderer extends EntityRenderer<RubbleEntity, RubbleRenderer.State> {
	public static class State extends FallingBlockRenderState {
		float scale = 1.5F;
		float spin;
	}

	public RubbleRenderer(EntityRendererProvider.Context ctx) {
		super(ctx);
		this.shadowRadius = 0.5F;
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(RubbleEntity entity, State state, float partialTick) {
		super.extractRenderState(entity, state, partialTick);
		state.scale = entity.scale();
		state.spin = (entity.tickCount + partialTick) * 9.0F;
		var mb = state.movingBlockRenderState;
		mb.randomSeedPos = entity.blockPosition();
		mb.blockPos = entity.blockPosition();
		mb.blockState = entity.block();
		mb.biome = entity.level().getBiome(entity.blockPosition());
		if (entity.level() instanceof net.minecraft.client.multiplayer.ClientLevel cl) {
			mb.cardinalLighting = cl.cardinalLighting();
			mb.lightEngine = cl.getLightEngine();
		}
	}

	@Override
	public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		if (state.movingBlockRenderState.blockState == null || state.movingBlockRenderState.blockState.getRenderShape() != RenderShape.MODEL) {
			return;
		}
		pose.pushPose();
		pose.translate(0.0, 0.5 * state.scale, 0.0);
		pose.mulPose(Axis.XP.rotationDegrees(state.spin));
		pose.mulPose(Axis.ZP.rotationDegrees(state.spin * 0.6F));
		pose.scale(state.scale, state.scale, state.scale);
		pose.translate(-0.5, -0.5, -0.5);
		collector.submitMovingBlock(pose, state.movingBlockRenderState, 0);
		pose.popPose();
		super.submit(state, pose, collector, camera);
	}
}
