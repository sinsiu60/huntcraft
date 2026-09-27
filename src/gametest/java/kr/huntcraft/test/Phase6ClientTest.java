package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.collision.HitLog;
import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 6 시험 — 완료 조건: parryable 공격을 패링하면 치명 일격이 발동하고, 가드로 버티다 스태미나가 바닥나면 가드 붕괴.
 *   골렘 (행동 끔) 의 휘두르기 (패링 가능) 를 F 로 쳐냄 → 골렘 크게 휘청 (치명 구간) → 좌클릭 = 치명 일격 (무적 · 큰 대미지)
 *   · 패링 불가 공격 (휩쓸기) 은 패링해도 맞음 · 헛패링은 빈틈 · 가드 스태미나 바닥 → 가드 붕괴 · 자세 붕괴 → 무릎 → 정면 치명 (뒤에서는 안 됨).
 */
public final class Phase6ClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
	private static final int RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
	private static final List<String> FAILURES = new ArrayList<>();
	private static int golemId;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:stone");
			sp.getServer().runCommand("fill -30 " + (Y + 1) + " -30 30 " + (Y + 14) + " 30 minecraft:air");
			sp.getConnection().waitForChunksRender();
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			parryAndCritical(ctx, sp);
			parryUnparryable(ctx, sp);
			parryWhiff(ctx, sp);
			guardBreak(ctx, sp);
			stanceBreak(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 6 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 6 전부 통과");
	}

	/** 골렘 (0.5, 0.5, +Z 를 봄) 정면 distance 칸에 서서 골렘을 봄, 골렘은 대기 · 체력 · 자세 초기화 */
	private static void stand(ClientGameTestContext ctx, TestSingleplayerContext sp, double distance) {
		ctx.getInput().releaseMouse(RIGHT);
		ctx.getInput().releaseKey(HuntKeys.modifier);
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 400);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.startAction("idle");
			g.teleportTo(0.5, Y + 1, 0.5);
			g.setYRot(0.0F);
			g.setHp(g.maxHp());
			HunterState st = HunterServer.get(player(server));
			st.health = CommonConfig.get().player.maxHealth;
			st.stamina = CommonConfig.get().stamina.max;
		});
		ctx.runOnClient(mc -> HunterMovement.state().stamina = CommonConfig.get().stamina.max);
		float yaw = distance >= 0 ? 180.0F : 0.0F;
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d %.2f %.1f 0", Y + 1, 0.5 + distance, yaw));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(yaw);
			ShoulderCamera.setAngles(yaw, 10.0F);
		});
		ctx.waitTicks(20);
	}

	private static void parryPress(ClientGameTestContext ctx) {
		ctx.getInput().pressKey(HuntKeys.parry);
	}

	/** 골렘이 action 을 하고, 골렘 프레임이 pressAt 이 되면 패링 — [잃은 체력, 패링 수 증가, 헌터 모션, 골렘 행동] */
	private static Object[] parryAgainst(ClientGameTestContext ctx, TestSingleplayerContext sp, String action, int pressAt, int watch) {
		int parries0 = sp.getServer().computeOnServer(server -> HunterServer.parries());
		double hp0 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		sp.getServer().runOnServer(server -> golem(server).startAction(action));
		boolean pressed = pressAt < 0;
		String hunterAfter = null;
		String golemAfter = null;
		for (int t = 0; t < watch; t++) {
			ctx.waitTick();
			double f = sp.getServer().computeOnServer(server -> golem(server).frame(0.0F));
			if (!pressed && f >= pressAt) {
				parryPress(ctx);
				pressed = true;
			}
			String h = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
			if ("parry_success".equals(h) && hunterAfter == null) {
				hunterAfter = h;
				golemAfter = sp.getServer().computeOnServer(server -> golem(server).actionId());
				break;
			}
		}
		double hp1 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		int parries1 = sp.getServer().computeOnServer(server -> HunterServer.parries());
		return new Object[] {hp0 - hp1, parries1 - parries0, hunterAfter, golemAfter};
	}

	private static void parryAndCritical(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 먼저 패링 없이 맞는지 (이 거리에서 휘두르기가 닿는지)
		stand(ctx, sp, 3.3);
		Object[] plain = parryAgainst(ctx, sp, "swing_1", -1, 60);
		log("패링 없이 휘두르기 1: 체력 -%.1f", (double) plain[0]);
		expect((double) plain[0] > 0, "시험 거리에서 휘두르기가 닿지 않음");

		stand(ctx, sp, 3.3);
		Object[] r = parryAgainst(ctx, sp, "swing_1", 24, 80);
		ctx.waitTicks(10);
		double critOpen = sp.getServer().computeOnServer(server -> golem(server).critOpen() ? 1.0 : 0.0);
		log("패링 (휘두르기 1, 골렘 24프레임에 입력): 체력 -%.1f · 패링 +%d · 헌터 %s · 골렘 %s · 치명 구간 %s · 기록 %s", (double) r[0], (int) r[1], r[2], r[3],
				critOpen > 0, HitLog.lastNote());
		expect((int) r[1] == 1 && (double) r[0] == 0.0, "패링 가능한 공격을 패링하지 못함");
		expect("parry_success".equals(r[2]) && "parried".equals(r[3]) && critOpen > 0, "패링 뒤 골렘이 휘청 (치명 구간) 이 아님");

		// 치명 일격
		float gHp0 = sp.getServer().computeOnServer(server -> golem(server).hp());
		int crit0 = sp.getServer().computeOnServer(server -> HunterServer.criticals());
		ctx.getInput().pressMouse(LEFT);
		ctx.waitFor(mc -> ClientHunters.local().current() != null && !"parry_success".equals(ClientHunters.local().motionId()), 60);
		String cm = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
		String sm = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		String ga = sp.getServer().computeOnServer(server -> golem(server).actionId());
		boolean invuln = true;
		for (int t = 0; t < 60; t++) {
			ctx.waitTick();
			invuln &= sp.getServer().computeOnServer(server -> {
				var c = HunterServer.combat(player(server));
				return c.current() == null || c.invulnerable(c.frame());
			});
			if (t == 38) {
				ctx.takeScreenshot("phase6_critical");
			}
		}
		float gHp1 = sp.getServer().computeOnServer(server -> golem(server).hp());
		int crit1 = sp.getServer().computeOnServer(server -> HunterServer.criticals());
		double critDmg = sp.getServer().computeOnServer(server -> HunterServer.lastCritical());
		// 비교: 약공격 1타 대미지 (몸통)
		double light = DamageCalc.compute(kr.huntcraft.combat.HunterCombat.moveSet().motion("light_1").hits().getFirst(), 0.0,
				new kr.huntcraft.collision.ColliderDef.Part(1.0, false), 0.25).damage();
		log("치명 일격: 클라 %s · 서버 %s · 골렘 %s · 무적 %s · 골렘 체력 %.0f → %.0f (치명 %.0f, 약공격 %.0f 의 %.1f배) · 기록 %s", cm, sm, ga, invuln,
				gHp0, gHp1, critDmg, light, critDmg / light, HitLog.lastNote());
		expect("critical".equals(cm) && "critical".equals(sm), "패링 뒤 공격이 치명 일격이 아님");
		expect("riposted".equals(ga), "골렘이 치명 일격을 맞는 행동이 아님: " + ga);
		expect(crit1 == crit0 + 1 && gHp0 - gHp1 > light * 5, "치명 일격 대미지가 없거나 작음");
		expect(invuln, "치명 일격 중 무적이 아님");
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
	}

	/** 패링 불가 공격 (팔 돌려 휩쓸기) 은 패링 구간이어도 맞음 */
	private static void parryUnparryable(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 3.3);
		Object[] r = parryAgainst(ctx, sp, "sweep_1", 42, 90);
		log("패링 불가 (휩쓸기): 체력 -%.1f · 패링 +%d", (double) r[0], (int) r[1]);
		expect((int) r[1] == 0 && (double) r[0] > 0, "패링 불가 공격이 패링됨");
	}

	/** 헛패링: 회복이 길어 곧바로 구르기 · 공격이 안 나감 */
	private static void parryWhiff(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 6.0);
		parryPress(ctx);
		ctx.waitTicks(20);
		String m = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		ctx.getInput().pressKey(HuntKeys.evade);
		ctx.waitTicks(6);
		String m2 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		int total = ctx.computeOnClient(mc -> ClientHunters.local().current() == null ? 0 : ClientHunters.local().current().evadeCancel());
		log("헛패링: 20틱 뒤 %s · 구르기 입력 → %s (구르기 가능 %d프레임부터)", m, m2, total);
		expect("parry".equals(m) && "parry".equals(m2), "헛패링 빈틈이 없음 (바로 구름)");
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
	}

	/** 가드로 버티다 스태미나가 바닥나면 가드 붕괴 */
	private static void guardBreak(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 6.0);
		ctx.getInput().holdMouse(RIGHT);
		ctx.waitTicks(10);
		List<String> seq = new ArrayList<>();
		int breaks0 = sp.getServer().computeOnServer(server -> HunterServer.guardBreaks());
		for (int i = 0; i < 4; i++) {
			String after = sp.getServer().computeOnServer(server -> {
				ServerPlayer p = player(server);
				double rad = Math.toRadians(HunterServer.combat(p).facing());
				Vec3 from = p.position().add(-Math.sin(rad) * 2.0, 0.0, Math.cos(rad) * 2.0);
				HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(30, "knockback", from, false, false, 20, 40, false, -1));
				return String.format(Locale.ROOT, "%s(스태미나 %.0f)", HunterServer.combat(p).motionId(), HunterServer.get(p).stamina);
			});
			seq.add(after);
			if (after.startsWith("guard_break")) {
				ctx.takeScreenshot("phase6_guard_break");
				break;
			}
			ctx.waitTicks(25);
		}
		ctx.getInput().releaseMouse(RIGHT);
		int breaks1 = sp.getServer().computeOnServer(server -> HunterServer.guardBreaks());
		log("가드 붕괴: %s", seq);
		expect(breaks1 == breaks0 + 1 && seq.getLast().startsWith("guard_break"), "스태미나가 바닥나도 가드 붕괴가 없음");
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
	}

	/** 자세 게이지가 가득 차면 무릎 꿇음 → 정면에서만 치명 일격 */
	private static void stanceBreak(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String[] results = new String[2];
		double[] dists = {3.5, -3.5};
		for (int i = 0; i < 2; i++) {
			stand(ctx, sp, dists[i]);
			sp.getServer().runOnServer(server -> golem(server).onHunterHit("body", 1.0, 0.0, 10000.0));
			ctx.waitTicks(30);
			String ga = sp.getServer().computeOnServer(server -> golem(server).actionId());
			ctx.getInput().pressMouse(LEFT);
			ctx.waitFor(mc -> ClientHunters.local().current() != null, 60);
			String hm = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
			results[i] = ga + " → " + hm;
			if (i == 0) {
				ctx.waitTicks(10);
				ctx.takeScreenshot("phase6_kneel_critical");
			}
			ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
		}
		log("자세 붕괴: 정면 %s · 뒤 %s", results[0], results[1]);
		expect(results[0].equals("kneel → critical"), "자세 붕괴 정면 치명이 안 됨: " + results[0]);
		expect(results[1].startsWith("kneel") && !results[1].endsWith("critical"), "등 뒤에서 치명 일격이 나감: " + results[1]);
	}

	private static MonsterEntity golem(MinecraftServer server) {
		return (MonsterEntity) server.overworld().getEntity(golemId);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static void expect(boolean ok, String failure) {
		if (!ok) {
			FAILURES.add(failure);
			log("실패: %s", failure);
		}
	}

	private static void log(String format, Object... args) {
		System.out.println("[huntcraft-test] " + String.format(Locale.ROOT, format, args));
	}
}
