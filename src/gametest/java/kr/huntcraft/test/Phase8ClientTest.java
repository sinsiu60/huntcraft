package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.client.camera.GrabCinematic;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.hud.HuntHud;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.fx.Shockwaves;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.progress.Encounter;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 8 시험 — 처음부터 처치까지 한 판: 첫 등장 (전원 꺼진 채 앉음 → 다가가면 켜지고 일어섬 → 공격) · 2페이즈 전환 (무적 · 카메라 연출 → 빨라짐)
 *   · 새 패턴 과부하 (둥근 충격파 두 번) · 처치 연출 (머리 · 왼팔이 떨어짐, 연기) → 먼지가 되어 사라짐 · /huntcraft arena reset 으로 처음 상태.
 *   스크린샷으로 장면 확인.
 */
public final class Phase8ClientTest implements FabricClientGameTest {
	private static final int Y = 120;
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("time set 6000");
			sp.getServer().runCommand("execute positioned 0.5 " + Y + " 0.5 run huntcraft arena build");
			sp.getServer().runCommand("gamemode survival @a");
			ctx.waitTicks(20);
			ArenaBuilder.Layout l = sp.getServer().computeOnServer(ArenaStore::load);
			sp.getServer().runCommand("execute as @a at @s run huntcraft arena enter");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);

			intro(ctx, sp, l);
			phase2(ctx, sp, l);
			overload(ctx, sp, l);
			death(ctx, sp, l);
			reset(ctx, sp, l);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 8 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 8 전부 통과");
	}

	private static MonsterEntity boss(MinecraftServer server) {
		return server.overworld().getEntity(Encounter.bossId()) instanceof MonsterEntity m ? m : null;
	}

	private static void keepAlive(TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth);
	}

	/** 앉아 있음 → 안개 → 다가감 → 깨어남 (전원 · 카메라 · 체력바) → 공격 시작 */
	private static void intro(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		String before = sp.getServer().computeOnServer(server -> {
			MonsterEntity m = boss(server);
			return m == null ? "없음" : m.actionId() + " · 전원 " + m.powered() + " · 행동 " + m.ai();
		});
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.gate().getX() + 0.5, l.gate().getY(), l.gate().getZ() + 2.0));
		ctx.waitTicks(15);
		ctx.runOnClient(mc -> ShoulderCamera.setAngles(180.0F, 8.0F));
		ctx.getInput().pressKey(HuntKeys.interact);
		ctx.waitTicks(90);
		ctx.takeScreenshot("phase8_dormant");
		boolean awaiting = sp.getServer().computeOnServer(server -> Encounter.awaiting());
		boolean barBefore = ctx.computeOnClient(mc -> HuntHud.shown().bossBar());
		// 다가감 (보스 앞 10칸)
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.bossSpawn().getX() + 0.5, l.bossSpawn().getY(),
				l.bossSpawn().getZ() + 10.0));
		ctx.runOnClient(mc -> ShoulderCamera.setAngles(180.0F, 5.0F));
		boolean poweredAt80 = false;
		double cine = 0.0;
		String firstAttack = null;
		for (int t = 0; t < 700 && firstAttack == null; t++) {
			ctx.waitTick();
			keepAlive(sp);
			cine = Math.max(cine, ctx.computeOnClient(mc -> GrabCinematic.weight()));
			String[] st = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = boss(server);
				return new String[] {m.actionId(), String.valueOf(m.powered()), String.valueOf((int) m.frame(0.0F))};
			});
			if ("awaken".equals(st[0]) && Integer.parseInt(st[2]) == 40) {
				ctx.takeScreenshot("phase8_awaken_off");
			}
			if ("awaken".equals(st[0]) && Integer.parseInt(st[2]) == 110) {
				poweredAt80 = Boolean.parseBoolean(st[1]);
				ctx.takeScreenshot("phase8_awaken_on");
			}
			if ("awaken".equals(st[0]) && Integer.parseInt(st[2]) == 180) {
				ctx.takeScreenshot("phase8_awaken_rise");
			}
			if (!st[0].equals("awaken") && !st[0].equals("dormant") && !st[0].equals("idle") && !st[0].equals("chase") && !st[0].startsWith("turn")
					&& !st[0].equals("pause")) {
				firstAttack = st[0];
			}
		}
		boolean bar = ctx.computeOnClient(mc -> HuntHud.shown().bossBar());
		int awakenings = sp.getServer().computeOnServer(server -> Encounter.awakenings());
		log("첫 등장: 들어가기 전 %s · 안에서 기다림 %s · 체력바(전) %s → 다가감 → 깨어남 %d · 전원(110) %s · 카메라 연출 %.2f · 체력바 %s · 첫 공격 %s",
				before, awaiting, barBefore, awakenings, poweredAt80, cine, bar, firstAttack);
		expect(before.startsWith("dormant · 전원 false · 행동 false"), "전장에 보스가 전원 꺼진 채 앉아 있지 않음: " + before);
		expect(awaiting && !barBefore, "들어가자마자 싸움이 시작됨");
		expect(awakenings == 1 && poweredAt80 && cine > 0.99 && bar, "깨어남 연출 이상");
		expect(firstAttack != null, "깨어난 뒤 공격하지 않음");
	}

	/** 체력 50% 아래 → 전환 연출 (무적 · 카메라) → 분노 (1.2배) */
	private static void phase2(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		sp.getServer().runOnServer(server -> {
			MonsterEntity m = boss(server);
			m.setHp(m.maxHp() * 0.49F);
		});
		String seen = null;
		boolean invuln = false;
		double cine = 0.0;
		for (int t = 0; t < 900; t++) {
			ctx.waitTick();
			keepAlive(sp);
			String a = sp.getServer().computeOnServer(server -> boss(server).actionId());
			if ("phase2".equals(a)) {
				if (seen == null) {
					seen = a;
				}
				invuln |= sp.getServer().computeOnServer(server -> !boss(server).hurtboxesActive(boss(server).frame(0.0F)));
				cine = Math.max(cine, ctx.computeOnClient(mc -> GrabCinematic.weight()));
				if (sp.getServer().computeOnServer(server -> (int) boss(server).frame(0.0F)) == 60) {
					ctx.takeScreenshot("phase8_phase2");
				}
			} else if (seen != null) {
				break;
			}
		}
		boolean rage = sp.getServer().computeOnServer(server -> boss(server).enraged());
		float speed = sp.getServer().computeOnServer(server -> boss(server).speedScale());
		log("2페이즈: 전환 %s · 무적 %s · 카메라 연출 %.2f · 분노 %s · 속도 %.2f", seen, invuln, cine, rage, speed);
		expect("phase2".equals(seen) && invuln && cine > 0.99, "2페이즈 전환 연출 이상");
		expect(rage && Math.abs(speed - 1.2F) < 0.01F, "2페이즈에 빨라지지 않음");
	}

	/** 새 패턴 과부하: 서 있으면 충격파에 맞고, 점프하면 넘김 */
	private static void overload(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		for (boolean jump : new boolean[] {false, true}) {
			ctx.waitFor(mc -> kr.huntcraft.client.combat.ClientHunters.local().current() == null, 400);
			sp.getServer().runOnServer(server -> {
				MonsterEntity m = boss(server);
				m.setAi(false);
				m.startAction("idle");
			});
			ctx.waitTicks(10);
			Vec3 at = sp.getServer().computeOnServer(server -> boss(server).position().add(0, 0, 6.0));
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 180 0", at.x, at.y, at.z));
			ctx.waitTicks(15);
			keepAlive(sp);
			int hits0 = sp.getServer().computeOnServer(server -> Shockwaves.hits());
			int ev0 = sp.getServer().computeOnServer(server -> Shockwaves.evaded());
			sp.getServer().runOnServer(server -> boss(server).startAction("overload"));
			boolean jumped = false;
			for (int t = 0; t < 230; t++) {
				ctx.waitTick();
				double f = sp.getServer().computeOnServer(server -> boss(server).frame(0.0F));
				// 링이 6칸 거리에 오는 때: 110 + (6 − 몸 반지름) / 16 × 60 (속도 1.2배 반영은 프레임 기준이라 그대로)
				if (jump && !jumped && f >= 118) {
					ctx.getInput().pressKey(HuntKeys.jump);
					jumped = true;
				}
				if (f >= 209) {
					break;
				}
			}
			int hits = sp.getServer().computeOnServer(server -> Shockwaves.hits()) - hits0;
			int ev = sp.getServer().computeOnServer(server -> Shockwaves.evaded()) - ev0;
			log("과부하 (%s): 충격파 맞음 %d · 피함 %d", jump ? "점프" : "서 있음", hits, ev);
			if (jump) {
				expect(ev >= 1, "과부하 첫 링을 점프로 넘지 못함");
			} else {
				expect(hits >= 1, "과부하 충격파에 맞지 않음");
			}
		}
	}

	/** 처치: 머리 · 왼팔이 떨어져 땅에 · 연출 뒤 먼지가 되어 사라짐 · 안개 걷힘 */
	private static void death(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		ctx.waitFor(mc -> kr.huntcraft.client.combat.ClientHunters.local().current() == null, 400);
		Vec3 at = sp.getServer().computeOnServer(server -> boss(server).position().add(0, 0, 9.0));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.2f %.2f %.2f 180 0", at.x, at.y, at.z));
		ctx.runOnClient(mc -> ShoulderCamera.setAngles(180.0F, 8.0F));
		ctx.waitTicks(10);
		int id = sp.getServer().computeOnServer(server -> Encounter.bossId());
		sp.getServer().runOnServer(server -> boss(server).onHunterHit("body", 1.0E6, 0, 0));
		double[] parts = null;
		float maxDissolve = 0.0F;
		boolean removed = false;
		for (int t = 0; t < 900; t++) {
			ctx.waitTick();
			var e = sp.getServer().computeOnServer(server -> server.overworld().getEntity(id));
			if (e == null) {
				removed = true;
				break;
			}
			MonsterEntity m = (MonsterEntity) e;
			int f = (int) m.frame(0.0F);
			if (f == 20 || f == 50 || f == 190) {
				ctx.takeScreenshot("phase8_death_f" + f);
			}
			if (f == 70 && parts == null) {
				parts = sp.getServer().computeOnServer(server -> {
					MonsterEntity b = (MonsterEntity) server.overworld().getEntity(id);
					Vec3 head = b.bonePoint("head", new double[] {0, 38, -4});
					Vec3 arm = b.bonePoint("left_arm2", new double[] {11, 10, 0});
					Vec3 neck = b.bonePoint("body", new double[] {0, 33, -2});
					return new double[] {head.y - b.getY(), head.distanceTo(neck), arm.y - b.getY(), arm.distanceTo(b.bonePoint("body", new double[] {11, 30, 0}))};
				});
			}
			float d = m.dissolve();
			if (d > 0.5F && maxDissolve <= 0.5F) {
				ctx.takeScreenshot("phase8_dissolve");
			}
			maxDissolve = Math.max(maxDissolve, d);
		}
		boolean open = sp.getServer().computeOnServer(server -> server.overworld().getBlockState(l.gate().above(1)).isAir());
		log("처치: 머리 높이 %.2f · 목에서 %.2f블록 · 왼팔 끝 높이 %.2f · 어깨에서 %.2f블록 · 소멸 최대 %.2f · 사라짐 %s · 안개 걷힘 %s", parts[0], parts[1], parts[2],
				parts[3], maxDissolve, removed, open);
		expect(parts[0] < 1.2 && parts[1] > 2.5, "머리가 떨어져 땅에 있지 않음");
		expect(parts[2] < 1.0 && parts[3] > 2.0, "왼팔이 떨어져 땅에 있지 않음");
		expect(maxDissolve >= 0.99F && removed, "먼지가 되어 사라지지 않음");
		expect(open, "처치 뒤 안개가 걷히지 않음");
	}

	/** /huntcraft arena reset → 보스 다시 앉음 · 안개 문 닫힘 */
	private static void reset(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		sp.getServer().runCommand("huntcraft arena reset");
		ctx.waitTicks(10);
		String[] r = sp.getServer().computeOnServer(server -> {
			MonsterEntity m = boss(server);
			return new String[] {m == null ? "없음" : m.actionId() + " · 전원 " + m.powered(), String.valueOf(ArenaStore.defeated(server)),
					String.valueOf(ArenaStore.introPlayed(server)), String.valueOf(server.overworld().getBlockState(l.gate().above(1)).is(Blocks.BARRIER))};
		});
		log("초기화 명령: 보스 %s · 처치 %s · 첫 등장 봄 %s · 안개 벽 %s", r[0], r[1], r[2], r[3]);
		expect(r[0].equals("dormant · 전원 false") && r[1].equals("false") && r[2].equals("false") && r[3].equals("true"), "필드 보스 초기화가 안 됨");
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
