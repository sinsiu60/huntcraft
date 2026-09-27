package kr.huntcraft.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.debug.DebugState;
import kr.huntcraft.client.debug.ShapeDebugRenderer;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.collision.Geometry;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.entity.ColliderTestCommands;
import kr.huntcraft.entity.HuntEntities;
import kr.huntcraft.entity.SwingRodEntity;
import kr.huntcraft.entity.TrainingDummyEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 2 시험 — 판정 코어 + 디버그 렌더.
 *   1) 기하: 캡슐 · OBB 겹침 · 거리 (손으로 계산한 값)
 *   2) 스켈레톤: 도형이 본을 따라감 (기본 자세 위치 · 본 회전 · 엔티티 방향)
 *   3) 완료 조건: 한 스텝 36° · 90° 로 도는 막대가 스윕을 켜면 매 바퀴 명중, 끄면 건너뜀(터널링)
 *   4) 디버그 렌더: 도형이 그려짐 + 스크린샷 (run/screenshots)
 */
public final class Phase2ClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		geometry();
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:stone");
			sp.getConnection().waitForChunksRender();
			home(ctx, sp);
			skeleton(ctx, sp);
			tunneling(ctx, sp, 2160.0F);
			tunneling(ctx, sp, 5000.0F); // 360 의 약수가 아닌 스텝(83.3°) — 약수면 매 바퀴 같은 각도만 샘플링돼 비교가 안 됨
			render(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 2 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 2 전부 통과");
	}

	// ── 1) 기하 ─────────────────────────────────────────────

	private static void geometry() {
		Shape.Capsule c1 = new Shape.Capsule(new Vec3(0, 0, 0), new Vec3(0, 2, 0), 0.5);
		Shape.Capsule c2 = new Shape.Capsule(new Vec3(0.9, 1, -1), new Vec3(0.9, 1, 1), 0.5);
		Shape.Capsule c3 = new Shape.Capsule(new Vec3(1.1, 1, -1), new Vec3(1.1, 1, 1), 0.5);
		expect(Geometry.test(c1, c2) != null, "캡슐-캡슐: 거리 0.9 < 1.0 인데 안 겹침");
		expect(Geometry.test(c1, c3) == null, "캡슐-캡슐: 거리 1.1 > 1.0 인데 겹침");

		// 45° 돌린 OBB (반길이 1) — 대각선 방향 모서리까지 √2
		double s = Math.sqrt(0.5);
		Shape.Obb box = new Shape.Obb(Vec3.ZERO, new Vec3(s, 0, s), new Vec3(0, 1, 0), new Vec3(-s, 0, s), new Vec3(1, 1, 1));
		double d = Geometry.distanceToObb(new Vec3(2, 0, 0), box);
		expect(Math.abs(d - (2 - Math.sqrt(2))) < 1.0E-6, "점-OBB 거리 " + d + " (기대 " + (2 - Math.sqrt(2)) + ")");
		// 캡슐이 모서리 앞 0.55 → 반지름 0.6 이면 닿고 0.5 면 안 닿음
		Vec3 a = new Vec3(Math.sqrt(2) + 0.55, -3, 0);
		Vec3 b = new Vec3(Math.sqrt(2) + 0.55, 3, 0);
		expect(Geometry.test(new Shape.Capsule(a, b, 0.6), box) != null, "캡슐-OBB: 닿아야 함");
		expect(Geometry.test(new Shape.Capsule(a, b, 0.5), box) == null, "캡슐-OBB: 안 닿아야 함");
		// OBB-OBB: 축 정렬 박스 vs 45° 박스 — 분리축이 대각선일 때만 걸러지는 배치
		Shape.Obb aligned = new Shape.Obb(new Vec3(2.3, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), new Vec3(1, 1, 1));
		expect(Geometry.test(aligned, box) != null, "OBB-OBB: 1.3 < √2 인데 안 겹침");
		Shape.Obb far = new Shape.Obb(new Vec3(2.5, 0, 0), new Vec3(1, 0, 0), new Vec3(0, 1, 0), new Vec3(0, 0, 1), new Vec3(1, 1, 1));
		expect(Geometry.test(far, box) == null, "OBB-OBB: 1.5 > √2 인데 겹침");
		log("기하: 캡슐-캡슐 · 점-OBB 거리 %.4f · 캡슐-OBB · OBB-OBB(SAT) 확인", d);
	}

	// ── 2) 스켈레톤 ─────────────────────────────────────────

	private static void skeleton(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		Vec3[] r = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			TrainingDummyEntity dummy = HuntEntities.TRAINING_DUMMY.create(level, EntitySpawnReason.COMMAND);
			dummy.snapTo(10.5, Y + 1, 10.5, 90.0F, 0.0F);
			dummy.setAnimate(false);
			ColliderDef def = Defs.collider("training_dummy");
			Transform[] bones = ColliderInstance.solve(dummy, 0.0F, null);
			Shape.Capsule head = (Shape.Capsule) box(def.hurtboxes, "head").shape().toWorld(bones[box(def.hurtboxes, "head").bone()]);
			// 몸통 본을 Y 로 90° 돌리면 왼팔 캡슐이 몸통 축(피벗) 둘레로 90° 돌아야 함
			ColliderDef.Box arm = box(def.hurtboxes, "arm_left");
			Shape.Capsule arm0 = (Shape.Capsule) arm.shape().toWorld(bones[arm.bone()]);
			Pose pose = new Pose(def.skeleton.size());
			pose.rotate(def.skeleton.indexOf("body"), 0.0, 90.0, 0.0);
			Transform[] turned = new Transform[def.skeleton.size()];
			def.skeleton.solve(pose, dummy.root(0.0F), turned);
			Shape.Capsule arm1 = (Shape.Capsule) arm.shape().toWorld(turned[arm.bone()]);
			return new Vec3[] {head.a(), arm0.a(), arm1.a(), dummy.position()};
		});
		Vec3 headRel = r[0].subtract(r[3]);
		log("스켈레톤: 머리 중심 기준 위치 (%.3f, %.3f, %.3f) (기대 높이 1.750)", headRel.x, headRel.y, headRel.z);
		expect(Math.abs(headRel.y - 1.75) < 1.0E-6 && Math.hypot(headRel.x, headRel.z) < 1.0E-6, "머리 도형 위치가 본과 안 맞음");
		Vec3 pivot = r[3].add(0, 12 / 16.0, 0);
		Vec3 v0 = r[1].subtract(pivot);
		Vec3 v1 = r[2].subtract(pivot);
		double h0 = Math.hypot(v0.x, v0.z);
		double h1 = Math.hypot(v1.x, v1.z);
		double angle = Math.toDegrees(Math.acos((v0.x * v1.x + v0.z * v1.z) / (h0 * h1)));
		log("스켈레톤: 몸통 90° 회전 → 왼팔이 몸통 축 둘레로 %.1f° 이동 (반지름 %.3f → %.3f, 높이 %.3f → %.3f)", angle, h0, h1, v0.y, v1.y);
		expect(Math.abs(angle - 90.0) < 0.01 && Math.abs(h0 - h1) < 1.0E-6 && Math.abs(v0.y - v1.y) < 1.0E-6, "본 회전을 도형이 안 따라감");
	}

	private static ColliderDef.Box box(List<ColliderDef.Box> list, String id) {
		for (ColliderDef.Box b : list) {
			if (b.id().equals(id)) {
				return b;
			}
		}
		throw new AssertionError("도형 없음: " + id);
	}

	// ── 3) 터널링 ───────────────────────────────────────────

	private static void tunneling(ClientGameTestContext ctx, TestSingleplayerContext sp, float speed) {
		home(ctx, sp);
		int[] ids = sp.getServer().computeOnServer(server -> {
			SwingRodEntity[] rods = ColliderTestCommands.spawnPairs(server.overworld(), new Vec3(0.5, Y + 1, 0.5), 0.0F, speed);
			return new int[] {rods[0].getId(), rods[1].getId()};
		});
		// 3초 (도는 동안 계속 판정)
		ctx.waitTicks(180);
		int[][] res = sp.getServer().computeOnServer(server -> new int[][] {
				ColliderTestCommands.result((SwingRodEntity) server.overworld().getEntity(ids[0])),
				ColliderTestCommands.result((SwingRodEntity) server.overworld().getEntity(ids[1]))});
		double onRate = res[0][0] == 0 ? 0 : res[0][1] / (double) res[0][0];
		double offRate = res[1][0] == 0 ? 0 : res[1][1] / (double) res[1][0];
		log("막대 초당 %.0f° (스텝 %.0f°, 칼끝 스텝당 %.2f블록): 스윕 켬 %d/%d바퀴 명중 (%.0f%%, 명중 %d회) · 스윕 끔 %d/%d바퀴 (%.0f%%)",
				speed, speed / 60.0, 3.0 * Math.toRadians(speed / 60.0), res[0][1], res[0][0], onRate * 100, res[0][2],
				res[1][1], res[1][0], offRate * 100);
		expect(res[0][0] >= 5 && res[0][1] == res[0][0], "스윕을 켰는데 명중하지 않은 바퀴가 있음 (관통)");
		expect(offRate < 0.8, "스윕을 꺼도 거의 다 맞음 — 시험 조건이 터널링을 못 만듦");
		// 인스턴스 규칙: 한 바퀴에 같은 부위는 1번 → 명중 수 ≤ 바퀴 × 부위 수
		expect(res[0][2] <= (res[0][0] + 1) * 6, "같은 인스턴스에 같은 부위가 여러 번 맞음");
		sp.getServer().runOnServer(server -> ColliderTestCommands.clear(server.overworld()));
	}

	// ── 4) 렌더 ─────────────────────────────────────────────

	private static void render(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		sp.getServer().runOnServer(server -> ColliderTestCommands.spawnPairs(server.overworld(), new Vec3(0.5, Y + 1, 0.5), 0.0F, 360.0F));
		ctx.runOnClient(mc -> {
			DebugState.showShapes = true;
			ShoulderCamera.setAngles(0.0F, 25.0F);
		});
		ctx.waitTicks(40);
		int shapes = ctx.computeOnClient(mc -> ShapeDebugRenderer.drawnShapes());
		log("디버그 렌더: 도형 %d개 그림", shapes);
		expect(shapes >= 14, "디버그 도형이 그려지지 않음: " + shapes);
		Path shot = ctx.takeScreenshot("huntcraft_phase2_shapes");
		log("스크린샷: %s", shot);
		// 막대가 더미를 지나가는 순간도 한 장
		ctx.waitTicks(23);
		Path shot2 = ctx.takeScreenshot("huntcraft_phase2_hit");
		log("스크린샷: %s", shot2);
		ctx.runOnClient(mc -> DebugState.showShapes = false);
	}

	// ── 도우미 ──────────────────────────────────────────────

	private static void home(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			p.teleportTo(0.5, Y + 1, 0.5);
			p.setYRot(0.0F);
		});
		ctx.waitTicks(20);
		ctx.runOnClient(mc -> mc.player.setYRot(0.0F));
		ctx.waitTicks(5);
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
