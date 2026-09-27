package kr.huntcraft.client.mixin;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적(카메라): 바닐라가 카메라를 엔티티에 맞춘 직후 숄더뷰 위치 · 회전으로 덮어쓰고, 시야각을 설정값으로 고정.
 * 바닐라 3인칭(OptionsCameraTypeMixin 이 강제)이 켜 둔 detached 덕분에 내 캐릭터 모델은 그대로 그려집니다.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "alignWithEntity", at = @At("TAIL"))
	private void huntcraft$shoulderView(float partialTicks, CallbackInfo ci) {
		Minecraft mc = Minecraft.getInstance();
		if (!ShoulderCamera.active(mc)) {
			return;
		}
		Vec3 pos = ShoulderCamera.update(mc, partialTicks);
		this.setRotation(ShoulderCamera.renderYaw(), ShoulderCamera.renderPitch());
		this.setPosition(pos);
	}

	@Inject(method = "calculateFov", at = @At("RETURN"), cancellable = true)
	private void huntcraft$fixedFov(float partialTicks, CallbackInfoReturnable<Float> cir) {
		if (ShoulderCamera.active(Minecraft.getInstance())) {
			// 기술 연출: 모션 데이터의 fov (큰 기술에서 살짝 넓어짐)
			double extra = 0.0;
			kr.huntcraft.combat.MoveSet.Motion m = kr.huntcraft.client.combat.ClientHunters.local().current();
			if (m != null && ClientConfig.get().camera.skillCameraEffects) {
				extra = m.fovAt(kr.huntcraft.client.combat.ClientHunters.local().frame() + partialTicks);
			}
			cir.setReturnValue((float) (ClientConfig.get().camera.fov + extra));
		}
	}
}
