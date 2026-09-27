package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.debug.ShapeDebugRenderer;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.hunter.HunterState;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 1 시험 (소울류) — 숄더뷰 카메라 · 카메라 기준 이동 · 몸 회전 분리 · 벽 충돌 · 달리기(Space 길게)/스태미나 ·
 * 구르기(Space 짧게) · 백스텝(방향 없이) · 무적 구간 (디버그 노랑) · 스태미나 0 이면 구르기 · 달리기 막힘 · C 점프 · 무게별 구르기.
 * 하늘 위 돌 발판(61x61)에서 잽니다. 시간은 틱 수 ÷ 60. 결과는 [huntcraft-test] 줄.
 */
public final class Phase1ClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:stone");
			sp.getConnection().waitForChunksRender();
			home(ctx, sp);

			camera(ctx, sp);
			wall(ctx, sp);
			direction(ctx, sp);
			turning(ctx, sp);
			speeds(ctx, sp);
			stamina(ctx, sp);
			evade(ctx, sp);
			emptyStamina(ctx, sp);
			jump(ctx, sp);
			rollClasses(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 1 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 1 전부 통과");
	}

	// ── 카메라 ──────────────────────────────────────────────

	private static void camera(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		setCamera(ctx, 0.0F, 12.0F);
		ctx.waitTicks(40);
		CameraType type = ctx.computeOnClient(mc -> mc.options.getCameraType());
		Vec3[] v = ctx.computeOnClient(mc -> new Vec3[] {mc.gameRenderer.mainCamera().position(), mc.player.getEyePosition()});
		Vec3 rel = v[0].subtract(v[1]);
		// 카메라 yaw 0 = +Z 를 봄 → 뒤 = -Z, 오른쪽 = -X
		double behind = -rel.z;
		double rightSide = -rel.x;
		float fov = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().getFov());
		log("카메라: 시점 %s · 뒤 %.2f · 오른쪽 %.2f · 위 %.2f · FOV %.1f", type, behind, rightSide, rel.y, fov);
		expect(type == CameraType.THIRD_PERSON_BACK, "3인칭 강제가 안 됨");
		// 소울류 기본값: 뒤 4~5 · 오른쪽 0.3~0.5 · 눈높이 위 0.5~0.8 (세로 각도 12° 만큼 더 올라감)
		expect(behind > 4.0 && behind < 5.0, "카메라 뒤 거리 이상: " + behind);
		expect(rightSide > 0.3 && rightSide < 0.5, "오른쪽 오프셋 이상: " + rightSide);
		expect(rel.y > 0.5, "카메라 높이 이상: " + rel.y);
		expect(Math.abs(fov - 70.0F) < 0.5F, "FOV 가 70 이 아님: " + fov);

		// 마우스는 카메라만 돌림
		float body0 = ctx.computeOnClient(mc -> mc.player.getYRot());
		float cam0 = ctx.computeOnClient(mc -> ShoulderCamera.yaw());
		// 시험 창은 마우스를 잡지 않아 바닐라가 회전 처리를 건너뜀 — 바닐라 회전 함수(turnPlayer)를 직접 부름
		ctx.runOnClient(mc -> {
			try {
				var handler = mc.mouseHandler;
				var dx = net.minecraft.client.MouseHandler.class.getDeclaredField("accumulatedDX");
				dx.setAccessible(true);
				dx.setDouble(handler, 300.0);
				var turn = net.minecraft.client.MouseHandler.class.getDeclaredMethod("turnPlayer", double.class);
				turn.setAccessible(true);
				turn.invoke(handler, 0.0);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.waitTicks(5);
		float body1 = ctx.computeOnClient(mc -> mc.player.getYRot());
		float cam1 = ctx.computeOnClient(mc -> ShoulderCamera.yaw());
		log("마우스: 카메라 %.1f → %.1f · 몸 %.1f → %.1f", cam0, cam1, body0, body1);
		expect(Math.abs(Mth.wrapDegrees(cam1 - cam0)) > 5.0F, "마우스로 카메라가 돌지 않음");
		expect(Math.abs(Mth.wrapDegrees(body1 - body0)) < 0.01F, "마우스가 몸을 돌림");
	}

	private static void wall(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		setCamera(ctx, 0.0F, 12.0F);
		// 플레이어 뒤(-Z) 1.5칸에 벽
		sp.getServer().runCommand("fill -4 " + (Y + 1) + " -2 4 " + (Y + 5) + " -2 minecraft:stone");
		ctx.waitTicks(30);
		Vec3[] v = ctx.computeOnClient(mc -> new Vec3[] {mc.gameRenderer.mainCamera().position(), mc.player.getEyePosition()});
		boolean inside = sp.getServer().computeOnServer(server -> !server.overworld().getBlockState(BlockPos.containing(v[0])).isAir());
		log("벽: 카메라 z %.2f (벽 앞면 z -1.0) · 블록 안 %s · 눈과 거리 %.2f", v[0].z, inside, v[0].distanceTo(v[1]));
		expect(v[0].z > -1.0 && !inside, "카메라가 벽을 뚫음");
		sp.getServer().runCommand("fill -4 " + (Y + 1) + " -2 4 " + (Y + 5) + " -2 minecraft:air");
		ctx.waitTicks(60);
		Vec3[] w = ctx.computeOnClient(mc -> new Vec3[] {mc.gameRenderer.mainCamera().position(), mc.player.getEyePosition()});
		double back = -(w[0].z - w[1].z);
		log("벽 치운 뒤: 뒤 거리 %.2f", back);
		expect(back > 3.0, "벽이 사라져도 카메라가 돌아오지 않음: " + back);
	}

	// ── 이동 ────────────────────────────────────────────────

	private static void direction(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 카메라 yaw 90 (= -X 를 봄) 에서 W → -X 로 이동, 몸도 90 으로
		home(ctx, sp);
		setCamera(ctx, 90.0F, 12.0F);
		Vec3 a = pos(ctx);
		ctx.getInput().holdKeyFor(opts -> opts.keyUp, 60);
		Vec3 b = pos(ctx);
		float body = ctx.computeOnClient(mc -> mc.player.getYRot());
		log("카메라 90° + W: 이동 (%.2f, %.2f) · 몸 %.1f°", b.x - a.x, b.z - a.z, Mth.wrapDegrees(body));
		expect(b.x - a.x < -2.5 && Math.abs(b.z - a.z) < 0.3, "W 가 카메라 기준 앞으로 가지 않음");
		expect(Math.abs(Mth.wrapDegrees(body - 90.0F)) < 1.0F, "몸이 이동 방향을 보지 않음");

		// 카메라 yaw 0 에서 D → 오른쪽(-X), 옆걸음 없이 몸을 돌려서
		home(ctx, sp);
		setCamera(ctx, 0.0F, 12.0F);
		ctx.waitTicks(5);
		Vec3 prev = pos(ctx);
		double worstStrafe = 0.0;
		ctx.getInput().holdKey(opts -> opts.keyRight);
		for (int i = 0; i < 60; i++) {
			ctx.waitTick();
			Vec3 now = pos(ctx);
			float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
			Vec3 d = now.subtract(prev);
			double len = Math.hypot(d.x, d.z);
			if (len > 0.005) {
				double moveYaw = Math.toDegrees(Math.atan2(-d.x, d.z));
				worstStrafe = Math.max(worstStrafe, Math.abs(Mth.wrapDegrees(moveYaw - yaw)));
			}
			prev = now;
		}
		ctx.getInput().releaseKey(opts -> opts.keyRight);
		Vec3 end = pos(ctx);
		float body2 = ctx.computeOnClient(mc -> mc.player.getYRot());
		log("카메라 0° + D: 끝 x %.2f · 몸 %.1f° · 이동 방향과 몸 방향 최대 차이 %.1f°", end.x, Mth.wrapDegrees(body2), worstStrafe);
		expect(end.x < -2.0, "D 가 카메라 기준 오른쪽으로 가지 않음");
		expect(worstStrafe < 20.0, "옆걸음 발생 (이동 방향 ≠ 몸 방향): " + worstStrafe);
	}

	private static void turning(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		setCamera(ctx, 0.0F, 12.0F);
		ctx.getInput().holdKeyFor(opts -> opts.keyUp, 20);
		// 180° 뒤로
		float prev = ctx.computeOnClient(mc -> mc.player.getYRot());
		double maxStep = 0.0;
		int doneAt = -1;
		ctx.getInput().holdKey(opts -> opts.keyDown);
		for (int i = 0; i < 40; i++) {
			ctx.waitTick();
			float yaw = ctx.computeOnClient(mc -> mc.player.getYRot());
			maxStep = Math.max(maxStep, Math.abs(Mth.wrapDegrees(yaw - prev)));
			prev = yaw;
			if (doneAt < 0 && Math.abs(Mth.wrapDegrees(yaw - 180.0F)) < 0.5F) {
				doneAt = i + 1;
			}
		}
		ctx.getInput().releaseKey(opts -> opts.keyDown);
		double limit = CommonConfig.get().movement.turnRate / FixedTick.HZ;
		log("180° 선회: 틱당 최대 %.2f° (한계 %.2f°) · %d틱(%.2f초)에 완료", maxStep, limit, doneAt, doneAt / 60.0);
		expect(maxStep <= limit + 0.01, "회전 속도 한계 초과");
		expect(doneAt > 0 && doneAt <= Math.ceil(180.0 / limit) + 1, "선회가 끝나지 않음");
	}

	private static void speeds(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		CommonConfig.Movement m = CommonConfig.get().movement;
		double walk = measureSpeed(ctx, sp, false);
		log("걷기 %.2f 블록/초 (설정 %.2f)", walk, m.walkSpeed);
		near(walk, m.walkSpeed, 0.05, "걷기 속도");

		double sprint = measureSpeed(ctx, sp, true);
		log("달리기 (Space 길게) %.2f 블록/초 (설정 %.2f)", sprint, m.sprintSpeed);
		near(sprint, m.sprintSpeed, 0.05, "달리기 속도");
	}

	/** 30틱 가속 후 120틱(2초) 동안 평균 속도. sprint = Space 를 누르고 있음 */
	private static double measureSpeed(ClientGameTestContext ctx, TestSingleplayerContext sp, boolean sprint) {
		home(ctx, sp);
		setCamera(ctx, 0.0F, 12.0F);
		refillStamina(ctx, sp);
		ctx.getInput().holdKey(opts -> opts.keyUp);
		if (sprint) {
			ctx.getInput().holdKey(HuntKeys.evade);
		}
		ctx.waitTicks(30);
		Vec3 a = pos(ctx);
		ctx.waitTicks(120);
		Vec3 b = pos(ctx);
		ctx.getInput().releaseKey(opts -> opts.keyUp);
		if (sprint) {
			ctx.getInput().releaseKey(HuntKeys.evade);
		}
		ctx.waitTicks(20);
		waitIdle(ctx);
		return Math.hypot(b.x - a.x, b.z - a.z) / 2.0;
	}

	private static void stamina(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		CommonConfig.Stamina cfg = CommonConfig.get().stamina;
		home(ctx, sp);
		refillStamina(ctx, sp);
		// 원을 그리며 달림 (발판 밖으로 나가지 않게 카메라를 조금씩 돌림) — Space 를 먼저 눌러 달리기 문턱을 넘긴 뒤 잼
		ctx.getInput().holdKey(opts -> opts.keyUp);
		ctx.getInput().holdKey(HuntKeys.evade);
		ctx.waitTicks(FixedTick.ticks(kr.huntcraft.client.config.ClientConfig.get().input.evadeHoldSeconds) + 2);
		double s0 = ctx.computeOnClient(mc -> HunterMovement.state().stamina);
		for (int i = 0; i < 120; i++) {
			ctx.waitTick();
			turnCamera(ctx, 1.5F);
		}
		double s1 = ctx.computeOnClient(mc -> HunterMovement.state().stamina);
		double server1 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina);
		double drainPerSec = (s0 - s1) / 2.0;
		log("스태미나: 2초 달리기 %.1f → %.1f (초당 %.1f, 설정 %.1f) · 서버 %.1f", s0, s1, drainPerSec, cfg.sprintDrainPerSec, server1);
		near(drainPerSec, cfg.sprintDrainPerSec, 0.05, "스태미나 소모 속도");
		expect(Math.abs(s1 - server1) < 3.0, "클라이언트 예측과 서버 스태미나가 어긋남");

		// 바닥날 때까지
		int guard = 0;
		while (!ctx.computeOnClient(mc -> HunterMovement.state().exhausted) && guard++ < 900) {
			ctx.waitTick();
			turnCamera(ctx, 1.5F);
		}
		// 클라이언트 예측이 몇 틱 앞서므로 서버가 따라올 때까지 (최대 0.5초)
		boolean serverExhausted = false;
		for (int i = 0; i < 30 && !serverExhausted; i++) {
			ctx.waitTick();
			serverExhausted = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).exhausted);
		}
		Vec3 a = pos(ctx);
		for (int i = 0; i < 60; i++) {
			ctx.waitTick();
			turnCamera(ctx, 1.5F);
		}
		Vec3 b = pos(ctx);
		double tiredSpeed = Math.hypot(b.x - a.x, b.z - a.z);
		ctx.getInput().releaseKey(opts -> opts.keyUp);
		ctx.getInput().releaseKey(HuntKeys.evade);
		ctx.waitTicks(3);
		String afterRelease = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
		log("지침: %d틱 뒤 · 서버 %s · 지친 동안 1초 이동 %.2f (걷기 %.2f 이하여야 함) · 길게 누른 Space 를 뗀 뒤 모션 %s", guard, serverExhausted,
				tiredSpeed, CommonConfig.get().movement.walkSpeed, afterRelease);
		expect(serverExhausted, "서버가 지침 상태가 아님");
		expect(tiredSpeed <= CommonConfig.get().movement.walkSpeed * 1.05, "지친 상태에서도 달림");
		expect(afterRelease == null, "달리다 Space 를 뗐는데 구름: " + afterRelease);

		// 회복
		ctx.waitTicks(FixedTick.ticks(cfg.regenDelaySeconds) + 2);
		double r0 = ctx.computeOnClient(mc -> HunterMovement.state().stamina);
		ctx.waitTicks(60);
		double r1 = ctx.computeOnClient(mc -> HunterMovement.state().stamina);
		log("회복: 대기 %.1f초 뒤 1초 %.1f → %.1f (설정 초당 %.1f)", cfg.regenDelaySeconds, r0, r1, cfg.regenPerSec);
		near(r1 - r0, cfg.regenPerSec, 0.1, "스태미나 회복 속도");
	}

	// ── 회피 ────────────────────────────────────────────────

	/** 한 번의 회피 결과: 모션 · 이동 (몸 기준 앞/오른쪽) · 몸 방향 · 무적 프레임 수 · 스태미나 소모 · 노랑 표시 */
	private record Dodge(String motion, double moved, double forward, float body, int invulnFrames, double cost, boolean yellow) {}

	/** Space 짧게 (dirKey 를 누른 채, null = 방향 없음) — 모션이 끝날 때까지 잼 */
	private static Dodge dodge(ClientGameTestContext ctx, TestSingleplayerContext sp, float cameraYaw,
							   java.util.function.Function<net.minecraft.client.Options, net.minecraft.client.KeyMapping> dirKey, String shot) {
		home(ctx, sp);
		setCamera(ctx, cameraYaw, 12.0F);
		refillStamina(ctx, sp);
		Vec3 a = pos(ctx);
		double s0 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina);
		if (dirKey != null) {
			ctx.getInput().holdKey(dirKey);
			ctx.waitTick();
		}
		ctx.getInput().pressKey(HuntKeys.evade);
		String motion = null;
		int invuln = 0;
		double sStart = s0;
		boolean yellow = false;
		for (int t = 0; t < 90; t++) {
			ctx.waitTick();
			String m = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
			if (motion == null && m != null) {
				motion = m;
				// 모션이 막 시작된 때의 스태미나 (회복이 시작되기 전)
				sStart = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina);
			}
			if (motion != null && !motion.equals(m)) {
				break;
			}
			if (m != null) {
				// 무적은 1프레임부터 이어짐 → 마지막 무적 프레임 = 무적 프레임 수 (샘플이 한두 틱 늦게 시작해도 정확)
				int invFrame = sp.getServer().computeOnServer(server -> {
					var c = HunterServer.combat(player(server));
					return c.invulnerable(c.frame()) ? c.frame() : -1;
				});
				invuln = Math.max(invuln, invFrame);
				// 디버그 렌더와 같은 색 판정 (내 헌터의 지금 시각)
				boolean y = ctx.computeOnClient(mc -> {
					var body = ClientHunters.localBody();
					return body != null && ShapeDebugRenderer.isInvulnColor(ShapeDebugRenderer.hurtColor(body, body.animTime(0.0F)));
				});
				yellow |= y;
				if (shot != null && y && ctx.computeOnClient(mc -> ClientHunters.local().frame()) == 8) {
					ctx.takeScreenshot(shot);
				}
			}
		}
		if (dirKey != null) {
			ctx.getInput().releaseKey(dirKey);
		}
		ctx.waitTicks(5);
		Vec3 b = pos(ctx);
		float body = ctx.computeOnClient(mc -> mc.player.getYRot());
		double s1 = sStart;
		double rad = Math.toRadians(body);
		double fwd = (b.x - a.x) * -Math.sin(rad) + (b.z - a.z) * Math.cos(rad);
		return new Dodge(motion, Math.hypot(b.x - a.x, b.z - a.z), fwd, body, invuln, s0 - s1, yellow);
	}

	private static void evade(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		ctx.runOnClient(mc -> kr.huntcraft.client.debug.DebugState.showShapes = true);
		CommonConfig cfg = CommonConfig.get();
		// 카메라 0° 에서 A (카메라 기준 왼쪽 = +X, yaw -90) → 그쪽으로 몸을 돌려 앞구르기
		Dodge roll = dodge(ctx, sp, 0.0F, opts -> opts.keyLeft, "phase1_roll_invuln");
		int want = FixedTick.ticks(cfg.player.evadeInvulnSeconds * cfg.rollClass().invulnScale);
		log("구르기 (A): 모션 %s · 몸 %.1f° · 이동 %.2f (앞 %.2f) · 무적 %d프레임 (설정 %d) · 스태미나 -%.1f · 디버그 노랑 %s", roll.motion, roll.body,
				roll.moved, roll.forward, roll.invulnFrames, want, roll.cost, roll.yellow);
		expect("roll".equals(roll.motion), "Space 짧게가 구르기가 아님: " + roll.motion);
		expect(Math.abs(Mth.wrapDegrees(roll.body + 90.0F)) < 2.0F, "입력 방향으로 구르지 않음: " + roll.body);
		expect(roll.forward > 2.5, "구르기 이동이 짧음: " + roll.forward);
		expect(Math.abs(roll.invulnFrames - want) <= 1, "구르기 무적 프레임이 설정과 다름: " + roll.invulnFrames);
		expect(roll.yellow, "디버그 화면에 무적 구간이 노랗게 표시되지 않음");

		Dodge back = dodge(ctx, sp, 0.0F, null, "phase1_backstep_invuln");
		int wantBack = FixedTick.ticks(cfg.player.backstepInvulnSeconds);
		log("백스텝 (방향 없음): 모션 %s · 이동 %.2f (앞 %.2f) · 무적 %d프레임 (설정 %d) · 스태미나 -%.1f · 노랑 %s", back.motion, back.moved, back.forward,
				back.invulnFrames, wantBack, back.cost, back.yellow);
		expect("backstep".equals(back.motion), "방향 없이 Space 가 백스텝이 아님: " + back.motion);
		expect(back.forward < -1.2, "백스텝이 뒤로 가지 않음: " + back.forward);
		expect(Math.abs(back.invulnFrames - wantBack) <= 1 && back.yellow, "백스텝 무적 구간 이상: " + back.invulnFrames);
		ctx.runOnClient(mc -> kr.huntcraft.client.debug.DebugState.showShapes = false);
	}

	/** 스태미나 0: 구르기 · 백스텝 · 달리기가 막힘. 조금이라도 남았으면 구르고 0 에서 멈춤 */
	private static void emptyStamina(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		setStamina(ctx, sp, 0.0);
		ctx.getInput().holdKey(opts -> opts.keyUp);
		ctx.waitTick();
		ctx.getInput().pressKey(HuntKeys.evade);
		ctx.waitTicks(6);
		String m0 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		// 달리기 시도 (1초)
		setStamina(ctx, sp, 0.0);
		ctx.getInput().holdKey(HuntKeys.evade);
		ctx.waitTicks(20);
		Vec3 a = pos(ctx);
		ctx.waitTicks(60);
		Vec3 b = pos(ctx);
		ctx.getInput().releaseKey(HuntKeys.evade);
		ctx.getInput().releaseKey(opts -> opts.keyUp);
		double speed = Math.hypot(b.x - a.x, b.z - a.z);
		boolean sprinting = ctx.computeOnClient(mc -> HunterMovement.state().sprinting);
		waitIdle(ctx);

		// 조금 남음 (5) → 구르고 0
		home(ctx, sp);
		setStamina(ctx, sp, 5.0);
		ctx.getInput().pressKey(HuntKeys.evade);
		ctx.waitTicks(6);
		String m1 = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		double left = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).stamina);
		waitIdle(ctx);
		log("스태미나 0: 회피 %s · 달리기 %s (1초 %.2f, 걷기 %.2f) · 스태미나 5: 회피 %s → 남은 %.1f", m0, sprinting, speed,
				CommonConfig.get().movement.walkSpeed, m1, left);
		expect(m0 == null, "스태미나 0 인데 회피가 나감: " + m0);
		expect(!sprinting && speed <= CommonConfig.get().movement.walkSpeed * 1.05, "스태미나 0 인데 달림");
		expect(m1 != null && left < 1.0, "스태미나가 조금 남았을 때 회피 후 0 이 되지 않음: " + m1 + " " + left);
	}

	private static void jump(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		double y0 = pos(ctx).y;
		ctx.getInput().pressKey(HuntKeys.jump);
		double peak = y0;
		for (int t = 0; t < 40; t++) {
			ctx.waitTick();
			peak = Math.max(peak, pos(ctx).y);
		}
		String motion = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
		log("C 점프: 최고 %.2f 블록 · 모션 %s", peak - y0, motion);
		expect(peak - y0 > 1.0, "C 로 점프하지 않음: " + (peak - y0));
		expect(motion == null, "C 가 다른 동작을 함: " + motion);
	}

	/** 장비 무게별 구르기 (데이터 구조) — 무거움은 짧게 · 무적 짧게 · 스태미나 많이 */
	private static void rollClasses(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		CommonConfig cfg = CommonConfig.get();
		String keep = cfg.player.equipLoad;
		Dodge medium = dodge(ctx, sp, 0.0F, opts -> opts.keyUp, null);
		cfg.player.equipLoad = "heavy";
		Dodge heavy = dodge(ctx, sp, 0.0F, opts -> opts.keyUp, null);
		cfg.player.equipLoad = "light";
		Dodge light = dodge(ctx, sp, 0.0F, opts -> opts.keyUp, null);
		cfg.player.equipLoad = keep;
		log("무게별 구르기: 가벼움 %.2f블록 · 무적 %d · -%.1f / 보통 %.2f · %d · -%.1f / 무거움 %.2f · %d · -%.1f", light.forward,
				light.invulnFrames, light.cost, medium.forward, medium.invulnFrames, medium.cost, heavy.forward, heavy.invulnFrames, heavy.cost);
		expect(light.forward > medium.forward && medium.forward > heavy.forward, "무게별 구르기 거리 순서가 이상함");
		expect(light.invulnFrames > medium.invulnFrames && medium.invulnFrames > heavy.invulnFrames, "무게별 무적 순서가 이상함");
		expect(heavy.cost > medium.cost && medium.cost > light.cost, "무게별 스태미나 순서가 이상함");
	}

	// ── 도우미 ──────────────────────────────────────────────

	/** 서버 · 클라이언트 스태미나를 value 로 맞추고 2초 동안 회복 멈춤 */
	private static void setStamina(ClientGameTestContext ctx, TestSingleplayerContext sp, double value) {
		sp.getServer().runOnServer(server -> {
			HunterState st = HunterServer.get(player(server));
			st.stamina = value;
			st.regenDelayTicks = 120;
		});
		ctx.runOnClient(mc -> {
			HunterMovement.state().stamina = value;
			HunterMovement.state().regenDelayTicks = 120;
		});
	}

	private static void waitIdle(ClientGameTestContext ctx) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 300);
		ctx.waitTicks(5);
	}

	private static void refillStamina(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 달리지 않으면 초당 25 회복 — 가득 찰 때까지
		ctx.waitFor(mc -> HunterMovement.state().stamina >= CommonConfig.get().stamina.max - 0.01, 600);
		ctx.waitTicks(5);
	}

	private static void turnCamera(ClientGameTestContext ctx, float degrees) {
		ctx.runOnClient(mc -> ShoulderCamera.setAngles(ShoulderCamera.yaw() + degrees, ShoulderCamera.pitch()));
	}

	private static void setCamera(ClientGameTestContext ctx, float yaw, float pitch) {
		ctx.runOnClient(mc -> ShoulderCamera.setAngles(yaw, pitch));
		ctx.waitTicks(3);
	}

	private static void home(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.teleportTo(0.5, Y + 1, 0.5);
			p.setDeltaMovement(Vec3.ZERO);
			p.setYRot(0.0F);
		});
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(0.0F);
			mc.player.setDeltaMovement(Vec3.ZERO);
		});
		ctx.waitTicks(10);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static Vec3 pos(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.position());
	}

	private static void near(double actual, double reference, double tolerance, String what) {
		expect(Math.abs(actual - reference) / reference <= tolerance,
				String.format(Locale.ROOT, "%s: %.3f (기준 %.3f, 허용 %.0f%%)", what, actual, reference, tolerance * 100));
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
