package kr.huntcraft.client.hud;

import java.util.List;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.camera.LockOn;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.input.ComboResolver;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.combat.CombatInput;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.net.ItemSelectPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 플레이어 HUD (바닐라 HUD 대신, 소울류) — 매 프레임 동기화된 상태(HunterMovement.state)를 그대로 그림.
 *   좌상단: HP (빨강, 잃은 양은 잠시 노란 잔상) · FP (파랑, 전투 기술 확장용) · 스태미나 (초록, 지침 = 깜빡임)
 *   좌하단: 네 방향 장비칸 (위 기술 · 아래 소모품 + 개수 · 왼 왼손 · 오른 오른손 무기)
 *   하단 가운데: 보스 이름 + 체력바 (보스전 동안) · 넣은 대미지 누적 · 자세 게이지 (옵션) · 상호작용 안내
 *   화면 가운데: 쓰러짐 · 강적 격파 · 휴식 연출 · 락온 표시
 * 위치 · 크기 · 색은 client.json 의 hud. 바닐라 요소는 Fabric HudElementRegistry 로 빼서 Mixin 없음.
 */
public final class HuntHud {
	/** 시험 · 디버그: 마지막으로 그린 값 */
	public record Shown(double health, double trail, double stamina, boolean exhausted, int itemIndex, int itemCount,
						boolean lockOnMarker, int damageNumbers, long frame, boolean bossBar, String prompt, int banner) {}

	private static Shown shown;
	private static long frames;
	/** 맞은 뒤 줄어드는 잔상 (체력보다 크거나 같음) */
	private static double trailHealth = -1.0;
	private static double trailBoss = -1.0;
	/** 보스 체력바가 놓인 높이 (아래에서, 상호작용 안내가 그 위에 옴) */
	private static float bossBarY;
	private static long lastNanos;

	private HuntHud() {}

	public static void register() {
		if (ClientConfig.get().hud.hideVanilla) {
			for (Identifier id : new Identifier[] {VanillaHudElements.HOTBAR, VanillaHudElements.HEALTH_BAR, VanillaHudElements.FOOD_BAR,
					VanillaHudElements.ARMOR_BAR, VanillaHudElements.AIR_BAR, VanillaHudElements.EXPERIENCE_LEVEL, VanillaHudElements.INFO_BAR,
					VanillaHudElements.HELD_ITEM_TOOLTIP}) {
				HudElementRegistry.replaceElement(id, vanilla -> (g, dt) -> {
					if (!ShoulderCamera.active(Minecraft.getInstance())) {
						vanilla.extractRenderState(g, dt); // 관전 등 숄더뷰가 아닐 때는 바닐라 그대로
					}
				});
			}
		}
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HuntCraft.id("hunter_hud"), HuntHud::render);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			trailHealth = -1.0;
			sendItem(HunterMovement.state().itemIndex);
		});
		// 휠 = 아이템 고르기 (클라이언트가 정하고 서버에 알림)
		ComboResolver.listen(in -> {
			if (in == CombatInput.ITEM_PREV || in == CombatInput.ITEM_NEXT) {
				HunterState st = HunterMovement.state();
				int n = CommonConfig.get().items.size();
				if (n > 0) {
					st.itemIndex = Math.floorMod(st.itemIndex + (in == CombatInput.ITEM_NEXT ? 1 : -1), n);
					sendItem(st.itemIndex);
				}
			}
		});
	}

	private static void sendItem(int index) {
		if (ClientPlayNetworking.canSend(ItemSelectPayload.TYPE)) {
			ClientPlayNetworking.send(new ItemSelectPayload(index));
		}
	}

	public static Shown shown() {
		return shown;
	}


	private static void render(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !ShoulderCamera.active(mc) || mc.gui.hud.isHidden()) {
			return;
		}
		HunterState st = HunterMovement.state();
		ClientConfig.Hud cfg = ClientConfig.get().hud;
		long now = System.nanoTime();
		double dtSec = lastNanos == 0 ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		if (trailHealth < st.health) {
			trailHealth = st.health;
		} else {
			double max = CommonConfig.get().player.maxHealth;
			trailHealth = Math.max(st.health, trailHealth - max * dtSec / Math.max(0.05, cfg.damageTrailSeconds));
		}
		status(g, st, cfg);
		int[] item = equipment(g, mc, st, cfg);
		boolean marker = lockOn(g, mc, cfg);
		if (cfg.damageNumbersInWorld) {
			DamageNumbers.render(g, mc);
		}
		boolean bar = bossBar(g, mc, cfg, dtSec);
		String prompt = ClientProgress.prompt(mc.player);
		if (prompt != null && ClientProgress.bannerKind() < 0 || prompt != null && ClientProgress.bannerAge() > bannerLength(cfg)) {
			String text = "[" + kr.huntcraft.client.input.HuntKeys.interact.getTranslatedKeyMessage().getString() + "] " + prompt;
			g.centeredText(mc.font, text, g.guiWidth() / 2, (int) (g.guiHeight() - Math.max(cfg.bossBar.y, bossBarY) - 42), cfg.colorPrompt);
		}
		int banner = banner(g, mc, cfg);
		shown = new Shown(st.health, trailHealth, st.stamina, st.exhausted, item[0], item[1], marker, DamageNumbers.live().size(), ++frames, bar, prompt,
				banner);
	}

	// ── 좌상단 ────────────────────────────────────────────

	private static void status(GuiGraphicsExtractor g, HunterState st, ClientConfig.Hud cfg) {
		CommonConfig common = CommonConfig.get();
		g.pose().pushMatrix();
		g.pose().translate(cfg.status.x, cfg.status.y);
		g.pose().scale((float) cfg.status.scale, (float) cfg.status.scale);
		int y = 0;
		// HP: 뒤판 → 노란 잔상 (잃은 양) → 빨강
		double maxHp = common.player.maxHealth;
		int w = (int) Math.round(maxHp * cfg.pxPerHealth);
		int h = cfg.healthHeight;
		frame(g, 0, y, w, h, cfg.colorFrame, cfg.colorBarBack);
		g.fill(1, y + 1, 1 + px(trailHealth / maxHp, w - 2), y + h - 1, cfg.colorDamageTrail);
		g.fill(1, y + 1, 1 + px(st.health / maxHp, w - 2), y + h - 1, cfg.colorHealth);
		g.fill(1, y + 1, 1 + px(st.health / maxHp, w - 2), y + 2, 0x30FFFFFF);
		y += h + cfg.barGap;
		// FP (전투 기술 확장용 — 지금은 늘 가득)
		double maxFp = common.player.maxFp;
		int fw = (int) Math.round(maxFp * cfg.pxPerFp);
		frame(g, 0, y, fw, cfg.fpHeight, cfg.colorFrame, cfg.colorBarBack);
		g.fill(1, y + 1, fw - 1, y + cfg.fpHeight - 1, cfg.colorFp);
		y += cfg.fpHeight + cfg.barGap;
		// 스태미나
		double maxSt = common.stamina.max;
		int sw = (int) Math.round(maxSt * cfg.pxPerStamina);
		int sh = cfg.staminaHeight;
		frame(g, 0, y, sw, sh, cfg.colorFrame, cfg.colorBarBack);
		int stColor = cfg.colorStamina;
		if (st.exhausted) {
			boolean blink = (System.currentTimeMillis() / 180) % 2 == 0;
			stColor = blink ? cfg.colorStaminaExhausted : dim(cfg.colorStaminaExhausted, 0.6);
		}
		g.fill(1, y + 1, 1 + px(st.stamina / maxSt, sw - 2), y + sh - 1, stColor);
		g.pose().popMatrix();
	}

	// ── 좌하단: 네 방향 장비칸 ─────────────────────────────

	/** 위 = 기술 (비었음) · 아래 = 고른 소모품 (개수 · 이름) · 왼 = 왼손 (비었음) · 오른 = 오른손 무기. 반환 [고른 번호, 개수] */
	private static int[] equipment(GuiGraphicsExtractor g, Minecraft mc, HunterState st, ClientConfig.Hud cfg) {
		List<CommonConfig.ItemDef> items = CommonConfig.get().items;
		int n = items.size();
		int sel = n == 0 ? -1 : Math.floorMod(st.itemIndex, n);
		float s = (float) cfg.equipment.scale;
		int half = 11;
		int gap = 24;
		g.pose().pushMatrix();
		// 십자 가운데가 좌하단 모서리에서 (x + 칸 하나, y + 칸 하나) 안쪽
		g.pose().translate(cfg.equipment.x + (gap + half) * s, g.guiHeight() - cfg.equipment.y - (gap + half) * s);
		g.pose().scale(s, s);
		emptySlot(g, 0, -gap, half);                                   // 위: 기술 · 주문
		emptySlot(g, -gap, 0, half);                                   // 왼: 왼손
		iconSlot(g, stack(cfg.weaponIcon), gap, 0, half, 0xFFD8CCA8);   // 오른: 오른손 무기 (태도)
		int count = 0;
		if (sel >= 0) {
			CommonConfig.ItemDef d = items.get(sel);
			count = st.count(sel);
			iconSlot(g, stack(d.icon), 0, gap, half, 0xFFF0E6C8);
			if (count == 0) {
				g.fill(-half + 1, gap - half + 1, half - 1, gap + half - 1, 0x90000000);
			}
			if (count >= 0) {
				String c = String.valueOf(count);
				g.text(mc.font, c, half - mc.font.width(c) - 1, gap + half - 9, count == 0 ? 0xFFE04040 : 0xFFFFFFFF, true);
			}
			g.text(mc.font, d.name, half + 4, gap + half - 9, 0xFFE8DCC0, true);
		} else {
			emptySlot(g, 0, gap, half);
		}
		g.pose().popMatrix();
		return new int[] {sel, count};
	}

	private static void emptySlot(GuiGraphicsExtractor g, int cx, int cy, int half) {
		frame(g, cx - half, cy - half, half * 2, half * 2, 0xFF4A4438, 0x90101010);
	}

	private static void iconSlot(GuiGraphicsExtractor g, ItemStack stack, int cx, int cy, int half, int border) {
		frame(g, cx - half, cy - half, half * 2, half * 2, border, 0xB0101010);
		float scale = half * 2 / 18.0F;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(scale, scale);
		g.item(stack, -8, -8);
		g.pose().popMatrix();
	}

	// ── 하단 가운데: 보스 체력바 ───────────────────────────

	private static boolean bossBar(GuiGraphicsExtractor g, Minecraft mc, ClientConfig.Hud cfg, double dtSec) {
		MonsterEntity m = ClientProgress.boss();
		if (m == null) {
			trailBoss = -1.0;
			return false;
		}
		double max = Math.max(1.0, m.maxHp());
		double hp = Math.max(0.0, m.hp());
		if (trailBoss < hp) {
			trailBoss = hp;
		} else {
			trailBoss = Math.max(hp, trailBoss - max * dtSec / Math.max(0.05, cfg.bossTrailSeconds));
		}
		float s = (float) cfg.bossBar.scale;
		// 작은 화면: 길이를 화면 폭 bossBarMaxShare 로 줄이고, 그래도 좌하단 장비칸과 겹치면 장비칸 위로 올림
		int w = (int) Math.min(cfg.bossBarWidth, g.guiWidth() * cfg.bossBarMaxShare / s);
		int h = cfg.bossBarHeight;
		float left = g.guiWidth() / 2.0F - w * s / 2.0F + cfg.bossBar.x;
		float es = (float) cfg.equipment.scale;
		float equipRight = cfg.equipment.x + (24 * 2 + 11 * 2 + 70) * es;
		float equipTop = cfg.equipment.y + (24 * 2 + 11 * 2) * es;
		float y = left < equipRight ? Math.max(cfg.bossBar.y, equipTop + 16) : cfg.bossBar.y;
		bossBarY = y;
		g.pose().pushMatrix();
		g.pose().translate(left, g.guiHeight() - y);
		g.pose().scale(s, s);
		// 이름 (왼쪽 위) · 넣은 대미지 (오른쪽 위, 한동안 안 맞히면 사라짐)
		g.text(mc.font, ClientProgress.bossName(), 0, -11, 0xFFE8DCC0, true);
		double since = ClientProgress.sinceDamage();
		if (ClientProgress.damageSum() > 0.0 && since < cfg.bossDamageKeepSeconds) {
			String d = String.valueOf((int) Math.round(ClientProgress.damageSum()));
			int a = (int) (255 * Math.min(1.0, (cfg.bossDamageKeepSeconds - since) / 0.4));
			g.text(mc.font, d, w - mc.font.width(d), -11, (Math.max(4, a) << 24) | (cfg.colorBossDamage & 0xFFFFFF), true);
		}
		frame(g, 0, 0, w, h, cfg.colorFrame, cfg.colorBarBack);
		g.fill(1, 1, 1 + px(trailBoss / max, w - 2), h - 1, cfg.colorDamageTrail);
		g.fill(1, 1, 1 + px(hp / max, w - 2), h - 1, cfg.colorBoss);
		// 자세 게이지 (옵션): 체력바 아래 가는 줄, 가운데에서 양쪽으로 참
		if (cfg.showBossStance && m.def() != null) {
			double r = Math.min(1.0, m.stance() / Math.max(1.0, m.def().stanceMax));
			int half = px(r, w / 2);
			g.fill(w / 2 - half, h + 2, w / 2 + half, h + 4, cfg.colorStance);
		}
		g.pose().popMatrix();
		return true;
	}

	// ── 화면 가운데: 큰 글씨 연출 ────────────────────────────

	private static double bannerLength(ClientConfig.Hud cfg) {
		return cfg.bannerFadeIn + cfg.bannerHold + cfg.bannerFadeOut;
	}

	/** 쓰러짐 · 강적 격파 · 휴식 — 가로 띠 위에 큰 글씨 (들어옴 → 머묾 → 사라짐). 반환 = 지금 그린 종류 (-1 없음) */
	private static int banner(GuiGraphicsExtractor g, Minecraft mc, ClientConfig.Hud cfg) {
		int kind = ClientProgress.bannerKind();
		double age = ClientProgress.bannerAge();
		if (kind < 0 || age > bannerLength(cfg)) {
			return -1;
		}
		double a = age < cfg.bannerFadeIn ? age / cfg.bannerFadeIn
				: age < cfg.bannerFadeIn + cfg.bannerHold ? 1.0 : Math.max(0.0, 1.0 - (age - cfg.bannerFadeIn - cfg.bannerHold) / cfg.bannerFadeOut);
		String text = switch (kind) {
			case kr.huntcraft.net.BannerPayload.DEATH -> cfg.textDeath;
			case kr.huntcraft.net.BannerPayload.VICTORY -> cfg.textVictory;
			default -> cfg.textRest;
		};
		int color = switch (kind) {
			case kr.huntcraft.net.BannerPayload.DEATH -> cfg.colorDeath;
			case kr.huntcraft.net.BannerPayload.VICTORY -> cfg.colorVictory;
			default -> cfg.colorRest;
		};
		float scale = (float) (kind == kr.huntcraft.net.BannerPayload.REST ? cfg.bannerScale * 0.6 : cfg.bannerScale);
		int cy = (int) (g.guiHeight() * 0.45);
		int band = (int) (18 * scale);
		// 띠: 가운데가 진하고 위아래로 옅어짐
		for (int i = -band; i < band; i++) {
			double f = 1.0 - Math.abs(i) / (double) band;
			int alpha = (int) (170 * f * a);
			g.fill(0, cy + i, g.guiWidth(), cy + i + 1, alpha << 24);
		}
		int ta = Math.max(4, (int) (255 * a));
		g.pose().pushMatrix();
		g.pose().translate(g.guiWidth() / 2.0F, cy - 4 * scale);
		// 쓰러짐 글씨는 천천히 조금 커짐 (엘든링처럼)
		float grow = kind == kr.huntcraft.net.BannerPayload.DEATH ? (float) (1.0 + 0.04 * age) : 1.0F;
		g.pose().scale(scale * grow, scale * grow);
		g.centeredText(mc.font, text, 0, 0, (ta << 24) | (color & 0xFFFFFF));
		g.pose().popMatrix();
		return kind;
	}

	private static ItemStack stack(String icon) {
		try {
			Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(icon));
			return new ItemStack(item);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	// ── 락온 표시 ─────────────────────────────────────────

	private static boolean lockOn(GuiGraphicsExtractor g, Minecraft mc, ClientConfig.Hud cfg) {
		MonsterEntity m = LockOn.target();
		if (m == null) {
			return false;
		}
		float[] p = Project.toScreen(mc, LockOn.center(m), g.guiWidth(), g.guiHeight());
		if (p == null) {
			return false;
		}
		int x = Math.round(p[0]);
		int y = Math.round(p[1]);
		int r = cfg.lockOnSize;
		int c = cfg.lockOnColor;
		// 작은 원 (점을 둘러 찍음) + 가운데 점
		int dots = Math.max(12, r * 3);
		for (int i = 0; i < dots; i++) {
			double a = i * Math.PI * 2.0 / dots;
			int px = x + (int) Math.round(Math.cos(a) * r);
			int py = y + (int) Math.round(Math.sin(a) * r);
			g.fill(px, py, px + 1, py + 1, c);
		}
		g.fill(x - 1, y - 1, x + 1, y + 1, c);
		return true;
	}

	// ── 도우미 ──────────────────────────────────────────

	private static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int back) {
		g.fill(x, y, x + w, y + h, back);
		g.outline(x, y, w, h, border);
	}

	private static int px(double ratio, int width) {
		return (int) Math.round(Math.max(0.0, Math.min(1.0, ratio)) * width);
	}

	private static int dim(int argb, double f) {
		int r = (int) (((argb >> 16) & 0xFF) * f);
		int gr = (int) (((argb >> 8) & 0xFF) * f);
		int b = (int) ((argb & 0xFF) * f);
		return (argb & 0xFF000000) | (r << 16) | (gr << 8) | b;
	}
}
