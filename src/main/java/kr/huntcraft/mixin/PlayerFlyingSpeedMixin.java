package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.MovementScale;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적: 공중 가속 보정 — 플레이어의 공중 · 비행 가속(0.02 등)은 속성이 아닌 고정값이라 60틱에서 공중 이동이 3배 빨라집니다.
 * MovementScale 과 같은 기준으로 줄입니다. 서버 · 클라이언트 모두 (플레이어 이동은 클라이언트가 계산).
 */
@Mixin(Player.class)
public abstract class PlayerFlyingSpeedMixin {
	@Inject(method = "getFlyingSpeed", at = @At("RETURN"), cancellable = true)
	private void huntcraft$airAccelPace(CallbackInfoReturnable<Float> cir) {
		cir.setReturnValue((float) (cir.getReturnValue() * MovementScale.factors().airAccel()));
	}
}
