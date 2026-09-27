package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.VanillaPace;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적: 걷기 팔다리 흔들림 — 바닐라는 틱마다 "이번 틱 이동거리 x 4" 를 목표 세기로 잡고 위치를 그만큼 넘깁니다.
 * 60틱이면 틱당 이동거리가 1/3 이라 흔들림이 작고 빨라지므로, 세기는 1초 기준으로 되돌리고 위치 · 수렴 속도는 틱 비율만큼 나눕니다.
 */
@Mixin(WalkAnimationState.class)
public abstract class WalkAnimationStateMixin {
	@Shadow
	private float speedOld;
	@Shadow
	private float speed;
	@Shadow
	private float position;
	@Shadow
	private float positionScale;

	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void huntcraft$perSecond(float targetSpeed, float factor, float positionScale, CallbackInfo ci) {
		double k = VanillaPace.RATIO;
		float target = Math.min(1.0F, (float) (targetSpeed * k));
		float f = (float) (1.0 - Math.pow(1.0 - factor, 1.0 / k));
		this.speedOld = this.speed;
		this.speed = this.speed + (target - this.speed) * f;
		this.position = this.position + (float) (this.speed / k);
		this.positionScale = positionScale;
		ci.cancel();
	}
}
