package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.movement.PushForce;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.ActionFx;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Step 3 — 보스가 직접 거리를 벌리는 재배치 패턴:
 *   이동: 백스텝 5~7칸 뒤로 · 사이드 스텝 옆으로 짧게 · 관통 돌진은 헌터를 지나 반대편 · 도약 내려찍기는 헌터 자리에 떨어짐
 *   경로 검사: 뒤가 막히면 백스텝 대신 옆 걸음 · 옆까지 막히면 재배치 안 함 · 천장이 있으면 높이 도약 안 함 · 옆 걸음은 헌터 반대편
 *   완료 조건: 근접전 1분 동안 밀어내기와 재배치가 섞여서 나옴 · 재배치 뒤 연계 공격이 매번 같지 않음
 */
public final class RepositionClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final int FIELD = 40;
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
			field(sp);
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 8.5 180 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			moves(ctx, sp);
			paths(ctx, sp);
			closeFight(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("재배치 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("재배치 시험 전부 통과");
	}

	private static void field(TestSingleplayerContext sp) {
		sp.getServer().runCommand(String.format(Locale.ROOT, "fill -%d %d -%d %d %d %d minecraft:grass_block", FIELD, Y, FIELD, FIELD, Y, FIELD));
		sp.getServer().runCommand(String.format(Locale.ROOT, "fill -%d %d -%d %d %d %d minecraft:dirt", FIELD, Y - 1, FIELD, FIELD, Y - 1, FIELD));
		sp.getServer().runCommand(String.format(Locale.ROOT, "fill -%d %d -%d %d %d %d minecraft:air", FIELD, Y + 1, FIELD, FIELD, Y + 14, FIELD));
	}

	/** 실제 이동: 행동을 강제로 시작 → 끝난 뒤 보스 자리 */
	private static void moves(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 백스텝: 헌터 (앞 +Z) 반대쪽으로 5~7칸
		reset(ctx, sp, 0.0, 2.0);
		Vec3 end = run(ctx, sp, "backstep");
		double back = 0.5 - end.z;
		log("백스텝: 뒤로 %.2f칸 (옆 %.2f)", back, end.x - 0.5);
		expect(back >= 5.0 && back <= 7.5 && Math.abs(end.x - 0.5) < 1.0, "백스텝이 뒤로 5~7칸이 아님: " + end);

		// 사이드 스텝: 옆으로 짧게 (2~5칸), 앞뒤로는 거의 안 움직임
		for (String side : new String[] {"side_step_left", "side_step_right"}) {
			reset(ctx, sp, 0.0, 2.0);
			end = run(ctx, sp, side);
			double lateral = end.x - 0.5;
			log("%s: 옆 %.2f칸 · 앞뒤 %.2f칸", side, lateral, end.z - 0.5);
			// 보스는 +Z 를 봄 → 오른쪽 = -X
			boolean dir = side.endsWith("left") ? lateral > 0.0 : lateral < 0.0;
			expect(dir && Math.abs(lateral) >= 2.0 && Math.abs(lateral) <= 5.0 && Math.abs(end.z - 0.5) < 1.0, side + " 이동이 이상함: " + end);
		}

		// 관통 돌진: 헌터 (앞 2.5칸) 를 지나 반대편으로 3칸 넘게
		reset(ctx, sp, 0.0, 2.5);
		end = run(ctx, sp, "pass_charge");
		log("관통 돌진: 끝 z %.2f (헌터 z %.2f)", end.z, 0.5 + 2.5);
		expect(end.z > 0.5 + 2.5 + 3.0, "관통 돌진이 헌터를 지나치지 않음: " + end);

		// 도약 내려찍기: 헌터 (앞 4칸) 자리 근처에 떨어짐
		reset(ctx, sp, 0.0, 4.0);
		double[] top = {0.0};
		end = run(ctx, sp, "leap_slam", top);
		double miss = Math.hypot(end.x - 0.5, end.z - 4.5);
		log("도약 내려찍기: 최고 %.2f칸 · 떨어진 자리가 헌터에서 %.2f칸", top[0], miss);
		expect(top[0] >= 5.0, "높이 도약이 높지 않음: " + top[0]);
		expect(miss < 2.0, "헌터 자리로 떨어지지 않음: " + miss);
	}

	/** 경로 검사: 막히면 다른 방향 · 다른 패턴으로 */
	private static void paths(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 0.0, 2.0);
		String open = preview(sp, "backstep");
		log("열린 곳 백스텝 → %s", open);
		expect("backstep".equals(open), "열린 곳인데 백스텝을 못 함: " + open);

		// 뒤 벽 (보스 뒤 3칸)
		sp.getServer().runCommand("fill -8 " + (Y + 1) + " -3 8 " + (Y + 6) + " -3 minecraft:stone");
		reset(ctx, sp, 0.0, 2.0);
		String behind = preview(sp, "backstep");
		log("뒤가 막힘: 백스텝 → %s", behind);
		expect(behind != null && behind.startsWith("side_step"), "뒤가 막혔는데 옆 걸음으로 바뀌지 않음: " + behind);

		// 양옆 벽까지
		sp.getServer().runCommand("fill -3 " + (Y + 1) + " -3 -3 " + (Y + 6) + " 6 minecraft:stone");
		sp.getServer().runCommand("fill 4 " + (Y + 1) + " -3 4 " + (Y + 6) + " 6 minecraft:stone");
		reset(ctx, sp, 0.0, 2.0);
		String boxedBack = preview(sp, "backstep");
		String boxedSide = preview(sp, "side_step_right");
		String boxedPass = preview(sp, "pass_charge");
		log("뒤 · 양옆이 막힘: 백스텝 → %s · 옆 걸음 → %s · 관통 돌진 → %s", boxedBack, boxedSide, boxedPass);
		expect(boxedBack == null && boxedSide == null, "사방이 막혔는데 뒤 · 옆으로 재배치함: " + boxedBack + " / " + boxedSide);
		expect("pass_charge".equals(boxedPass), "앞은 열려 있는데 관통 돌진을 못 함: " + boxedPass);

		// 천장 → 높이 도약 안 함
		sp.getServer().runCommand("fill -3 " + (Y + 8) + " -3 4 " + (Y + 8) + " 6 minecraft:stone");
		reset(ctx, sp, 0.0, 2.0);
		String roof = preview(sp, "leap_slam");
		log("천장 아래: 도약 내려찍기 → %s", roof);
		expect(roof == null, "천장이 있는데 높이 도약을 함: " + roof);
		field(sp);

		// 옆 걸음: 헌터가 보스 오른쪽 (-X) 에 있으면 왼쪽 (+X) 으로
		reset(ctx, sp, -2.2, 1.0);
		String away = preview(sp, "side_step_right");
		log("헌터가 오른쪽: 옆 걸음 → %s", away);
		expect("side_step_left".equals(away), "헌터 반대편으로 옆 걸음하지 않음: " + away);
		reset(ctx, sp, 2.2, 1.0);
		away = preview(sp, "side_step_left");
		log("헌터가 왼쪽: 옆 걸음 → %s", away);
		expect("side_step_right".equals(away), "헌터 반대편으로 옆 걸음하지 않음: " + away);
	}

	/**
	 * 완료 조건: 헌터가 1분 동안 보스에게 붙어 있음 (멀어지면 달려서 다시 붙음) — 밀어내기 · 재배치가 섞여서 나오고, 재배치 뒤 연계가 매번 같지 않음
	 */
	private static void closeFight(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 0.0, 1.6);
		sp.getServer().runOnServer(server -> {
			golem(server).setPressure(0.0F);
			golem(server).setAi(true);
		});
		List<String> seq = new ArrayList<>();
		for (int t = 0; t < 60 * 60; t++) {
			ctx.waitTick();
			faceGolem(ctx);
			String a = sp.getServer().computeOnServer(server -> {
				MonsterEntity g = golem(server);
				ServerPlayer p = player(server);
				var st = HunterServer.get(p);
				st.health = CommonConfig.get().player.maxHealth;
				st.stamina = CommonConfig.get().stamina.max;
				// 붙어 있기: 보스 가운데에서 2칸 밖이면 초당 약 4칸으로 다가감 (잡혀 있으면 그대로)
				if (g.heldVictim() == null) {
					double dx = g.getX() - p.getX();
					double dz = g.getZ() - p.getZ();
					double d = Math.hypot(dx, dz);
					if (d > 2.0) {
						double step = Math.min(0.07, d - 2.0);
						p.teleportTo(p.getX() + dx / d * step, Math.max(p.getY(), Y + 1), p.getZ() + dz / d * step);
					}
				}
				return g.actionId();
			});
			if (seq.isEmpty() || !seq.getLast().equals(a)) {
				seq.add(a);
			}
		}
		sp.getServer().runOnServer(server -> golem(server).setAi(false));
		log("근접 1분 행동: %s", String.join(" → ", seq));

		MonsterDef d = sp.getServer().computeOnServer(server -> golem(server).def());
		List<Integer> pushes = new ArrayList<>();
		List<Integer> repos = new ArrayList<>();
		List<String> combos = new ArrayList<>();
		for (int i = 0; i < seq.size(); i++) {
			MonsterDef.Action a = d.actions.get(seq.get(i));
			if (a == null) {
				continue;
			}
			if (a.flow().push()) {
				pushes.add(i);
			}
			if (a.flow().repositions()) {
				repos.add(i);
				if (!a.flow().combo().isEmpty() && i + 1 < seq.size()) {
					String next = seq.get(i + 1);
					if (a.flow().combo().stream().map(ActionFx.Combo::action).anyMatch(next::equals)) {
						combos.add(a.id() + " → " + next);
					}
				}
			}
		}
		Set<String> followKinds = new HashSet<>();
		for (String c : combos) {
			followKinds.add(c.substring(c.indexOf("→") + 2));
		}
		log("밀어내기 %d번 · 재배치 %d번 · 재배치 연계 %s", pushes.size(), repos.size(), combos);
		expect(pushes.size() >= 2, "1분 동안 밀어내기가 2번 미만: " + pushes.size());
		expect(repos.size() >= 2, "1분 동안 재배치가 2번 미만: " + repos.size());
		expect(!repos.isEmpty() && !pushes.isEmpty() && pushes.getFirst() < repos.getFirst() && repos.getFirst() < pushes.getLast(),
				"밀어내기와 재배치가 섞이지 않음 (밀어내기 " + pushes + " · 재배치 " + repos + ")");
		expect(combos.size() >= 2 && followKinds.size() >= 2, "재배치 뒤 연계 공격이 매번 같음: " + combos);
	}

	/** 보스를 원점 (+Z 를 봄) 에 세우고 헌터를 (x, z) 에 — 목표를 잡을 때까지 잠깐 기다림 */
	private static void reset(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null && !PushForce.active(), 600);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.resetForFight(new Vec3(0.5, Y + 1, 0.5), 0.0F, false);
			var st = HunterServer.get(player(server));
			st.health = CommonConfig.get().player.maxHealth;
			st.stamina = CommonConfig.get().stamina.max;
		});
		float yaw = (float) Math.toDegrees(Math.atan2(x, -z));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f 0", 0.5 + x, Y + 1, 0.5 + z, yaw));
		for (int i = 0; i < 20; i++) {
			ctx.waitTick();
			faceGolem(ctx);
		}
	}

	private static Vec3 run(ClientGameTestContext ctx, TestSingleplayerContext sp, String action) {
		return run(ctx, sp, action, null);
	}

	/** 행동 강제 시작 → 끝날 때까지 (헌터는 제자리 · 체력 그대로 채움) → 보스 자리. top != null 이면 최고 높이 (시작 대비) */
	private static Vec3 run(ClientGameTestContext ctx, TestSingleplayerContext sp, String action, double[] top) {
		double y0 = sp.getServer().computeOnServer(server -> golem(server).getY());
		sp.getServer().runOnServer(server -> golem(server).startAction(action));
		for (int t = 0; t < 400; t++) {
			ctx.waitTick();
			faceGolem(ctx);
			double y = sp.getServer().computeOnServer(server -> {
				HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
				return golem(server).getY();
			});
			if (top != null) {
				top[0] = Math.max(top[0], y - y0);
			}
			if (!action.equals(sp.getServer().computeOnServer(server -> golem(server).actionId()))) {
				break;
			}
		}
		ctx.waitTicks(10);
		return sp.getServer().computeOnServer(server -> golem(server).position());
	}

	private static String preview(TestSingleplayerContext sp, String action) {
		return sp.getServer().computeOnServer(server -> golem(server).repositionPreview(action));
	}

	private static void faceGolem(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			if (mc.level.getEntity(golemId) instanceof MonsterEntity g) {
				float yaw = (float) Math.toDegrees(Math.atan2(-(g.getX() - mc.player.getX()), g.getZ() - mc.player.getZ()));
				mc.player.setYRot(yaw);
				ShoulderCamera.setAngles(yaw, -10.0F);
			}
		});
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
