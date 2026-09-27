package kr.huntcraft.client.mixin;

import kr.huntcraft.client.camera.ShoulderCamera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적(카메라): 게임 중에는 1인칭 · 정면 3인칭을 쓰지 않음 — 항상 "뒤에서 보는 3인칭" 으로 답해서
 * 캐릭터 모델이 그려지고 1인칭 손이 사라지게 합니다. 실제 위치는 CameraMixin 의 숄더뷰. F5 는 효과 없음.
 * 옵션 저장값 자체는 건드리지 않아 관전 · 모드 제거 시 원래 시점이 돌아옵니다.
 */
@Mixin(Options.class)
public abstract class OptionsCameraTypeMixin {
	@Inject(method = "getCameraType", at = @At("HEAD"), cancellable = true)
	private void huntcraft$alwaysShoulder(CallbackInfoReturnable<CameraType> cir) {
		Minecraft mc = Minecraft.getInstance();
		if (mc != null && ShoulderCamera.active(mc)) {
			cir.setReturnValue(CameraType.THIRD_PERSON_BACK);
		}
	}
}
