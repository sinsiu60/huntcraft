package kr.huntcraft.client.debug;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.core.FixedTick;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.language.I18n;

/**
 * F8 디버그 정보 — Phase 0: 틱 주기 실측 · 입력 확인.
 * Phase 3 이후 현재 애니메이션 이름 · 프레임 번호 · 활성 판정 구간이 여기에 추가됩니다.
 */
public final class DebugOverlay {
	private static int ticksThisSecond;
	private static long secondStart = System.nanoTime();
	private static int measuredHz;

	private DebugOverlay() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			ticksThisSecond++;
			long now = System.nanoTime();
			if (now - secondStart >= 1_000_000_000L) {
				measuredHz = ticksThisSecond;
				ticksThisSecond = 0;
				secondStart = now;
			}
		});
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, HuntCraft.id("debug_info"), DebugOverlay::render);
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!DebugState.showInfo) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int color = 0xFF000000 | ClientConfig.get().hud.debugTextColor;
		float serverRate = mc.level != null ? mc.level.tickRateManager().tickrate() : 0.0F;
		String input = DebugState.lastNote.startsWith("key.") ? I18n.get(DebugState.lastNote) : DebugState.lastNote;
		if (DebugState.lastNoteAt != 0) {
			input += String.format(" (%.1fs 전)", (System.currentTimeMillis() - DebugState.lastNoteAt) / 1000.0);
		}
		String[] lines = {
				"[HuntCraft 디버그]",
				"로직 " + FixedTick.HZ + "Hz · 서버 틱레이트 " + serverRate + " · 클라이언트 실측 " + measuredHz + " tick/s",
				"판정 도형(F7): " + (DebugState.showShapes ? "켜짐" : "꺼짐") + "  (Phase 2 에서 렌더)",
				"마지막 입력: " + input,
				"애니메이션 / 프레임 / 활성 판정: - (Phase 3)",
		};
		int y = 4;
		for (String line : lines) {
			g.text(mc.font, line, 4, y, color);
			y += 10;
		}
	}
}
