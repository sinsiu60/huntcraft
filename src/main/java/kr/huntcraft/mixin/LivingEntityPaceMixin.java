package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.VanillaPace;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적 1: 상태 효과(지속시간 감소 · 재생/독 발동 · 효과 입자)를 3틱에 1번만 돌려 1초 20틱 속도로. 서버 · 클라이언트 모두.
 * 목적 2: 최소 속도 문턱 — 바닐라는 틱마다 속도가 0.003 칸/틱보다 작으면 0 으로 자르는데, 60틱에서는 틱당 속도가 1/3 이라
 *         느린 움직임이 통째로 잘립니다. 문턱도 1/3 로.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPaceMixin {
	@Inject(method = "tickEffects", at = @At("HEAD"), cancellable = true)
	private void huntcraft$effectPace(CallbackInfo ci) {
		if (!VanillaPace.runs(((LivingEntity) (Object) this).tickCount)) {
			ci.cancel();
		}
	}

	@ModifyConstant(method = "aiStep", constant = @Constant(doubleValue = 0.003))
	private double huntcraft$minVelocity(double value) {
		return value / VanillaPace.RATIO;
	}
}
