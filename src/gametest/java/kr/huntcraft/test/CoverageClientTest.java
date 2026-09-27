package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.debug.CoverageRenderer;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.Coverage;
import kr.huntcraft.monster.CoverageCommands;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 사각지대 커버리지 (Step 0):
 *   네 가지 (선회 반영 / 미반영 × 선택 조건 반영 / 미반영) 표를 콘솔에 · 맵을 받아 바닥에 그림 (위에서 스크린샷)
 *   시뮬레이터 교차 확인: 몇 자리에 헌터를 실제로 세우고 공격 → 맞았는지가 시뮬레이션 (선회 반영) 과 같은지
 */
public final class CoverageClientTest implements FabricClientGameTest {
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
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:grass_block");
			sp.getServer().runCommand("fill -30 " + (Y - 1) + " -30 30 " + (Y - 1) + " 30 minecraft:dirt");
			sp.getServer().runCommand("fill -30 " + (Y + 1) + " -30 30 " + (Y + 14) + " 30 minecraft:air");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 12.5 180 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			Coverage.Result tracked = null;
			// {선회, 선택 조건, 각도, 근접 보조 판정}
			boolean[][] runs = {{true, false, true, false}, {true, false, true, true}, {true, true, false, false}, {true, true, false, true},
					{true, true, true, false}, {true, true, true, true}, {false, false, true, true}};
			java.util.function.Predicate<Coverage.Cell> near = c -> c.zone().equals("inner") || c.zone().equals("close");
			double[] completion = new double[2];
			for (boolean[] run : runs) {
				long t0 = System.nanoTime();
				Coverage.Result r = sp.getServer().computeOnServer(server -> {
					CoverageCommands.set(run[0], run[1], false, false, Set.of());
					CoverageCommands.setAngles(run[2]);
					CoverageCommands.setInner(run[3]);
					Coverage.Result res = CoverageCommands.compute(golem(server).def());
					CoverageCommands.set(true, false, false, false, Set.of());
					return res;
				});
				log("── 계산 %.1f초 · 점 %d개", (System.nanoTime() - t0) / 1.0E9, r.cells().size());
				for (String line : Coverage.table(r)) {
					log("%s", line);
				}
				double red = Coverage.redPercent(r, near);
				double redNoCenter = Coverage.redPercent(r, near.and(c -> !c.dir().equals("under")));
				log("   => 밀착 + 근접 빨강 %.1f%% (하부 빼면 %.1f%%)", red, redNoCenter);
				if (run[0] && run[1] && !run[2]) {
					completion[run[3] ? 1 : 0] = red;
				}
				if (run[0] && !run[1] && run[3]) {
					tracked = r;
				}
			}
			log("완료 조건 (선회 반영 · 거리로 고를 수 있는 공격): 밀착 + 근접 빨강 %.1f%% => %.1f%%", completion[0], completion[1]);
			expect(completion[1] <= 10.0, "밀착 + 근접 빨강이 10퍼센트를 넘음: " + completion[1]);
			// 맵 그리기 (선회 반영 · 선택 조건 미반영) — 위에서 내려다봄
			Coverage.Result show = tracked;
			WAVES = sp.getServer().computeOnServer(server -> {
				CoverageCommands.set(true, false, true, false, Set.of());
				Coverage.Result r = CoverageCommands.compute(golem(server).def());
				CoverageCommands.set(true, false, false, false, Set.of());
				return r;
			});
			sp.getServer().runOnServer(server -> CoverageCommands.send(server.getPlayerList().getPlayers(), golem(server), show));
			sp.getServer().runCommand("gamemode spectator @a");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 16) + " 0.6 0 90");
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> mc.player.setXRot(90.0F));
			ctx.waitTicks(5);
			expect(ctx.computeOnClient(mc -> CoverageRenderer.cells()) == show.cells().size(), "커버리지 맵을 받지 못함");
			ctx.takeScreenshot("coverage_map_tracked");
			// 실제 사각지대: 선회 반영 · 1페이즈에 거리로 고를 수 있는 공격만 · 충격파 제외
			sp.getServer().runOnServer(server -> {
				CoverageCommands.set(true, true, false, false, Set.of());
				CoverageCommands.setAngles(false);
				CoverageCommands.send(server.getPlayerList().getPlayers(), golem(server), CoverageCommands.compute(golem(server).def()));
				CoverageCommands.set(true, false, false, false, Set.of());
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("coverage_map_selectable");
			sp.getServer().runCommand("gamemode survival @a");

			// 교차 확인: 시뮬레이션이 맞다 / 안 맞다 한 자리에 실제로 세워 봄
			// 뒤 4개는 근접 보조 판정 자리 (앞가슴 · 허리 · 주먹 충격 · 목)
			String[][] probes = {{"swing_1", "0", "3.5"}, {"swing_1", "0", "-2"}, {"stomp", "1", "2"}, {"stomp", "0", "-2.5"},
					{"sweep_1", "-2.5", "0"}, {"delayed_slam", "0", "1.5"}, {"swing_3", "2.5", "0.5"},
					{"swing_1", "0", "1.3"}, {"sweep_1", "0", "-1.6"}, {"delayed_slam", "1.5", "1.0"}, {"grab_reach", "0", "1.3"}};
			int agree = 0;
			for (String[] pr : probes) {
				double x = Double.parseDouble(pr[1]);
				double z = Double.parseDouble(pr[2]);
				Coverage.Cell cell = nearest(show, x, z);
				// 교차 확인은 충격파 포함 (실제 싸움에서도 맞음)
				boolean predicted = cell.hits().contains(pr[0]) || waveHit(pr[0], x, z);
				boolean real = realHit(ctx, sp, pr[0], x, z);
				log("교차 확인 %s @ (%.1f, %.1f) %s/%s: 시뮬 %s · 실제 %s", pr[0], x, z, Coverage.ko(cell.zone()), Coverage.ko(cell.dir()), predicted ? "맞음" : "빗나감",
						real ? "맞음" : "빗나감");
				if (predicted == real) {
					agree++;
				}
			}
			log("교차 확인 %d / %d 일치", agree, probes.length);
			expect(agree >= probes.length - 1, "시뮬레이션이 실제 판정과 다름: " + agree + " / " + probes.length);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("커버리지 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("커버리지 시험 전부 통과");
	}

	private static boolean waveHit(String attack, double x, double z) {
		return WAVES != null && nearest(WAVES, x, z).hits().contains(attack);
	}

	private static Coverage.Result WAVES;

	private static Coverage.Cell nearest(Coverage.Result r, double x, double z) {
		Coverage.Cell best = null;
		double bd = Double.MAX_VALUE;
		for (Coverage.Cell c : r.cells()) {
			double d = Math.hypot(c.x() - x, c.z() - z);
			if (d < bd) {
				bd = d;
				best = c;
			}
		}
		return best;
	}

	/** 보스를 원점 +Z 로 세우고 헌터를 (x, z) (보스 기준) 에 세운 뒤 공격 → 체력이 줄었는지 */
	private static boolean realHit(ClientGameTestContext ctx, TestSingleplayerContext sp, String attack, double x, double z) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 600);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.startAction("idle");
			g.teleportTo(0.5, Y + 1, 0.5);
			g.setYRot(0.0F);
			g.setDeltaMovement(Vec3.ZERO);
			HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
		});
		float yaw = (float) Math.toDegrees(Math.atan2(x, -z));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f 0", 0.5 + x, Y + 1, 0.5 + z, yaw));
		for (int i = 0; i < 15; i++) {
			ctx.waitTick();
			ctx.runOnClient(mc -> ShoulderCamera.setAngles(yaw, -10.0F));
		}
		double hp0 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		// 서버가 행동을 고르지 않게 (setYRot 이 움직임에 끌려가지 않게) 매 틱 헌터 자리 고정
		sp.getServer().runOnServer(server -> golem(server).startAction(attack));
		for (int t = 0; t < 260; t++) {
			ctx.waitTick();
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f 0", 0.5 + x, Y + 1, 0.5 + z, yaw));
			String a = sp.getServer().computeOnServer(server -> golem(server).actionId());
			if (!attack.equals(a)) {
				break;
			}
		}
		double hp1 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		boolean held = sp.getServer().computeOnServer(server -> HunterServer.held(player(server)) || "grab_hold".equals(golem(server).actionId()));
		if (hp1 < hp0) {
			log("   %s 대미지 %.1f", attack, hp0 - hp1);
		}
		return hp1 < hp0 || held;
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
