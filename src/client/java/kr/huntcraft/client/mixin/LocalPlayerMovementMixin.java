package kr.huntcraft.client.mixin;

import kr.huntcraft.client.camera.ShoulderCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적(입력): 바닐라 이동 부가 기능을 끔 — 달리기는 HunterMovement(Shift · 스태미나) 가 맡음.
 *   W 두 번 눌러 달리기(canStartSprinting) · 자동 점프(isAutoJumpEnabled)
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMovementMixin {
	@Inject(method = "canStartSprinting", at = @At("HEAD"), cancellable = true)
	private void huntcraft$noVanillaSprint(CallbackInfoReturnable<Boolean> cir) {
		if (ShoulderCamera.active(Minecraft.getInstance())) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isAutoJumpEnabled", at = @At("HEAD"), cancellable = true)
	private void huntcraft$noAutoJump(CallbackInfoReturnable<Boolean> cir) {
		if (ShoulderCamera.active(Minecraft.getInstance())) {
			cir.setReturnValue(false);
		}
	}
}
