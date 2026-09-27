package kr.huntcraft.client.debug;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.core.FixedTick;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;


/**
 * F8 디버그 정보 — Phase 0: 틱 주기 실측 · 입력 확인.
 * Phase 3 이후 현재 애니메이션 이름 · 프레임 번호 · 활성 판정 구간이 여기에 추가됩니다.
 */
public final class DebugOverlay {
	private static int ticksThisSecond;
	private static long secondStart = System.nanoTime();
	private static int measuredHz;
	/** 플레이어 수평 속도 (블록/초) — 바닐라 걷기 약 4.32, 달리기 약 5.61 이면 속도 보정 정상 */
	private static double horizontalSpeed;
	private static double lastX;
	private static double lastZ;

	private DebugOverlay() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			DebugState.clientTick++;
			if (mc.player != null) {
				double dx = mc.player.getX() - lastX;
				double dz = mc.player.getZ() - lastZ;
				double perSecond = Math.sqrt(dx * dx + dz * dz) * FixedTick.HZ;
				// 순간값이 튀지 않게 약 0.25초 지수 평균
				horizontalSpeed += (perSecond - horizontalSpeed) * 0.065;
				lastX = mc.player.getX();
				lastZ = mc.player.getZ();
			}
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

	private static kr.huntcraft.monster.MonsterEntity nearestMonster(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return null;
		}
		kr.huntcraft.monster.MonsterEntity best = null;
		double bestD = 64.0 * 64.0;
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof kr.huntcraft.monster.MonsterEntity m && e.distanceToSqr(mc.player) < bestD) {
				bestD = e.distanceToSqr(mc.player);
				best = m;
			}
		}
		return best;
	}

	private static kr.huntcraft.collision.ColliderOwner nearestOwner(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return null;
		}
		kr.huntcraft.collision.ColliderOwner best = null;
		double bestD = 32.0 * 32.0;
		for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof kr.huntcraft.collision.ColliderOwner o && e.distanceToSqr(mc.player) < bestD) {
				bestD = e.distanceToSqr(mc.player);
				best = o;
			}
		}
		return best;
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		if (!DebugState.showInfo) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		int color = 0xFF000000 | ClientConfig.get().hud.debugTextColor;
		float serverRate = mc.level != null ? mc.level.tickRateManager().tickrate() : 0.0F;
		java.util.List<String> lines = new java.util.ArrayList<>();
		lines.add("[HuntCraft 디버그]");
		lines.add("로직 " + FixedTick.HZ + "Hz · 서버 틱레이트 " + serverRate + " · 클라이언트 실측 " + measuredHz + " tick/s");
		lines.add(String.format("수평 속도 %.2f 블록/s (목표 모델 %.2f)", horizontalSpeed, HunterMovement.horizontalSpeed()));
		HunterState st = HunterMovement.state();
		lines.add(String.format("스태미나 %.1f%s · 달리기 %s%s", st.stamina,
				st.exhausted ? " (지침)" : "", st.sprinting ? "예" : (kr.huntcraft.client.input.ComboResolver.sprintHeld() ? "키만" : "-"),
				st.guarding ? " · §9가드§r" : ""));
		if (mc.player != null) {
			lines.add(String.format("몸 %.0f° · 입력 방향 %.0f° · 카메라 %.0f°/%.0f° · 카메라 지연 %.2f · 거리 %.2f",
					net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()), HunterMovement.desiredYaw(),
					ShoulderCamera.yaw(), ShoulderCamera.pitch(), ShoulderCamera.lag(mc), ShoulderCamera.distance()));
		}
		lines.add("판정 도형(F7): " + (DebugState.showShapes ? "켜짐" : "꺼짐") + " · 이번 프레임 도형 " + ShapeDebugRenderer.drawnShapes() + "개");
		kr.huntcraft.collision.ColliderOwner near = nearestOwner(mc);
		if (near != null) {
			double t = near.animTime(delta.getGameTimeDeltaPartialTick(false));
			kr.huntcraft.collision.ColliderDef def = near.colliderDef();
			StringBuilder act = new StringBuilder();
			if (def != null) {
				for (kr.huntcraft.collision.ColliderDef.Box h : def.hitboxes) {
					act.append(h.id()).append(near.hitboxActive(h.id(), t) ? "[켜짐] " : "[꺼짐] ");
				}
			}
			lines.add(String.format("가까운 판정: %s · 시간 %.1f틱 · 인스턴스 #%d · %s· 명중 %d",
					near.self().getType().getDescription().getString(), t, near.attackInstance(t), act, ShapeDebugRenderer.hits(near.self().getId())));
		}
		kr.huntcraft.monster.MonsterEntity mon = nearestMonster(mc);
		if (mon != null) {
			kr.huntcraft.monster.MonsterDef.Action a = mon.action();
			double f = mon.frame(delta.getGameTimeDeltaPartialTick(false));
			String hitInfo = "";
			if (a != null) {
				StringBuilder hb = new StringBuilder();
				for (kr.huntcraft.monster.MonsterDef.Hit h : a.hits()) {
					hb.append(f >= h.from() && f <= h.to() ? "§c" : "§7").append('[').append(h.from()).append('-').append(h.to()).append("]§r ");
				}
				hitInfo = hb.toString();
			}
			kr.huntcraft.monster.MonsterDef md = mon.def();
			lines.add(String.format("몬스터: %s · %s %.0f/%d · 판정 %s· 체력 %.0f/%.0f%s%s · 강인도 %.0f/%.0f · 자세 %.0f/%.0f · 속도 x%.2f · 거리 %.1f",
					md == null ? "-" : md.displayName, a == null ? "-" : a.id(), f, a == null ? 0 : a.length(), hitInfo,
					mon.hp(), mon.maxHp(), mon.enraged() ? " §c분노§r" : "", mon.dead() ? " §8처치§r" : "", mon.poise(), md == null ? 0 : md.poiseMax,
					mon.stance(), md == null ? 0 : md.stanceMax, mon.speedScale(), mon.distanceTo(mc.player)));
			if (md != null) {
				lines.add(String.format("근접 압박 %s%.0f§r / %.0f (임계 %.0f) · 윤곽 거리 %.2f", mon.pressure() >= md.pressure.threshold() ? "§c" : "§f",
						mon.pressure(), md.pressure.max(), md.pressure.threshold(), mon.zoneDistance(mc.player)));
			}
		}
		lines.add(String.format("헌터 체력 %.0f / %.0f", st.health, kr.huntcraft.config.CommonConfig.get().player.maxHealth));
		kr.huntcraft.net.HitDebugPayload lh = ShapeDebugRenderer.lastHit();
		if (lh != null) {
			lines.add(String.format("마지막 명중: 부위 %s (%s) · TOI %.3f · 서브스텝 %d", lh.part(), lh.hurtbox(), lh.toi(), lh.substeps()));
		}
		kr.huntcraft.combat.HunterCombat combat = kr.huntcraft.client.combat.ClientHunters.local();
		kr.huntcraft.combat.MoveSet.Motion m = combat.current();
		if (m != null) {
			int f = combat.frame();
			StringBuilder hits = new StringBuilder();
			for (kr.huntcraft.combat.MoveSet.Hit h : m.hits()) {
				hits.append(f >= h.from() && f <= h.to() ? "§c" : "§7").append('[').append(h.from()).append('-').append(h.to()).append("]§r ");
			}
			double ha = combat.hyperArmorBonus(f);
			lines.add(String.format("모션 %s (%s) · 프레임 %d/%d · 판정 %s· 캔슬 %s · 회피 %s%s%s%s%s", m.id(), String.valueOf(m.animation()).replace("animation.huntcraft.", ""),
					f, m.length(), hits, f >= m.cancel() ? "§a열림§r" : m.cancel() + "~", f >= m.evadeCancel() ? "§a열림§r" : m.evadeCancel() + "~",
					combat.hitstop() > 0 ? " · §e히트스톱 " + combat.hitstop() + "§r" : "",
					combat.invulnerable(f) ? " · §e무적§r" : "", ha >= 0.0 ? String.format(" · §6슈퍼아머 %.0f§r", ha) : "",
					combat.heavyHeld() && m.x().chargeMax() > 0 ? " · §d차지 중§r" : ""));
		} else {
			lines.add("모션: 대기" + (combat.comboLeft() > 0
					? String.format(" · §a콤보 유지 %s %d프레임§r", combat.comboFrom(), combat.comboLeft()) : ""));
		}
		lines.add("선입력: " + (combat.buffered() == null ? "-" : combat.buffered().label));
		kr.huntcraft.net.HitDebugPayload own = kr.huntcraft.client.combat.ClientHunters.lastOwnHit();
		if (own != null) {
			lines.add(String.format("내 마지막 명중: %s · 대미지 %s%.1f§r%s", own.part(), own.weak() ? "§6" : "§f", own.damage(), own.bounce() ? " §7(튕김)" : ""));
		}
		lines.add("입력 (최근 순, 앞 입력과의 간격 프레임):");
		java.util.List<DebugState.Entry> entries = new java.util.ArrayList<>(DebugState.history);
		for (int i = 0; i < entries.size(); i++) {
			DebugState.Entry e = entries.get(i);
			DebugState.Entry older = i + 1 < entries.size() ? entries.get(i + 1) : null;
			String gap = older == null ? "" : "  +" + (e.tick() - older.tick()) + "f";
			lines.add("  " + e.text() + gap);
		}
		int y = 4;
		for (String line : lines) {
			g.text(mc.font, line, 4, y, color);
			y += 10;
		}
	}
}
