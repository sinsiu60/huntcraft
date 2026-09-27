package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.VanillaPace;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적: 날씨 주기와 특수 스폰(팬텀 · 순찰대 · 떠돌이 상인 · 고양이)을 3틱에 1번만 돌려 1초 20틱 속도로.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPaceMixin {
	@Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
	private void huntcraft$weatherPace(CallbackInfo ci) {
		if (!VanillaPace.runs((ServerLevel) (Object) this)) {
			ci.cancel();
		}
	}

	@Inject(method = "tickCustomSpawners", at = @At("HEAD"), cancellable = true)
	private void huntcraft$customSpawnerPace(boolean spawnEnemies, CallbackInfo ci) {
		if (!VanillaPace.runs((ServerLevel) (Object) this)) {
			ci.cancel();
		}
	}
}
