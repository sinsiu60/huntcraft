package kr.huntcraft.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.TickRateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적: 클라이언트 틱을 서버 틱레이트(60)에 맞춤.
 * 바닐라 클라이언트는 max(50ms, 서버 1틱) 으로 돌아서 서버를 60틱으로 올려도 1초 20틱에 머뭅니다.
 * 이걸 풀어야 입력 · 이동 · 위치 전송이 1/60초 단위가 되어 60Hz 판정과 맞습니다.
 * 서버가 멈춤(/tick freeze) · 한 틱씩 진행 중이면 바닐라 동작 그대로.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftTickRateMixin {
	@Inject(method = "getTickTargetMillis", at = @At("RETURN"), cancellable = true)
	private void huntcraft$followServerTickRate(float defaultTickTargetMillis, CallbackInfoReturnable<Float> cir) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.level == null) {
			return;
		}
		TickRateManager manager = mc.level.tickRateManager();
		if (manager.runsNormally()) {
			cir.setReturnValue(manager.millisecondsPerTick());
		}
	}
}
