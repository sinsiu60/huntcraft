package kr.huntcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.huntcraft.client.camera.ShoulderCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 목적(입력/카메라): 마우스 이동이 플레이어 몸을 돌리지 않고 숄더뷰 카메라만 돌리게 함.
 * 감도 · 마우스 반전 · 부드러운 카메라 옵션은 바닐라가 계산한 값을 그대로 받습니다.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerTurnMixin {
	@WrapOperation(method = "turnPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
	private void huntcraft$turnCamera(LocalPlayer player, double dx, double dy, Operation<Void> original) {
		if (ShoulderCamera.active(Minecraft.getInstance())) {
			ShoulderCamera.turn(player, dx, dy);
		} else {
			original.call(player, dx, dy);
		}
	}
}
