package kr.huntcraft.client.mixin;

import kr.huntcraft.client.render.HunterPoseRenderer;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 목적(플레이어 렌더): 바닐라 자세가 정해진 뒤 헌터 스켈레톤 자세(판정과 같은 계산)로 팔 · 몸 · 다리를 덮어씀. */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void huntcraft$hunterPose(AvatarRenderState state, CallbackInfo ci) {
		HunterPoseRenderer.pose((PlayerModel) (Object) this, state);
	}
}
