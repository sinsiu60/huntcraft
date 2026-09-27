package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.VanillaPace;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적: 허기 · 자연 회복 · 굶주림 피해 타이머를 3틱에 1번만 돌려 1초 20틱 속도로 (그대로 두면 회복이 3배 빠름).
 */
@Mixin(FoodData.class)
public abstract class FoodDataPaceMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void huntcraft$foodPace(ServerPlayer player, CallbackInfo ci) {
		if (!VanillaPace.runs(player.tickCount)) {
			ci.cancel();
		}
	}
}
