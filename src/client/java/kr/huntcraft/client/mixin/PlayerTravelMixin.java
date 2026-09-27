package kr.huntcraft.client.mixin;

import kr.huntcraft.client.movement.HunterMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 목적(이동): 내 플레이어의 수평 이동을 HunterMovement 로 대체 (카메라 기준 입력 · 몸 회전 제한 · 블록/초 속도).
 * 바닐라 travel 직전에 속도를 정해 두고 바닐라에는 입력 0 을 넘기므로, 충돌 · 중력 · 계단 · 물은 바닐라가 처리합니다.
 * 클라이언트 전용 Mixin (다른 플레이어 · 서버 쪽 Player 는 바닐라).
 */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {
	@ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
	private Vec3 huntcraft$hunterMovement(Vec3 input) {
		if ((Object) this instanceof LocalPlayer local) {
			return HunterMovement.travel(local, input);
		}
		return input;
	}
}
