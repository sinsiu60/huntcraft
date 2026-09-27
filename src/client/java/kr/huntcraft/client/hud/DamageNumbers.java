package kr.huntcraft.client.hud;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.net.HitDebugPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;

/**
 * 명중 대미지 숫자 — 내 칼날이 맞힌 자리에서 떠올라 사라짐. 흰색 = 보통, 주황 = 약점(육질 높음), 회색 = 튕김.
 * 시간은 실시간(초) — 서버 틱 정지 · 히트스톱과 상관없이 흐름.
 */
public final class DamageNumbers {
	public record Popup(Vec3 pos, float damage, boolean weak, boolean bounce, long startNanos) {}

	private static final List<Popup> POPUPS = new ArrayList<>();
	/** 시험: 지금까지 띄운 수 */
	private static int spawned;

	private DamageNumbers() {}

	public static void onHit(HitDebugPayload msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || msg.attackerId() != mc.player.getId() || msg.damage() < 0.0F) {
			return;
		}
		POPUPS.add(new Popup(new Vec3(msg.x(), msg.y(), msg.z()), msg.damage(), msg.weak(), msg.bounce(), System.nanoTime()));
		spawned++;
	}

	public static List<Popup> live() {
		return POPUPS;
	}

	public static int spawned() {
		return spawned;
	}

	static void render(GuiGraphicsExtractor g, Minecraft mc) {
		ClientConfig.Hud cfg = ClientConfig.get().hud;
		long now = System.nanoTime();
		Iterator<Popup> it = POPUPS.iterator();
		while (it.hasNext()) {
			Popup p = it.next();
			double age = (now - p.startNanos()) / 1.0E9 / cfg.damageNumberLifetime;
			if (age >= 1.0) {
				it.remove();
				continue;
			}
			double rise = cfg.damageNumberRise * (1.0 - (1.0 - age) * (1.0 - age));
			float[] s = Project.toScreen(mc, p.pos().add(0.0, 0.25 + rise, 0.0), g.guiWidth(), g.guiHeight());
			if (s == null) {
				continue;
			}
			int rgb = p.bounce() ? cfg.damageColorBounce : p.weak() ? cfg.damageColorWeak : cfg.damageColorNormal;
			int alpha = (int) (255 * Math.min(1.0, (1.0 - age) / 0.35));
			// 처음에 크게 튀었다가 제 크기로
			float scale = (float) (cfg.damageNumberScale * (1.0 + 0.45 * Math.max(0.0, 1.0 - age / 0.18)));
			String text = String.format(Locale.ROOT, "%d", Math.round(p.damage()));
			g.pose().pushMatrix();
			g.pose().translate(s[0], s[1]);
			g.pose().scale(scale, scale);
			g.centeredText(mc.font, text, 0, -4, (alpha << 24) | (rgb & 0xFFFFFF));
			g.pose().popMatrix();
		}
	}
}
