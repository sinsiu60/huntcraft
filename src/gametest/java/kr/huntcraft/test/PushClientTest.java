package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.PushForce;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.Coverage;
import kr.huntcraft.monster.CoverageCommands;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Step 2 — 근접 압박 · 밀어내기 패턴:
 *   압박: 밀착이면 오르고 멀어지면 내려감
 *   완료 조건: 배 밑 · 뒤 · 옆에 계속 붙어 있으면 10초 안에 밀어내기 패턴이 나옴 (자리마다 여러 번)
 *   회피: 다섯 패턴 전부 구르기로 피함 (짓밟기 충격파는 점프로도)
 *   외력: 몸 털기에 맞으면 보스 반대쪽으로 밀려남 (감속) · 벽에 막히면 그 자리에서 멈춤
 *   소리: 밀어내기 패턴은 공통 경고음 (golem.warn_push), 깔아뭉개기는 가드 불가 경고 (golem.warn) 도
 */
public final class PushClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<String> HEARD = new ArrayList<>();
	private static int golemId;
	private static final String[] PUSH = {"shake_off", "stomp_both", "arm_spin", "body_crush", "steam_vent"};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		SoundEventListener listener = (instance, set, range) -> {
			if (instance.getIdentifier().getNamespace().equals("huntcraft")) {
				HEARD.add(instance.getIdentifier().getPath());
			}
		};
		ctx.runOnClient(mc -> mc.getSoundManager().addListener(listener));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -30 " + Y + " -30 30 " + Y + " 30 minecraft:grass_block");
			sp.getServer().runCommand("fill -30 " + (Y - 1) + " -30 30 " + (Y - 1) + " 30 minecraft:dirt");
			sp.getServer().runCommand("fill -30 " + (Y + 1) + " -30 30 " + (Y + 14) + " 30 minecraft:air");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 8.5 180 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			pressure(ctx, sp);
			forceAndSound(ctx, sp);
			wall(ctx, sp);
			for (String p : PUSH) {
				dodge(ctx, sp, p, false);
			}
			dodge(ctx, sp, "stomp_both", true);
			appears(ctx, sp);
			coverage(sp);
		} finally {
			ctx.runOnClient(mc -> mc.getSoundManager().removeListener(listener));
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("밀어내기 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("밀어내기 시험 전부 통과");
	}

	/** 압박: 밀착 2초 → 오름 · 멀리 2초 → 내려감 */
	private static void pressure(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 0.0, 1.2);
		sp.getServer().runOnServer(server -> golem(server).setPressure(0.0F));
		// 목표를 잡도록 잠깐 AI 켬 (행동은 idle 로 되돌림)
		hold(ctx, sp, 0.0, 1.2, 120, true);
		float up = sp.getServer().computeOnServer(server -> golem(server).pressure());
		sp.getServer().runOnServer(server -> golem(server).setAi(false));
		reset(ctx, sp, 0.0, 9.0);
		hold(ctx, sp, 0.0, 9.0, 120, false);
		float down = sp.getServer().computeOnServer(server -> golem(server).pressure());
		log("근접 압박: 밀착 2초 → %.0f · 멀리 2초 → %.0f", up, down);
		expect(up > 30.0F, "밀착해도 압박이 오르지 않음: " + up);
		expect(down < up, "멀어져도 압박이 내려가지 않음");
	}

	/** 몸 털기에 맞음 → 대미지 낮음 · 보스 반대쪽으로 밀려남 (외력) + 경고음 · 깔아뭉개기 경고음 */
	private static void forceAndSound(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		reset(ctx, sp, 0.0, 1.8);
		int from = HEARD.size();
		double hp0 = hp(sp);
		Vec3 p0 = ctx.computeOnClient(mc -> mc.player.position());
		double pushed0 = ctx.computeOnClient(mc -> PushForce.traveled());
		run(ctx, sp, "shake_off", -1, null);
		Vec3 p1 = ctx.computeOnClient(mc -> mc.player.position());
		double pushed = ctx.computeOnClient(mc -> PushForce.traveled()) - pushed0;
		double moved = Math.hypot(p1.x - p0.x, p1.z - p0.z);
		double away = (p1.z - p0.z);
		List<String> ids = new ArrayList<>(HEARD.subList(from, HEARD.size()));
		log("몸 털기: 대미지 %.1f · 외력 %.2f칸 · 실제 이동 %.2f칸 (보스 반대쪽 %.2f) · 소리 %s", hp0 - hp(sp), pushed, moved, away, ids);
		expect(hp0 - hp(sp) > 0.0 && hp0 - hp(sp) < 20.0, "몸 털기 대미지가 낮지 않음: " + (hp0 - hp(sp)));
		expect(pushed > 3.0 && away > 2.5, "몸 털기에 밀려나지 않음: 외력 " + pushed + " · 반대쪽 " + away);
		expect(ids.contains("golem.warn_push") && !ids.contains("golem.warn") && !ids.contains("golem.kick"), "몸 털기 경고음이 다름: " + ids);
		reset(ctx, sp, 0.0, 1.8);
		from = HEARD.size();
		run(ctx, sp, "body_crush", -1, null);
		ids = new ArrayList<>(HEARD.subList(from, HEARD.size()));
		log("깔아뭉개기 소리: %s", ids);
		expect(ids.contains("golem.warn_push") && ids.contains("golem.warn"), "깔아뭉개기에 밀어내기 · 가드 불가 경고음이 없음: " + ids);
	}

	/** 벽: 헌터 바로 뒤 (보스 반대쪽) 에 벽 → 밀려나다 벽에서 멈춤 (벽을 뚫거나 더 굳지 않음) */
	private static void wall(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int wz = 4;
		sp.getServer().runCommand("fill -4 " + (Y + 1) + " " + wz + " 4 " + (Y + 4) + " " + wz + " minecraft:stone");
		reset(ctx, sp, 0.0, 2.0);
		run(ctx, sp, "shake_off", -1, null);
		Vec3 p = ctx.computeOnClient(mc -> mc.player.position());
		String motion = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
		log("벽 앞 밀어내기: 헌터 z %.2f (벽 %d) · 지금 모션 %s", p.z, wz, motion);
		expect(p.z < wz + 0.01, "벽을 뚫고 밀려남: " + p.z);
		expect(motion == null, "벽에 부딪힌 뒤에도 경직이 이어짐: " + motion);
		sp.getServer().runCommand("fill -4 " + (Y + 1) + " " + wz + " 4 " + (Y + 4) + " " + wz + " minecraft:air");
	}

	/** 판정 직전에 뒤로 구르기 (jump = 짓밟기는 점프) → 대미지 없음 */
	private static void dodge(ClientGameTestContext ctx, TestSingleplayerContext sp, String attack, boolean jump) {
		reset(ctx, sp, 0.0, 1.8);
		double hp0 = hp(sp);
		int[] at = sp.getServer().computeOnServer(server -> {
			MonsterDef.Action a = golem(server).def().actions.get(attack);
			if (!a.hits().isEmpty()) {
				return new int[] {a.hits().getFirst().from()};
			}
			// 충격파: 두 번 (짓밟기)
			return a.events().stream().filter(e -> e.shockwave() != null).mapToInt(kr.huntcraft.monster.ActionFx.Event::frame).toArray();
		});
		run(ctx, sp, attack, jump ? 3 : 5, at);
		double lost = hp0 - hp(sp);
		log("회피 %s (%s): 대미지 %.1f", attack, jump ? "점프" : "구르기", lost);
		expect(lost <= 0.0, attack + " 를 " + (jump ? "점프" : "구르기") + "로 피하지 못함: 대미지 " + lost);
	}

	/** 완료 조건: 배 밑 · 뒤 · 옆에 붙어 있으면 (AI 켬) 10초 안에 밀어내기 패턴 */
	private static void appears(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		double[][] spots = {{0.0, 0.3}, {0.0, -1.4}, {1.7, 0.0}, {-1.7, 0.0}};
		String[] names = {"배 밑", "뒤", "왼쪽", "오른쪽"};
		for (int s = 0; s < spots.length; s++) {
			for (int trial = 0; trial < 3; trial++) {
				reset(ctx, sp, spots[s][0], spots[s][1]);
				double x = spots[s][0];
				double z = spots[s][1];
				sp.getServer().runOnServer(server -> {
					golem(server).setPressure(0.0F);
					golem(server).setAi(true);
				});
				String found = null;
				int t;
				for (t = 0; t < 600 && found == null; t++) {
					ctx.waitTick();
					sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f", 0.5 + x, Y + 1, 0.5 + z));
					sp.getServer().runOnServer(server -> HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth);
					String a = action(sp);
					for (String p : PUSH) {
						if (p.equals(a)) {
							found = a;
						}
					}
				}
				sp.getServer().runOnServer(server -> golem(server).setAi(false));
				log("%s 밀착 #%d: %s (%.1f초)", names[s], trial + 1, found == null ? "밀어내기 없음" : found, t / 60.0);
				expect(found != null, names[s] + " 에 붙어 있었는데 10초 안에 밀어내기가 나오지 않음");
			}
		}
		// 쿨다운 · 전역 (이 시험이 남긴 상태 정리)
		ctx.waitTicks(60);
	}

	private static void coverage(TestSingleplayerContext sp) {
		Coverage.Result r = sp.getServer().computeOnServer(server -> {
			CoverageCommands.set(true, true, false, false, Set.of());
			Coverage.Result res = CoverageCommands.compute(golem(server).def());
			CoverageCommands.set(true, false, false, false, Set.of());
			return res;
		});
		for (String line : Coverage.table(r)) {
			log("%s", line);
		}
		log("   => 밀착 + 근접 빨강 (선회 · 거리 · 각도 · 1페이즈) %.1f%%",
				Coverage.redPercent(r, c -> c.zone().equals("inner") || c.zone().equals("close")));
	}

	/**
	 * 보스를 원점 +Z 로 세우고 행동 시작 → 끝날 때까지. evadeLead ≥ 0 이면 at 의 각 프레임 evadeLead 전에 뒤로 구르기
	 * (evadeLead == 3 → 점프). 헌터 체력은 그대로 둠.
	 */
	private static void run(ClientGameTestContext ctx, TestSingleplayerContext sp, String attack, int evadeLead, int[] at) {
		sp.getServer().runOnServer(server -> golem(server).startAction(attack));
		int next = 0;
		for (int t = 0; t < 320; t++) {
			ctx.waitTick();
			faceGolem(ctx);
			double f = sp.getServer().computeOnServer(server -> golem(server).frame(0.0F));
			if (evadeLead >= 0 && at != null && next < at.length && f >= at[next] - evadeLead - (evadeLead == 3 ? 4 : 0)) {
				next++;
				if (evadeLead == 3) {
					ctx.getInput().pressKey(HuntKeys.jump);
				} else {
					ctx.getInput().holdKey(o -> o.keyDown);
					ctx.waitTick();
					ctx.getInput().pressKey(HuntKeys.evade);
					ctx.waitTicks(3);
					ctx.getInput().releaseKey(o -> o.keyDown);
				}
			}
			if (!attack.equals(action(sp))) {
				break;
			}
		}
		ctx.waitTicks(20);
	}

	private static void reset(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null && !PushForce.active(), 600);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.setAi(false);
			g.startAction("idle");
			g.teleportTo(0.5, Y + 1, 0.5);
			g.setYRot(0.0F);
			g.setDeltaMovement(Vec3.ZERO);
			var st = HunterServer.get(player(server));
			st.health = CommonConfig.get().player.maxHealth;
			st.stamina = CommonConfig.get().stamina.max;
		});
		float yaw = (float) Math.toDegrees(Math.atan2(x, -z));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f %.1f 0", 0.5 + x, Y + 1, 0.5 + z, yaw));
		for (int i = 0; i < 15; i++) {
			ctx.waitTick();
			faceGolem(ctx);
		}
	}

	/** 헌터를 제자리에 ticks 동안 (ai = 보스 목표 잡기용) */
	private static void hold(ClientGameTestContext ctx, TestSingleplayerContext sp, double x, double z, int ticks, boolean ai) {
		sp.getServer().runOnServer(server -> golem(server).setAi(ai));
		for (int t = 0; t < ticks; t++) {
			ctx.waitTick();
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %d %.2f", 0.5 + x, Y + 1, 0.5 + z));
			sp.getServer().runOnServer(server -> {
				golem(server).startAction("idle");
				HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
			});
		}
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
