package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.debug.DebugState;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.collision.HitLog;
import kr.huntcraft.collision.HitSystem;
import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.combat.MoveSet;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.entity.HuntEntities;
import kr.huntcraft.entity.TrainingDummyEntity;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 3 시험 (소울류 태도) — 완료 조건: 더미 상대로 모든 공격이 스태미나를 소모하며 연결되고, 명중 로그에 대미지 · 강인도 대미지가 찍힘.
 *   약 1→2→3→4→1 · 강 1→2 · 차지 (탭 · 조금 · 최대) · 달리기 · 구르기 · 백스텝 · 점프 약/강 공격 · 가드 (정면 경감 · 스태미나 · 뒤/가드불가는 그대로)
 *   · 슈퍼아머 (강공격 중 강인도 대미지를 버팀) · 선입력 0.2초 · 스태미나 0 이면 공격 불가 · 골렘 강인도가 깨지면 휘청.
 * 입력은 실제 키/마우스로 넣어 ComboResolver → 상태 머신(클라 예측 + 서버) 전체 경로를 탑니다.
 */
public final class Phase3ClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
	private static final int RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<HitSystem.HitEvent> HITS = new CopyOnWriteArrayList<>();
	private static int dummyId;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		HitSystem.listen(HITS::add);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:stone");
			sp.getConnection().waitForChunksRender();
			home(ctx, sp);
			ctx.runOnClient(mc -> DebugState.showShapes = true);
			spawnDummy(ctx, sp);

			lightCombo(ctx, sp);
			heavyCombo(ctx, sp);
			charge(ctx, sp);
			special(ctx, sp);
			jumpAttacks(ctx, sp);
			buffer(ctx, sp);
			noStamina(ctx, sp);
			guard(ctx, sp);
			hyperArmor(ctx, sp);
			golemPoise(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 3 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 3 전부 통과");
	}

	// ── 연결 ────────────────────────────────────────────────

	/** 한 번의 연결 결과: 모션 순서 · 모션마다 스태미나 소모 · 명중 (대미지 · 강인도) */
	private record Chain(List<String> motions, List<Double> costs, List<HitSystem.HitEvent> hits, boolean gap) {}

	private static Chain chain(ClientGameTestContext ctx, TestSingleplayerContext sp, Runnable first, Runnable next, int count) {
		return chain(ctx, sp, first, next, count, -1);
	}

	/**
	 * first 로 시작, 모션마다 cancel 6프레임 전에 next 입력 (선입력 → 캔슬 프레임에 실행). count 개 모션이 나오면 멈춤.
	 * releaseAt >= 0 이면 그 틱에 좌클릭 · Shift 를 뗌 (차지 길이). 모션이 바뀔 때마다 더미를 헌터 앞에 다시 세우고,
	 * 스태미나는 모션 시작 때 가득 채워서 모션마다 소모량만 잼.
	 */
	private static Chain chain(ClientGameTestContext ctx, TestSingleplayerContext sp, Runnable first, Runnable next, int count, int releaseAt) {
		home(ctx, sp);
		refill(ctx, sp);
		placeDummyAhead(ctx, sp);
		HITS.clear();
		List<String> timeline = new ArrayList<>();
		List<Double> costs = new ArrayList<>();
		first.run();
		String prev = null;
		int stage = 0;
		boolean pressed = false;
		double full = CommonConfig.get().stamina.max;
		for (int t = 0; t < 600; t++) {
			ctx.waitTick();
			if (t == releaseAt) {
				ctx.getInput().releaseMouse(LEFT);
				ctx.getInput().releaseKey(HuntKeys.modifier);
			}
			String motion = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
			int frame = ctx.computeOnClient(mc -> ClientHunters.local().frame());
			if (!Objects.equals(motion, prev)) {
				timeline.add(motion);
				pressed = false;
				if (motion != null) {
					stage++;
					double now = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina);
					costs.add(full - now);
					setStamina(ctx, sp, full);
					placeDummyAhead(ctx, sp);
				}
			}
			prev = motion;
			MoveSet.Motion m = ctx.computeOnClient(mc -> ClientHunters.local().current());
			if (m != null && next != null && !pressed && stage < count && frame >= m.cancel() - 6) {
				next.run();
				pressed = true;
			}
			if (motion == null && stage > 0 && (stage >= count || next == null)) {
				break;
			}
		}
		ctx.getInput().releaseMouse(LEFT);
		ctx.getInput().releaseKey(HuntKeys.modifier);
		List<String> motions = timeline.stream().filter(Objects::nonNull).toList();
		int firstNull = timeline.indexOf(null);
		boolean gap = firstNull >= 0 && firstNull < timeline.size() - 1;
		List<HitSystem.HitEvent> own = HITS.stream().filter(e -> e.attacker() instanceof HunterBody).toList();
		return new Chain(motions, costs, own, gap);
	}

	private static void logHits(String what, Chain c) {
		for (HitSystem.HitEvent e : c.hits()) {
			if (e.damage() instanceof DamageCalc.Result r) {
				log("  %s 명중: #%d 부위 %s · 대미지 %.1f · 강인도 %.1f · 자세 %.1f (MV %.0f%s)", what, e.instance(), e.part(), r.damage(), r.poise(),
						r.stance(), r.motionValue(), r.charge() > 0.0 ? String.format(Locale.ROOT, " · 차지 %.0f%%", r.charge() * 100.0) : "");
			}
		}
	}

	/** 기대한 모션 순서 · 끊김 없음 · 공격마다 스태미나 소모 · 명중마다 대미지 > 0, 강인도 > 0 */
	private static void check(String what, Chain c, List<String> expected, int minHits) {
		log("%s: %s · 스태미나 %s · 명중 %d", what, c.motions(), fmt(c.costs()), c.hits().size());
		logHits(what, c);
		expect(c.motions().equals(expected), what + " 순서가 다름: " + c.motions());
		expect(!c.gap(), what + " 중간에 대기로 끊김");
		expect(c.costs().stream().allMatch(x -> x > 0.5), what + " 스태미나를 쓰지 않은 동작이 있음: " + c.costs());
		expect(c.hits().size() >= minHits, what + " 명중 " + c.hits().size() + "번 (기대 " + minHits + " 이상)");
		expect(c.hits().stream().allMatch(e -> e.damage() instanceof DamageCalc.Result r && r.damage() > 0 && r.poise() > 0),
				what + " 명중에 대미지 · 강인도 대미지가 없음");
	}

	private static void lightCombo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Chain c = chain(ctx, sp, () -> ctx.getInput().pressMouse(LEFT), () -> ctx.getInput().pressMouse(LEFT), 5);
		check("약공격 1→4→1", c, List.of("light_1", "light_2", "light_3", "light_4", "light_1"), 5);
		String line = HitLog.lastLine();
		log("  명중 로그: %s", line);
		expect(line.contains("대미지=") && line.contains("강인도="), "명중 로그에 대미지 · 강인도가 없음");
		long distinct = c.hits().stream().map(HitSystem.HitEvent::instance).distinct().count();
		expect(distinct == c.hits().size(), "한 공격이 같은 더미를 여러 번 맞힘");
	}

	private static void heavyCombo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Chain c = chain(ctx, sp, () -> heavyTap(ctx), () -> heavyTap(ctx), 2);
		check("강공격 1→2", c, List.of("heavy_1", "heavy_2"), 2);
		expect(c.hits().stream().allMatch(e -> ((DamageCalc.Result) e.damage()).poise() >= 30), "강공격 강인도 대미지가 작음");
	}

	/** 차지: 누르고 있는 시간에 따라 대미지 · 강인도 증가 (탭 · 42틱 · 최대) */
	private static void charge(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		double[] dmg = new double[3];
		double[] poise = new double[3];
		double[] level = new double[3];
		int[] holds = {0, 42, 150};
		for (int i = 0; i < 3; i++) {
			int hold = holds[i];
			Chain c = chain(ctx, sp, () -> {
				ctx.getInput().holdKey(HuntKeys.modifier);
				ctx.waitTick();
				if (hold == 0) {
					ctx.getInput().pressMouse(LEFT);
				} else {
					ctx.getInput().holdMouse(LEFT);
				}
			}, null, 1, hold == 0 ? 1 : hold);
			dmg[i] = c.hits().stream().mapToDouble(e -> ((DamageCalc.Result) e.damage()).damage()).max().orElse(0);
			poise[i] = c.hits().stream().mapToDouble(e -> ((DamageCalc.Result) e.damage()).poise()).max().orElse(0);
			level[i] = c.hits().stream().mapToDouble(e -> ((DamageCalc.Result) e.damage()).charge()).max().orElse(0);
			log("차지 %d틱: 모션 %s · 대미지 %.1f · 강인도 %.1f · 차지량 %.2f", hold, c.motions(), dmg[i], poise[i], level[i]);
			if (hold == 0) {
				expect(c.motions().equals(List.of("heavy_1")), "탭 강공격이 차지로 넘어감: " + c.motions());
			} else {
				expect(c.motions().equals(List.of("heavy_1", "heavy_charge", "heavy_charged")), "차지 흐름이 다름: " + c.motions());
			}
		}
		expect(dmg[0] > 0 && dmg[1] > dmg[0] && dmg[2] > dmg[1], "차지할수록 대미지가 커지지 않음");
		expect(poise[2] > poise[1] && level[2] > 0.99 && level[1] > 0.2 && level[1] < 0.9, "차지량 · 강인도 증가 이상");
	}

	/** Shift + 좌클릭 (바로 뗌 — 차지 없음) */
	private static void heavyTap(ClientGameTestContext ctx) {
		ctx.getInput().holdKey(HuntKeys.modifier);
		ctx.waitTick();
		ctx.getInput().pressMouse(LEFT);
		ctx.getInput().releaseKey(HuntKeys.modifier);
	}

	/** 달리기 · 구르기 · 백스텝 공격 */
	private static void special(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Chain run = chain(ctx, sp, () -> {
			ctx.getInput().holdKey(opts -> opts.keyUp);
			ctx.getInput().holdKey(HuntKeys.evade);
			ctx.waitTicks(40);
			ctx.getInput().pressMouse(LEFT);
			ctx.waitTick();
			ctx.getInput().releaseKey(HuntKeys.evade);
			ctx.getInput().releaseKey(opts -> opts.keyUp);
		}, null, 1);
		check("달리기 공격", run, List.of("run_attack"), 1);

		Chain roll = chain(ctx, sp, () -> {
			ctx.getInput().holdKey(opts -> opts.keyUp);
			ctx.waitTick();
			ctx.getInput().pressKey(HuntKeys.evade);
			ctx.waitTick();
			ctx.getInput().releaseKey(opts -> opts.keyUp);
		}, () -> ctx.getInput().pressMouse(LEFT), 2);
		check("구르기 → 구르기 공격", roll, List.of("roll", "roll_attack"), 1);

		Chain back = chain(ctx, sp, () -> ctx.getInput().pressKey(HuntKeys.evade), () -> ctx.getInput().pressMouse(LEFT), 2);
		check("백스텝 → 백스텝 공격", back, List.of("backstep", "backstep_attack"), 1);
	}

	private static void jumpAttacks(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		for (boolean heavy : new boolean[] {false, true}) {
			Chain c = chain(ctx, sp, () -> {
				ctx.getInput().pressKey(HuntKeys.jump);
				ctx.waitTicks(6);
				if (heavy) {
					heavyTap(ctx);
				} else {
					ctx.getInput().pressMouse(LEFT);
				}
			}, null, 1);
			String want = heavy ? "jump_heavy" : "jump_light";
			log("점프 %s: %s · 스태미나 %s · 명중 %d", heavy ? "강" : "약", c.motions(), fmt(c.costs()), c.hits().size());
			logHits(want, c);
			expect(c.motions().equals(List.of(want)), "점프 공격이 " + want + " 이 아님: " + c.motions());
			expect(c.costs().stream().allMatch(x -> x > 0.5), want + " 스태미나 소모 없음");
			expect(!c.hits().isEmpty(), want + " 이 더미에 맞지 않음");
			if (heavy) {
				expect(c.hits().stream().allMatch(e -> ((DamageCalc.Result) e.damage()).stance() >= 30), "점프 강공격 자세 대미지가 작음");
			}
		}
	}

	/** 선입력 0.2초: 캔슬 8프레임 전 입력은 실행, 20프레임 전 입력은 사라짐 */
	private static void buffer(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		for (int early : new int[] {8, 20}) {
			home(ctx, sp);
			refill(ctx, sp);
			placeDummyAhead(ctx, sp);
			ctx.getInput().pressMouse(LEFT);
			boolean pressed = false;
			List<String> seen = new ArrayList<>();
			for (int t = 0; t < 200; t++) {
				ctx.waitTick();
				String motion = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
				if (motion != null && (seen.isEmpty() || !seen.getLast().equals(motion))) {
					seen.add(motion);
				}
				MoveSet.Motion m = ctx.computeOnClient(mc -> ClientHunters.local().current());
				int frame = ctx.computeOnClient(mc -> ClientHunters.local().frame());
				if (m != null && !pressed && frame >= m.cancel() - early) {
					ctx.getInput().pressMouse(LEFT);
					pressed = true;
				}
				if (motion == null && pressed) {
					break;
				}
			}
			waitIdle(ctx);
			ctx.waitTicks(30);
			log("선입력: 캔슬 %d프레임 전 입력 → %s (선입력 %.2f초)", early, seen, CommonConfig.get().player.inputBufferSeconds);
			if (early == 8) {
				expect(seen.equals(List.of("light_1", "light_2")), "선입력 안의 입력이 실행되지 않음: " + seen);
			} else {
				expect(seen.equals(List.of("light_1")), "선입력보다 이른 입력이 실행됨: " + seen);
			}
		}
	}

	private static void noStamina(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		setStamina(ctx, sp, 0.0);
		ctx.getInput().pressMouse(LEFT);
		ctx.waitTicks(6);
		String a = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		setStamina(ctx, sp, 0.0);
		heavyTap(ctx);
		ctx.waitTicks(6);
		String b = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		log("스태미나 0: 약공격 %s · 강공격 %s", a, b);
		expect(a == null && b == null, "스태미나 0 인데 공격이 나감");
		waitIdle(ctx);
	}

	// ── 가드 · 슈퍼아머 ──────────────────────────────────────

	private static void guard(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		refill(ctx, sp);
		ctx.getInput().holdMouse(RIGHT);
		ctx.waitTicks(10);
		String m0 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		boolean guarding = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).guarding);
		// 정면: 대미지 경감 · 스태미나 소모 · 막기 경직 → 다시 가드
		double[] front = bossHit(sp, 0.0, false, 40, 25);
		ctx.waitTicks(30);
		String m1 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		// 뒤 · 가드 불가: 그대로 맞음
		double[] behind = bossHit(sp, 180.0, false, 40, 25);
		waitIdle(ctx);
		ctx.getInput().holdMouse(RIGHT);
		ctx.waitTicks(10);
		double[] grab = bossHit(sp, 0.0, true, 40, 25);
		waitIdle(ctx);
		// 가드 중 스태미나 회복 (1초)
		ctx.getInput().holdMouse(RIGHT);
		ctx.waitTicks(10);
		setStamina(ctx, sp, 20.0);
		sp.getServer().runOnServer(server -> HunterServer.get(player(server)).regenDelayTicks = 0);
		ctx.runOnClient(mc -> HunterMovement.state().regenDelayTicks = 0);
		ctx.waitTicks(60);
		double regenGuard = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina) - 20.0;
		ctx.takeScreenshot("phase3_guard");
		ctx.getInput().releaseMouse(RIGHT);
		ctx.waitTicks(10);
		String m2 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		CommonConfig cfg = CommonConfig.get();
		log("가드: 모션 %s · 가드 상태 %s · 정면 체력 -%.1f 스태미나 -%.1f → %s · 뒤 -%.1f · 가드불가 -%.1f · 가드 중 1초 회복 %.1f (보통 %.1f) · 뗀 뒤 %s",
				m0, guarding, front[0], front[1], m1, behind[0], grab[0], regenGuard, cfg.stamina.regenPerSec, m2);
		expect("guard".equals(m0) && guarding, "우클릭으로 가드가 안 됨");
		expect(Math.abs(front[0] - 40 * (1 - cfg.player.guardAbsorption)) < 0.5 && Math.abs(front[1] - 25) < 0.5, "정면 가드 경감 · 스태미나 이상");
		expect("guard".equals(m1), "막은 뒤 다시 가드로 돌아오지 않음: " + m1);
		expect(Math.abs(behind[0] - 40) < 0.5 && Math.abs(grab[0] - 40) < 0.5, "뒤 · 가드 불가 공격이 막힘");
		expect(Math.abs(regenGuard - cfg.stamina.regenPerSec * cfg.stamina.guardRegenMultiplier) < 2.0, "가드 중 회복이 느려지지 않음: " + regenGuard);
		expect(m2 == null, "우클릭을 떼도 가드가 안 풀림: " + m2);
	}

	/** 헌터 몸 방향 기준 angle 쪽 2칸에서 보스 공격 → [잃은 체력, 잃은 스태미나] */
	private static double[] bossHit(TestSingleplayerContext sp, double angle, boolean unblockable, double damage, double guardStamina) {
		return sp.getServer().computeOnServer(server -> {
			ServerPlayer p = player(server);
			HunterState st = HunterServer.get(p);
			st.health = CommonConfig.get().player.maxHealth;
			double hp0 = st.health;
			double s0 = st.stamina;
			double rad = Math.toRadians(HunterServer.combat(p).facing() + angle);
			Vec3 from = p.position().add(-Math.sin(rad) * 2.0, 0.0, Math.cos(rad) * 2.0);
			HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(damage, "knockback", from, false, unblockable, 20, guardStamina, false, -1));
			return new double[] {hp0 - st.health, s0 - st.stamina};
		});
	}

	private static void hyperArmor(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Object[][] cases = {{true, 30.0, 20}, {true, 90.0, 20}, {false, 10.0, 12}};
		String[] result = new String[cases.length];
		for (int i = 0; i < cases.length; i++) {
			boolean heavy = (boolean) cases[i][0];
			double poise = (double) cases[i][1];
			int at = (int) cases[i][2];
			home(ctx, sp);
			refill(ctx, sp);
			if (heavy) {
				heavyTap(ctx);
			} else {
				ctx.getInput().pressMouse(LEFT);
			}
			ctx.waitFor(mc -> ClientHunters.local().current() != null && ClientHunters.local().frame() >= at, 120);
			result[i] = sp.getServer().computeOnServer(server -> {
				ServerPlayer p = player(server);
				String before = HunterServer.combat(p).motionId();
				double rad = Math.toRadians(HunterServer.combat(p).facing());
				Vec3 from = p.position().add(-Math.sin(rad) * 2.0, 0.0, Math.cos(rad) * 2.0);
				HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(10, "knockback", from, false, false, poise, 0, false, -1));
				return before + " → " + HunterServer.combat(p).motionId();
			});
			waitIdle(ctx);
		}
		log("슈퍼아머: 강공격 중 강인도 30 → %s · 강공격 중 강인도 90 → %s · 약공격 중 강인도 10 → %s (보너스 %.0f)", result[0], result[1], result[2],
				CommonConfig.get().player.hyperArmorPoise);
		expect(result[0].equals("heavy_1 → heavy_1"), "슈퍼아머가 강인도 30 을 못 버팀: " + result[0]);
		expect(result[1].endsWith("hit_knockback"), "슈퍼아머가 강인도 90 도 버팀: " + result[1]);
		expect(result[2].endsWith("hit_knockback"), "약공격 중에 경직이 없음: " + result[2]);
	}

	/** 골렘: 강공격 강인도 대미지로 강인도가 깨지면 휘청 (stagger) */
	private static void golemPoise(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int gid = sp.getServer().computeOnServer(server -> {
			var d = server.overworld().getEntity(dummyId);
			if (d != null) {
				d.discard();
			}
			MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 3.3), 180.0F, 0.0);
			m.setAi(false);
			return m.getId();
		});
		ctx.waitTicks(30);
		HITS.clear();
		String action = null;
		int heavies = 0;
		for (int i = 0; i < 12 && action == null; i++) {
			home(ctx, sp);
			refill(ctx, sp);
			heavyTap(ctx);
			heavies++;
			for (int t = 0; t < 90 && action == null; t++) {
				ctx.waitTick();
				String a = sp.getServer().computeOnServer(server -> ((MonsterEntity) server.overworld().getEntity(gid)).actionId());
				if ("stagger".equals(a)) {
					action = a;
					ctx.takeScreenshot("phase3_golem_stagger");
				}
			}
		}
		waitIdle(ctx);
		long golemHits = HITS.stream().filter(e -> e.target().self().getId() == gid).count();
		log("골렘 강인도: 강공격 %d번 (명중 %d) → 행동 %s · 기록 %s", heavies, golemHits, action, HitLog.lastNote());
		expect("stagger".equals(action), "골렘 강인도가 깨져도 휘청하지 않음");
		sp.getServer().runOnServer(server -> server.overworld().getEntity(gid).discard());
	}

	// ── 도우미 ──────────────────────────────────────────────

	private static String fmt(List<Double> xs) {
		return xs.stream().map(x -> String.format(Locale.ROOT, "%.0f", x)).toList().toString();
	}

	private static void spawnDummy(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		dummyId = sp.getServer().computeOnServer(server -> {
			TrainingDummyEntity d = HuntEntities.TRAINING_DUMMY.create(server.overworld(), EntitySpawnReason.COMMAND);
			d.snapTo(0.5, Y + 1, 3.1, 180.0F, 0.0F);
			d.setAnimate(false);
			d.startClock();
			server.overworld().addFreshEntity(d);
			return d.getId();
		});
		ctx.waitTicks(5);
	}

	private static void placeDummyAhead(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			var e = server.overworld().getEntity(dummyId);
			if (e == null) {
				return;
			}
			double rad = Math.toRadians(p.getYRot());
			Vec3 at = p.position().add(-Math.sin(rad) * 2.4, 0.0, Math.cos(rad) * 2.4);
			e.snapTo(at.x, Y + 1, at.z, p.getYRot() + 180.0F, 0.0F);
		});
	}

	/** 서버 · 클라이언트 스태미나를 value 로 (0 이면 회복도 잠시 멈춤 — 시험 중 회복돼 버리지 않게) */
	private static void setStamina(ClientGameTestContext ctx, TestSingleplayerContext sp, double value) {
		int delay = value <= 0.0 ? 120 : 0;
		sp.getServer().runOnServer(server -> {
			HunterServer.get(player(server)).stamina = value;
			if (delay > 0) {
				HunterServer.get(player(server)).regenDelayTicks = delay;
			}
		});
		ctx.runOnClient(mc -> {
			HunterMovement.state().stamina = value;
			if (delay > 0) {
				HunterMovement.state().regenDelayTicks = delay;
			}
		});
	}

	private static void refill(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		setStamina(ctx, sp, CommonConfig.get().stamina.max);
		sp.getServer().runOnServer(server -> HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth);
	}

	private static void waitIdle(ClientGameTestContext ctx) {
		ctx.getInput().releaseMouse(RIGHT);
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 600);
		ctx.waitTicks(3);
	}

	private static void home(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		waitIdle(ctx);
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.teleportTo(0.5, Y + 1, 0.5);
			p.setYRot(0.0F);
		});
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(0.0F);
			mc.player.setDeltaMovement(Vec3.ZERO);
			ShoulderCamera.setAngles(0.0F, 12.0F);
		});
		ctx.waitTicks(25); // 콤보 유지 · 구르기 파생 창이 끝나도록
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
