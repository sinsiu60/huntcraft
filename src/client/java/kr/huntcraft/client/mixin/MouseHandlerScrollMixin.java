package kr.huntcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import kr.huntcraft.client.input.ComboResolver;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.ScrollWheelHandler;
import org.joml.Vector2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 목적(입력): 게임 중 마우스 휠을 바닐라 핫바 선택 대신 HuntCraft 아이템 선택으로.
 * 화면(GUI)이 열려 있을 때의 스크롤은 이 지점 전에 처리되므로 영향 없음.
 * 휠 칸 수를 가져간 뒤 (0, 0) 을 돌려줘 바닐라의 핫바 · 관전 메뉴 · 비행 속도 변경이 일어나지 않게 합니다.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerScrollMixin {
	@WrapOperation(method = "onScroll",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/ScrollWheelHandler;onMouseScroll(DD)Lorg/joml/Vector2i;"))
	private Vector2i huntcraft$itemSelect(ScrollWheelHandler handler, double x, double y, Operation<Vector2i> original) {
		Vector2i steps = original.call(handler, x, y);
		ComboResolver.scrolled(steps.y);
		return new Vector2i(0, 0);
	}
}
