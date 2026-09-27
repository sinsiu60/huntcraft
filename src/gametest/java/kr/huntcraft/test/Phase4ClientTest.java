package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.LockOn;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 4 시험 — 락온 카메라. 완료 조건: 큰 대상 (2배 골렘, 행동 끔) 주위를 락온한 채 돌아도 카메라가 뒤집히거나 대상을 놓치지 않음.
 *   게걸음 원 돌기 (카메라 · 몸이 대상을 봄, 세로 각도 한계 · 튀지 않음) · 가까이 붙으면 카메라가 뒤로 빠지고 세로 한계 유지
 *   · 마우스로 락온 지점 (몸통 → 머리) · 대상 전환 · 락온 구르기 (방향별) · 락온 공격 (대상 쪽) · 거리 · 가려짐으로 자동 해제.
 */
public final class Phase4ClientTest implements FabricClientGameTest {
	private static final int Y = 150;
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
			sp.getServer().runCommand("fill -60 " + Y + " -60 60 " + Y + " 60 minecraft:stone");
			sp.getServer().runCommand("fill -60 " + (Y + 1) + " -60 60 " + (Y + 20) + " 60 minecraft:air");
			sp.getConnection().waitForChunksRender();
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			lockAndCircle(ctx, sp);
			close(ctx, sp);
			points(ctx, sp);
			lockedRollAndAttack(ctx, sp);
			switchTarget(ctx, sp);
			release(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 4 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 4 전부 통과");
	}

	/** 골렘 앞 distance 칸에서 골렘을 보고 섬 (락온 풀린 상태) */
	private static void stand(ClientGameTestContext ctx, TestSingleplayerContext sp, double distance) {
		ctx.runOnClient(mc -> LockOn.release());
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 300);
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d %.2f 180 0", Y + 1, 0.5 + distance));
		ctx.waitTicks(10);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(180.0F);
			ShoulderCamera.setAngles(180.0F, 12.0F);
		});
		ctx.waitTicks(10);
	}

	private static void lockAndCircle(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 7.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		boolean locked = ctx.computeOnClient(mc -> LockOn.target() != null && LockOn.target().getId() == golemId);
		expect(locked, "Q 로 골렘에 락온되지 않음");
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		double worstYaw = 0.0;
		double worstBody = 0.0;
		double worstPitchJump = 0.0;
		double minPitch = 90.0;
		double maxPitch = -90.0;
		double minDist = 99.0;
		double maxDist = 0.0;
		boolean lost = false;
		boolean inside = false;
		double travelled = 0.0;
		Double lastAng = null;
		float lastPitch = ctx.computeOnClient(mc -> ShoulderCamera.renderPitch());
		ctx.getInput().holdKey(opts -> opts.keyRight);
		for (int t = 0; t < 480; t++) {
			ctx.waitTick();
			double[] v = ctx.computeOnClient(mc -> {
				Vec3 p = LockOn.point(1.0F);
				if (p == null) {
					return null;
				}
				Vec3 cam = mc.gameRenderer.mainCamera().position();
				double camToTarget = Math.toDegrees(Math.atan2(-(p.x - cam.x), p.z - cam.z));
				double bodyToTarget = Math.toDegrees(Math.atan2(-(p.x - mc.player.getX()), p.z - mc.player.getZ()));
				return new double[] {Mth.wrapDegrees(camToTarget - ShoulderCamera.renderYaw()), ShoulderCamera.renderPitch(),
						Mth.wrapDegrees(bodyToTarget - mc.player.getYRot()), Math.hypot(mc.player.getX() - 0.5, mc.player.getZ() - 0.5),
						Math.toDegrees(Math.atan2(mc.player.getX() - 0.5, mc.player.getZ() - 0.5)), cam.x, cam.y, cam.z};
			});
			if (v == null) {
				lost = true;
				break;
			}
			if (t > 30) {
				worstYaw = Math.max(worstYaw, Math.abs(v[0]));
				worstBody = Math.max(worstBody, Math.abs(v[2]));
			}
			worstPitchJump = Math.max(worstPitchJump, Math.abs(v[1] - lastPitch));
			lastPitch = (float) v[1];
			minPitch = Math.min(minPitch, v[1]);
			maxPitch = Math.max(maxPitch, v[1]);
			minDist = Math.min(minDist, v[3]);
			maxDist = Math.max(maxDist, v[3]);
			if (lastAng != null) {
				travelled += Math.abs(Mth.wrapDegrees(v[4] - lastAng));
			}
			lastAng = v[4];
			BlockPos cp = BlockPos.containing(v[5], v[6], v[7]);
			inside |= sp.getServer().computeOnServer(server -> !server.overworld().getBlockState(cp).isAir());
			if (t == 200) {
				ctx.takeScreenshot("phase4_circle");
			}
			// 게걸음 한 주기 (다리 모양 확인용)
			if (t >= 240 && t < 288 && t % 3 == 0) {
				ctx.takeScreenshot(String.format(Locale.ROOT, "phase4_strafe_%03d", t));
			}
		}
		ctx.getInput().releaseKey(opts -> opts.keyRight);
		log("원 돌기 (8초 D): 놓침 %s · 돈 각도 %.0f° · 거리 %.1f~%.1f · 카메라-대상 좌우 최대 %.1f° · 몸-대상 최대 %.1f° · 세로 %.1f~%.1f° (한계 %.0f~%.0f) · 세로 한 틱 최대 %.2f° · 카메라 블록 안 %s",
				lost, travelled, minDist, maxDist, worstYaw, worstBody, minPitch, maxPitch, cfg.lockOnPitchMin, cfg.lockOnPitchMax, worstPitchJump, inside);
		expect(!lost, "원을 도는 중 락온이 풀림");
		expect(travelled > 150.0, "대상 주위를 돌지 않음 (게걸음이 아님): " + travelled);
		expect(worstYaw < 20.0, "카메라가 대상을 놓침 (좌우 차이 " + worstYaw + ")");
		expect(worstBody < 15.0, "몸이 대상을 보지 않음 (" + worstBody + ")");
		expect(minPitch >= cfg.lockOnPitchMin - 0.01 && maxPitch <= cfg.lockOnPitchMax + 0.01, "세로 각도가 한계를 넘음");
		expect(worstPitchJump < 5.0, "카메라 세로 각도가 튐 (" + worstPitchJump + ")");
		expect(!inside, "카메라가 블록 안으로 들어감");
	}

	/** 바짝 붙음: 카메라가 뒤 · 위로 빠지고, 머리가 위에 있어도 세로 한계 */
	private static void close(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 7.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		double far = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d 2.6", Y + 1));
		ctx.waitTicks(60);
		double near = ctx.computeOnClient(mc -> mc.gameRenderer.mainCamera().position().distanceTo(mc.player.getEyePosition()));
		float pitch = ctx.computeOnClient(mc -> ShoulderCamera.renderPitch());
		boolean locked = ctx.computeOnClient(mc -> LockOn.locked());
		// 머리 지점으로 바꿔 올려다보게 해도 한계
		ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, 0.0, -200.0));
		ctx.waitTicks(40);
		float headPitch = ctx.computeOnClient(mc -> ShoulderCamera.renderPitch());
		int idx = ctx.computeOnClient(mc -> LockOn.pointIndex());
		ctx.takeScreenshot("phase4_close");
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		log("바짝 붙음: 카메라 거리 %.2f → %.2f · 세로 %.1f° · 머리 지점(%d) 세로 %.1f° (위 한계 %.0f) · 락온 %s", far, near, pitch, idx, headPitch,
				cfg.lockOnPitchMin, locked);
		expect(near > far + 0.5, "가까이 붙어도 카메라가 뒤로 빠지지 않음");
		expect(locked && headPitch >= cfg.lockOnPitchMin - 0.01, "머리 위 대상에서 세로 한계가 안 지켜짐");
	}

	/** 마우스 위로 휙 → 머리 지점, 아래로 휙 → 몸통 */
	private static void points(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 8.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		int i0 = ctx.computeOnClient(mc -> LockOn.pointIndex());
		ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, 0.0, -150.0));
		ctx.waitTicks(30);
		int i1 = ctx.computeOnClient(mc -> LockOn.pointIndex());
		ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, 0.0, 150.0));
		ctx.waitTicks(30);
		int i2 = ctx.computeOnClient(mc -> LockOn.pointIndex());
		// 천천히 흘린 마우스 (틱마다 조금씩) 는 바뀌지 않음
		for (int t = 0; t < 30; t++) {
			ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, 0.0, -8.0));
			ctx.waitTick();
		}
		int i3 = ctx.computeOnClient(mc -> LockOn.pointIndex());
		log("락온 지점: 처음 %d · 위로 휙 → %d · 아래로 휙 → %d · 천천히 위로 → %d (0 몸통 · 1 머리)", i0, i1, i2, i3);
		expect(i0 == 0 && i1 == 1 && i2 == 0 && i3 == 0, "마우스로 락온 지점이 바뀌지 않음");
	}

	/** 락온 중 A + Space → 왼쪽 구르기 (몸은 대상 쪽), 락온 중 공격은 대상 쪽으로 */
	private static void lockedRollAndAttack(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 7.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		ctx.getInput().holdKey(opts -> opts.keyLeft);
		ctx.waitTicks(2);
		ctx.getInput().pressKey(HuntKeys.evade);
		ctx.waitFor(mc -> ClientHunters.local().current() != null, 30);
		ctx.getInput().releaseKey(opts -> opts.keyLeft);
		String roll = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
		String serverRoll = sp.getServer().computeOnServer(server -> kr.huntcraft.hunter.HunterServer.combat(player(server)).motionId());
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 120);
		ctx.waitTicks(10);
		// 몸을 대상에서 돌려 놓고 (락온은 유지) 약공격 → 시작할 때 대상 쪽으로
		ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitFor(mc -> ClientHunters.local().current() != null, 30);
		double off = ctx.computeOnClient(mc -> {
			float want = LockOn.bodyYaw(mc.player);
			return (double) Math.abs(Mth.wrapDegrees(want - ClientHunters.local().facing()));
		});
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 120);
		log("락온 구르기 (A): 클라 %s · 서버 %s · 락온 공격 시작 방향과 대상 차이 %.1f°", roll, serverRoll, off);
		expect("roll_left".equals(roll) && "roll_left".equals(serverRoll), "락온 중 옆 구르기가 아님: " + roll + "/" + serverRoll);
		expect(off < 5.0, "락온 공격이 대상 쪽으로 나가지 않음: " + off);
	}

	/** 둘째 골렘을 왼쪽에 두고 마우스 왼쪽으로 휙 → 대상이 바뀜, 오른쪽으로 휙 → 돌아옴 */
	private static void switchTarget(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int second = sp.getServer().computeOnServer(server -> {
			MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(-7.5, Y + 1, 0.5), 0.0F, 0.0);
			m.setAi(false);
			return m.getId();
		});
		ctx.waitTicks(20);
		stand(ctx, sp, 9.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		int t0 = ctx.computeOnClient(mc -> LockOn.target() == null ? -1 : LockOn.target().getId());
		// 카메라 yaw 180 (북쪽 −Z 를 봄) 에서 서쪽 (−X) 은 화면 왼쪽
		ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, -200.0, 0.0));
		ctx.waitTicks(30);
		int t1 = ctx.computeOnClient(mc -> LockOn.target() == null ? -1 : LockOn.target().getId());
		ctx.runOnClient(mc -> ShoulderCamera.turn(mc.player, 200.0, 0.0));
		ctx.waitTicks(30);
		int t2 = ctx.computeOnClient(mc -> LockOn.target() == null ? -1 : LockOn.target().getId());
		log("대상 전환: 처음 #%d (골렘 #%d) · 왼쪽으로 휙 → #%d (둘째 #%d) · 오른쪽으로 휙 → #%d", t0, golemId, t1, second, t2);
		expect(t0 == golemId && t1 == second && t2 == golemId, "마우스로 대상이 바뀌지 않음");
		sp.getServer().runOnServer(server -> server.overworld().getEntity(second).discard());
		ctx.waitTicks(10);
	}

	/** 멀어지면 · 가려지면 풀림 */
	private static void release(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		stand(ctx, sp, 7.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d %.1f", Y + 1, 0.5 + ClientConfig.get().camera.lockOnKeepRange + 4));
		ctx.waitTicks(20);
		boolean farLocked = ctx.computeOnClient(mc -> LockOn.locked());

		stand(ctx, sp, 9.0);
		ctx.getInput().pressKey(HuntKeys.lockOn);
		ctx.waitTicks(20);
		sp.getServer().runCommand("fill -6 " + (Y + 1) + " 5 6 " + (Y + 12) + " 5 minecraft:stone");
		ctx.waitTicks(20);
		boolean shortHidden = ctx.computeOnClient(mc -> LockOn.locked());
		ctx.waitTicks(150);
		boolean longHidden = ctx.computeOnClient(mc -> LockOn.locked());
		sp.getServer().runCommand("fill -6 " + (Y + 1) + " 5 6 " + (Y + 12) + " 5 minecraft:air");
		log("자동 해제: 멀어짐 → 락온 %s · 가려짐 0.3초 → %s · 가려짐 2.8초 → %s", farLocked, shortHidden, longHidden);
		expect(!farLocked, "멀어져도 락온이 안 풀림");
		expect(shortHidden && !longHidden, "가려짐 해제 시간이 이상함");
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
