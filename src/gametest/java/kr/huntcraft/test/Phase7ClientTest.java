package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.hud.HuntHud;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.net.BannerPayload;
import kr.huntcraft.progress.Checkpoints;
import kr.huntcraft.progress.Encounter;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Phase 7 시험 — 완료 조건: 체크포인트 → 보스방 → 사망 → 부활 → 재도전 루프가 끊김 없이 돌아감.
 *   체크포인트 휴식 (체력 · 물약 채움, 부활 자리) · 안개 문 E → 안으로 · 보스 체력바 · 안개가 나가는 길을 막음 · 물약은 맞아도 끝까지
 *   · 쓰러짐 (연출) → 체크포인트 부활 (가득) · 보스 초기화 · 다시 들어감 · 보스 처치 → 안개 걷힘 · 강적 격파 연출. HUD 스크린샷.
 */
public final class Phase7ClientTest implements FabricClientGameTest {
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
			// 첫 등장 연출 (앉아 있다 깨어남) 은 Phase8ClientTest — 여기서는 이미 본 것으로
			sp.getServer().runOnServer(server -> {
				ArenaStore.setIntroPlayed(server, true);
				Encounter.arenaBuilt(server);
			});
			sp.getServer().runCommand("execute as @a at @s run huntcraft arena enter");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);

			rest(ctx, sp, l);
			enter(ctx, sp, l, 1);
			fogBlocks(ctx, sp, l);
			flaskCommit(ctx, sp);
			die(ctx, sp, l);
			enter(ctx, sp, l, 2);
			victory(ctx, sp, l);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 7 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("Phase 7 전부 통과");
	}

	private static void face(ClientGameTestContext ctx, float yaw) {
		ctx.runOnClient(mc -> {
			mc.player.setYRot(yaw);
			ShoulderCamera.setAngles(yaw, 10.0F);
		});
	}

	/** 체크포인트: 체력 30 · 물약 1 → E → 휴식 (가득) */
	private static void rest(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		face(ctx, 180.0F);
		sp.getServer().runOnServer(server -> {
			HunterState st = HunterServer.get(player(server));
			st.health = 30.0;
			st.itemCounts[0] = 1;
		});
		ctx.waitTicks(10);
		String prompt = ctx.computeOnClient(mc -> HuntHud.shown() == null ? null : HuntHud.shown().prompt());
		int rests0 = sp.getServer().computeOnServer(server -> Checkpoints.rests());
		ctx.getInput().pressKey(HuntKeys.interact);
		ctx.waitTicks(20);
		String motion = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		double[] st = sp.getServer().computeOnServer(server -> {
			HunterState s = HunterServer.get(player(server));
			return new double[] {s.health, s.itemCounts[0], Checkpoints.rests()};
		});
		int banner = ctx.computeOnClient(mc -> HuntHud.shown().banner());
		ctx.takeScreenshot("phase7_rest");
		log("체크포인트: 안내 '%s' · E → 모션 %s · 체력 %.0f · 물약 %.0f · 휴식 %d→%.0f · 연출 %d", prompt, motion, st[0], st[1], rests0, st[2], banner);
		expect("모닥불 곁에서 쉰다".equals(prompt), "체크포인트 안내가 없음: " + prompt);
		expect("rest".equals(motion) && st[0] == CommonConfig.get().player.maxHealth && st[1] == 5 && st[2] == rests0 + 1, "휴식이 채우지 않음");
		expect(banner == BannerPayload.REST, "휴식 연출이 없음");
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
	}

	/** 안개 문 밖에 서서 E → 안개를 헤치고 안으로 · 보스 · 체력바 */
	private static void enter(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l, int nth) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 300);
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.gate().getX() + 0.5, l.gate().getY(), l.gate().getZ() + 2.0));
		ctx.waitTicks(15);
		face(ctx, 180.0F);
		ctx.waitTicks(5);
		String prompt = ctx.computeOnClient(mc -> HuntHud.shown().prompt());
		ctx.getInput().pressKey(HuntKeys.interact);
		ctx.waitTicks(8);
		String motion = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		ctx.waitTicks(80);
		double z = sp.getServer().computeOnServer(server -> player(server).getZ());
		boolean active = sp.getServer().computeOnServer(server -> Encounter.active());
		int entries = sp.getServer().computeOnServer(server -> Encounter.entries());
		String boss = sp.getServer().computeOnServer(server -> server.overworld().getEntity(Encounter.bossId()) instanceof MonsterEntity m
				? m.actionId() + (m.ai() ? " (행동 켬)" : " (행동 끔)") : "없음");
		boolean bar = ctx.computeOnClient(mc -> HuntHud.shown().bossBar());
		if (nth == 1) {
			ctx.takeScreenshot("phase7_boss_bar");
		}
		log("안개 문 %d번째: 안내 '%s' · E → %s · 안쪽 z %.1f (문 %d) · 전투 %s · 들어간 수 %d · 보스 %s · 체력바 %s", nth, prompt, motion, z, l.gate().getZ(),
				active, entries, boss, bar);
		expect("안개 속으로 들어간다".equals(prompt), "안개 문 안내가 없음: " + prompt);
		expect("fog_pass".equals(motion), "안개 헤치기 모션이 아님: " + motion);
		expect(z < l.gate().getZ() - 1.0 && active && entries == nth, "안개를 지나 보스전이 시작되지 않음");
		expect(boss.endsWith("(행동 켬)") && bar, "보스 · 체력바가 없음");
	}

	/** 안에서 문 쪽 (남쪽) 으로 걸어도 안개 벽에 막힘 */
	private static void fogBlocks(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		sp.getServer().runOnServer(server -> ((MonsterEntity) server.overworld().getEntity(Encounter.bossId())).setAi(false));
		face(ctx, 0.0F);
		ctx.getInput().holdKey(opts -> opts.keyUp);
		ctx.waitTicks(90);
		ctx.getInput().releaseKey(opts -> opts.keyUp);
		double z = sp.getServer().computeOnServer(server -> player(server).getZ());
		log("안개 벽: 문 쪽으로 1.5초 걸은 뒤 z %.2f (문 %d)", z, l.gate().getZ());
		expect(z < l.gate().getZ(), "안개 문으로 나갈 수 있음");
		face(ctx, 180.0F);
	}

	/** 회복 물약: 마시는 도중 맞아도 끝까지 (대미지만) */
	private static void flaskCommit(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> HunterServer.get(player(server)).health = 50.0);
		ctx.waitTicks(5);
		ctx.getInput().pressKey(HuntKeys.useItem);
		ctx.waitTicks(15);
		String before = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(10, "launch", p.position().add(0, 0, -2)));
		});
		String after = sp.getServer().computeOnServer(server -> HunterServer.combat(player(server)).motionId());
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 200);
		ctx.waitTicks(80);
		double hp = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		log("물약: 마시는 중 %s → 맞은 뒤 %s · 끝난 뒤 체력 %.0f (50 - 10 + 회복)", before, after, hp);
		expect("use_drink".equals(before) && "use_drink".equals(after) && hp > 50, "물약이 맞으면 끊기거나 회복이 안 됨");
	}

	/** 쓰러짐 → 연출 → 체크포인트 부활 (가득) · 보스 초기화 */
	private static void die(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		sp.getServer().runOnServer(server -> {
			MonsterEntity m = (MonsterEntity) server.overworld().getEntity(Encounter.bossId());
			m.setHp(m.maxHp() * 0.4F);
			ServerPlayer p = player(server);
			HunterServer.get(p).itemCounts[0] = 0;
			HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(999, "launch", p.position().add(0, 0, -2)));
		});
		ctx.waitTicks(40);
		boolean dead = sp.getServer().computeOnServer(server -> HunterServer.dead(player(server)));
		int banner = ctx.computeOnClient(mc -> HuntHud.shown().banner());
		boolean vanillaDead = sp.getServer().computeOnServer(server -> player(server).isDeadOrDying());
		ctx.takeScreenshot("phase7_death");
		boolean barAfterDeath = ctx.computeOnClient(mc -> HuntHud.shown().bossBar());
		ctx.waitFor(mc -> !HunterServerView.dead(), 400);
		ctx.waitTicks(20);
		Object[] r = sp.getServer().computeOnServer(server -> {
			ServerPlayer p = player(server);
			HunterState st = HunterServer.get(p);
			MonsterEntity m = (MonsterEntity) server.overworld().getEntity(Encounter.bossId());
			double toCp = Math.hypot(p.getX() - (l.checkpoint().getX() + 0.5), p.getZ() - (l.checkpoint().getZ() + 0.5));
			double bossToSpawn = Math.hypot(m.getX() - (l.bossSpawn().getX() + 0.5), m.getZ() - (l.bossSpawn().getZ() + 0.5));
			return new Object[] {HunterServer.dead(p), toCp, st.health, st.itemCounts[0], m.hp() / m.maxHp(), bossToSpawn, m.ai(), Encounter.active(),
					HunterServer.respawns()};
		});
		log("쓰러짐: 쓰러짐 %s · 연출 %d · 바닐라 죽음 %s · 체력바 %s → 부활: 쓰러짐 %s · 체크포인트까지 %.1f · 체력 %.0f · 물약 %s · 보스 체력 %.0f%% · 보스 자리까지 %.1f · 보스 행동 %s · 전투 %s · 부활 %s",
				dead, banner, vanillaDead, barAfterDeath, r[0], (double) r[1], (double) r[2], r[3], (float) r[4] * 100, (double) r[5], r[6], r[7], r[8]);
		expect(dead && banner == BannerPayload.DEATH && !vanillaDead, "쓰러짐 연출이 이상함");
		expect(!barAfterDeath, "쓰러진 뒤에도 보스 체력바가 남음");
		expect(!(boolean) r[0] && (double) r[1] < 2.0 && (double) r[2] == CommonConfig.get().player.maxHealth && (int) r[3] == 5,
				"체크포인트에서 가득 채워 부활하지 않음");
		expect((float) r[4] == 1.0F && (double) r[5] < 1.0 && !(boolean) r[6] && !(boolean) r[7], "보스가 초기화되지 않음");
	}

	/** 보스 처치 → 안개 걷힘 · 강적 격파 · 체력바 사라짐 */
	private static void victory(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l) {
		sp.getServer().runOnServer(server -> ((MonsterEntity) server.overworld().getEntity(Encounter.bossId())).onHunterHit("body", 1.0E6, 0, 0));
		ctx.waitTicks(40);
		int banner = ctx.computeOnClient(mc -> HuntHud.shown().banner());
		ctx.takeScreenshot("phase7_victory");
		boolean bar = ctx.computeOnClient(mc -> HuntHud.shown().bossBar());
		boolean open = sp.getServer().computeOnServer(server -> server.overworld().getBlockState(l.gate().above(1)).isAir());
		boolean defeated = sp.getServer().computeOnServer(ArenaStore::defeated);
		int wins = sp.getServer().computeOnServer(server -> Encounter.victories());
		log("보스 처치: 연출 %d · 체력바 %s · 안개 걷힘 %s · 저장 %s · 처치 %d", banner, bar, open, defeated, wins);
		expect(banner == BannerPayload.VICTORY && !bar && open && defeated && wins == 1, "보스 처치 흐름이 이상함");
	}

	/** 클라이언트에서 서버 쓰러짐 상태 보기 (싱글플레이) */
	private static final class HunterServerView {
		static boolean dead() {
			var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
			return server != null && !server.getPlayerList().getPlayers().isEmpty() && HunterServer.dead(server.getPlayerList().getPlayers().getFirst());
		}
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
