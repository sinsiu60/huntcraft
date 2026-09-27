package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.GrabCinematic;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.entity.RubbleEntity;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 프루니스 공격 패턴 — 행동 흐름이 정의대로 이어지는지.
 *   잡기: 닿으면 grab_hold (손에 쥐임 → 내리꽂혀 대미지) · 멀면 grab_miss (헛잡고 휘청)
 *   잔해 던지기: 가만히 있으면 맞음 · 던진 뒤 옆으로 비키면 안 맞음
 *   뛰어올라 내려찍기: 먼 목표 앞에 떨어짐 · 돌진: 벽에 막히면 charge_wall
 *   연타: swing_1 뒤 swing_2 가 이어짐 (확률 — 여러 번 중 한 번 이상)
 */
public final class GolemPatternsClientTest implements FabricClientGameTest {
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
			sp.getServer().runCommand("fill -40 " + Y + " -40 40 " + Y + " 40 minecraft:grass_block");
			sp.getServer().runCommand("fill -40 " + (Y - 1) + " -40 40 " + (Y - 1) + " 40 minecraft:dirt");
			sp.getServer().runCommand("fill -40 " + (Y + 1) + " -40 40 " + (Y + 14) + " 40 minecraft:air");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 12.5 180 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			reach(ctx, sp);
			grab(ctx, sp);
			grabMiss(ctx, sp);
			rubble(ctx, sp, false);
			rubble(ctx, sp, true);
			leap(ctx, sp);
			chargeWall(ctx, sp);
			combo(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("골렘 패턴 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("골렘 패턴 시험 전부 통과");
	}

	/** 골렘을 (0.5, 0.5) 에 +Z 방향으로 되돌리고, 헌터를 정면 distance 칸에 세움 (체력 가득) */
	private static void reset(ClientGameTestContext ctx, TestSingleplayerContext sp, double distance) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 600);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.setAi(false);
			g.startAction("idle");
			g.teleportTo(0.5, Y + 1, 0.5);
			g.setYRot(0.0F);
			g.setDeltaMovement(Vec3.ZERO);
			HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
		});
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d %.2f 180 0", Y + 1, 0.5 + distance));
		for (int i = 0; i < 15; i++) {
			tick(ctx);
		}
	}

	/** 한 틱 + 카메라를 골렘 쪽으로 (헌터는 골렘 앞 +Z 쪽에 서므로 기본 방향이면 등을 지고 있음) */
	private static void tick(ClientGameTestContext ctx) {
		ctx.waitTick();
		ctx.runOnClient(mc -> {
			if (mc.level.getEntity(golemId) instanceof MonsterEntity g) {
				double dx = g.getX() - mc.player.getX();
				double dz = g.getZ() - mc.player.getZ();
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				// 가까우면 큰 골렘이 화면에 들어오게 조금 올려다봄
				float pitch = (float) -Math.toDegrees(Math.atan2(2.0, Math.max(3.0, Math.hypot(dx, dz) + 3.75)));
				mc.player.setYRot(yaw);
				ShoulderCamera.setAngles(yaw, pitch);
			}
		});
	}

	/** 근접 공격이 정면 3.3칸에 선 헌터에게 실제로 닿는지 (판정 구간에 팔이 헌터 높이 · 거리를 지나가야 함) */
	private static void reach(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		String[] attacks = {"swing_1", "swing_2", "swing_3", "delayed_slam", "sweep_1", "stomp"};
		List<String> missed = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String a : attacks) {
			reset(ctx, sp, 3.3);
			double hp0 = hp(sp);
			sp.getServer().runOnServer(server -> golem(server).startAction(a));
			for (int t = 0; t < 200; t++) {
				tick(ctx);
				if (!a.equals(action(sp))) {
					break;
				}
			}
			double lost = hp0 - hp(sp);
			line.append(String.format(Locale.ROOT, "%s -%.0f · ", a, lost));
			if (lost <= 0.0) {
				missed.add(a);
			}
		}
		log("근접 공격 사거리 (정면 3.3칸): %s", line);
		expect(missed.isEmpty(), "헌터에게 닿지 않는 근접 공격: " + missed);
	}

	private static void grab(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 4.5);
		double hp0 = hp(sp);
		sp.getServer().runOnServer(server -> golem(server).startAction("grab_reach"));
		boolean held = false;
		String next = null;
		double cine = 0.0;
		for (int t = 0; t < 300; t++) {
			tick(ctx);
			held |= sp.getServer().computeOnServer(server -> HunterServer.held(player(server)));
			cine = Math.max(cine, ctx.computeOnClient(mc -> GrabCinematic.weight()));
			if ("grab_hold".equals(action(sp))) {
				int f = (int) Math.floor(sp.getServer().computeOnServer(server -> golem(server).frame(0.0F)));
				if (f == 20 || f == 50 || f == 70 || f == 95 || f == 118) {
					ctx.takeScreenshot("golem_grab_stare_f" + f);
				}
			}
			String a = action(sp);
			if (next == null && !"grab_reach".equals(a)) {
				next = a;
			}
			if ("grab_hold".equals(next) && !"grab_hold".equals(a)) {
				break;
			}
		}
		for (int t = 0; t < 10; t++) {
			tick(ctx);
		}
		boolean still = sp.getServer().computeOnServer(server -> HunterServer.held(player(server)));
		double hp1 = hp(sp);
		double cineAfter = ctx.computeOnClient(mc -> GrabCinematic.weight());
		log("잡기 (4.5칸): 다음 행동 %s · 쥐임 %s · 끝난 뒤 쥐임 %s · 체력 %.0f → %.0f · 카메라 연출 최대 %.2f → 끝난 뒤 %.2f", next, held, still,
				hp0, hp1, cine, cineAfter);
		expect(cine > 0.99 && cineAfter < 0.01, "잡힘 카메라 연출이 들어갔다 나오지 않음 (최대 " + cine + ", 끝난 뒤 " + cineAfter + ")");
		expect("grab_hold".equals(next), "잡기가 닿았는데 grab_hold 로 넘어가지 않음: " + next);
		expect(held && !still, "쥐었다가 놓지 않음 (쥐임 " + held + ", 끝난 뒤 " + still + ")");
		expect(hp1 < hp0, "내리꽂기 대미지가 없음");
	}

	private static void grabMiss(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 12.0);
		sp.getServer().runOnServer(server -> golem(server).startAction("grab_reach"));
		String next = null;
		for (int t = 0; t < 120 && next == null; t++) {
			tick(ctx);
			String a = action(sp);
			if (!"grab_reach".equals(a)) {
				next = a;
			}
		}
		log("잡기 (12칸, 헛잡음): 다음 행동 %s", next);
		expect("grab_miss".equals(next), "헛잡았는데 grab_miss 가 아님: " + next);
	}

	private static void rubble(ClientGameTestContext ctx, TestSingleplayerContext sp, boolean sidestep) {
		reset(ctx, sp, 16.0);
		int hits0 = RubbleEntity.hits();
		double hp0 = hp(sp);
		sp.getServer().runOnServer(server -> golem(server).startAction("throw"));
		boolean moved = false;
		boolean gripped = false;
		for (int t = 0; t < 240; t++) {
			tick(ctx);
			double frame = sp.getServer().computeOnServer(server -> golem(server).frame(0.0F));
			if (frame > 60 && frame < 95 && !gripped) {
				gripped = sp.getServer().computeOnServer(server -> !server.overworld().getEntitiesOfClass(RubbleEntity.class,
						golem(server).getBoundingBox().inflate(6.0)).isEmpty());
			}
			if (sidestep && !moved && frame >= 104) {
				// 던진 직후 옆으로 5칸 (예측 조준이 없으니 원래 자리로 떨어짐)
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 5.5 %d 16.5", Y + 1));
				moved = true;
			}
			if (frame >= 149) {
				break;
			}
		}
		ctx.waitTicks(40);
		boolean hit = RubbleEntity.hits() > hits0;
		double hp1 = hp(sp);
		log("잔해 던지기 (%s): 손에 든 잔해 %s · 맞음 %s · 체력 %.0f → %.0f", sidestep ? "던진 뒤 옆으로" : "가만히", gripped, hit, hp0, hp1);
		expect(gripped, "던지기 전에 손에 든 잔해가 없음");
		if (sidestep) {
			expect(!hit, "옆으로 비켰는데 잔해에 맞음");
		} else {
			expect(hit && hp1 < hp0, "가만히 있는데 잔해에 맞지 않음");
		}
	}

	private static void leap(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 22.0);
		sp.getServer().runOnServer(server -> golem(server).startAction("leap"));
		double peak = 0.0;
		for (int t = 0; t < 180; t++) {
			tick(ctx);
			peak = Math.max(peak, sp.getServer().computeOnServer(server -> golem(server).getY() - (Y + 1)));
			if (!"leap".equals(action(sp))) {
				break;
			}
		}
		double[] res = sp.getServer().computeOnServer(server -> {
			MonsterEntity g = golem(server);
			// 헌터가 맞아 날아갈 수 있으니 원래 서 있던 자리 (0.5, 22.5) 까지
			return new double[] {g.getZ(), g.getY() - (Y + 1), Math.hypot(g.getX() - 0.5, g.getZ() - 22.5)};
		});
		log("뛰어올라 내려찍기 (22칸): 최고 높이 %.1f · 착지 z %.1f · 높이 %.2f · 헌터 자리까지 %.1f", peak, res[0], res[1], res[2]);
		expect(peak > 3.0, "충분히 뛰어오르지 않음: " + peak);
		expect(Math.abs(res[1]) < 0.2, "착지 뒤 땅 위가 아님: " + res[1]);
		expect(res[2] < 6.0, "목표 앞에 떨어지지 않음 (남은 거리 " + res[2] + ")");
	}

	private static void chargeWall(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 20.0);
		// 골렘과 헌터 사이 z=9 에 낮은 벽 (골렘 눈높이보다 낮아 목표는 보임)
		sp.getServer().runCommand("fill -8 " + (Y + 1) + " 9 8 " + (Y + 3) + " 9 minecraft:stone_bricks");
		ctx.waitTicks(5);
		sp.getServer().runOnServer(server -> golem(server).startAction("charge"));
		String next = null;
		for (int t = 0; t < 160 && next == null; t++) {
			tick(ctx);
			String a = action(sp);
			if (!"charge".equals(a)) {
				next = a;
			}
		}
		double z = sp.getServer().computeOnServer(server -> golem(server).getZ());
		log("돌진 → 벽: 다음 행동 %s · 멈춘 z %.1f", next, z);
		expect("charge_wall".equals(next), "벽에 막혔는데 charge_wall 이 아님: " + next);
		sp.getServer().runCommand("fill -8 " + (Y + 1) + " 9 8 " + (Y + 3) + " 9 minecraft:air");
	}

	private static void combo(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int second = 0;
		int tries = 6;
		for (int i = 0; i < tries; i++) {
			reset(ctx, sp, 3.5);
			sp.getServer().runOnServer(server -> {
				golem(server).setAi(true);
				golem(server).startAction("swing_1");
			});
			String next = null;
			for (int t = 0; t < 100 && next == null; t++) {
				tick(ctx);
				String a = action(sp);
				if (!"swing_1".equals(a)) {
					next = a;
				}
			}
			sp.getServer().runOnServer(server -> golem(server).setAi(false));
			if ("swing_2".equals(next)) {
				second++;
			}
		}
		log("팔 휘두르기 연타: swing_1 %d번 중 swing_2 로 이어짐 %d번", tries, second);
		expect(second > 0, "swing_1 뒤에 swing_2 가 한 번도 이어지지 않음");
	}

	private static String action(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> golem(server).actionId());
	}

	private static double hp(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
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
