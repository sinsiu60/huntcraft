package kr.huntcraft.client.mixin;

import kr.huntcraft.client.render.HunterPoseRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적(플레이어 렌더): 헌터 모션 · 발도 자세 중에는 모델 몸 방향을 판정과 같은 값(보간된 yRot)으로 맞춤.
 * 바닐라 몸 방향은 머리를 늦게 따라가서, 그대로 두면 화면의 칼과 판정 도형이 어긋납니다.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
			at = @At("TAIL"))
	private void huntcraft$hunterBodyYaw(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		HunterPoseRenderer.extract(entity, state, partialTicks);
	}
}
