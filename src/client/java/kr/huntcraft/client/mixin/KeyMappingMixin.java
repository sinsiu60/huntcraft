package kr.huntcraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import kr.huntcraft.client.input.HuntKeys;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적(입력): HuntCraft 키와 같은 물리 키에 묶인 바닐라(및 다른 모드) 동작을 없앰.
 * 게임 코드가 키 상태를 읽는 두 입구(isDown · consumeClick)에서 "안 눌림" 으로 답하므로
 * 점프 · 웅크리기 · 달리기(토글 포함) · 공격/채굴 · 사용 · 인벤토리 · 손 바꾸기 · 목록 이 모두 멈춥니다.
 * 키 상태 자체는 건드리지 않아, 바닐라 동작을 다른 키로 옮기면 바로 다시 동작합니다.
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {
	@Shadow
	protected InputConstants.Key key;
	@Shadow
	private int clickCount;

	@Inject(method = "isDown", at = @At("HEAD"), cancellable = true)
	private void huntcraft$suppressDown(CallbackInfoReturnable<Boolean> cir) {
		if (huntcraft$overridden()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "consumeClick", at = @At("HEAD"), cancellable = true)
	private void huntcraft$suppressClick(CallbackInfoReturnable<Boolean> cir) {
		if (huntcraft$overridden()) {
			this.clickCount = 0;
			cir.setReturnValue(false);
		}
	}

	private boolean huntcraft$overridden() {
		KeyMapping self = (KeyMapping) (Object) this;
		return !HuntKeys.isOurs(self) && HuntKeys.owns(this.key);
	}
}
