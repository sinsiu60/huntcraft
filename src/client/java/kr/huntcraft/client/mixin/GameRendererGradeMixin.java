package kr.huntcraft.client.mixin;

import kr.huntcraft.client.config.ClientConfig;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적(렌더): 소울류 색 보정 (huntcraft:souls_grade — 채도를 빼고 칙칙하게) 을 바닐라의 "화면 효과" 자리에 늘 걸어 둠.
 * 바닐라는 카메라 엔티티가 바뀔 때 (월드 접속 · 관전) checkEntityPostEffect 로 효과를 정하고, 특별한 엔티티가 아니면 비움 —
 * 그 뒤에 비어 있으면 우리 효과를 넣음. 크리퍼 · 거미 관전처럼 바닐라 효과가 있으면 그쪽이 우선.
 * 화면 효과는 월드에만 걸리고 HUD 는 그 뒤에 그려져서 HUD 색은 그대로. ClientConfig.grade.enabled 로 끔.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererGradeMixin {
	private static final Identifier HUNTCRAFT_GRADE = Identifier.fromNamespaceAndPath("huntcraft", "souls_grade");

	@Shadow
	private Identifier postEffectId;

	@Shadow
	private boolean effectActive;

	@Shadow
	protected abstract void setPostEffect(Identifier id);

	@Inject(method = "checkEntityPostEffect", at = @At("TAIL"))
	private void huntcraft$grade(Entity entity, CallbackInfo ci) {
		boolean on = ClientConfig.get().grade.enabled;
		if (on && postEffectId == null) {
			setPostEffect(HUNTCRAFT_GRADE);
		} else if (!on && HUNTCRAFT_GRADE.equals(postEffectId)) {
			postEffectId = null;
			effectActive = false;
		}
	}
}
