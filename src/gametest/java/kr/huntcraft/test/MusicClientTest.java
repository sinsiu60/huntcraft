package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.sound.BossMusic;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.progress.Encounter;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 보스 음악 (전장 흐름 그대로):
 *   깨어남 → 인트로 · 인트로 끝 → 1페이즈 반복 · 분노 → 2페이즈 반복 (엇갈려 바뀜) · 처치 → 처치 곡 (천천히 사라짐) → 꺼짐
 *   다시 도전해 싸우다 전장 초기화 (보스 살아 있음) → 짧게 줄여 꺼짐 · 음악 파일이 실제로 있음 · 도는 동안 바닐라 음악 멈춤
 */
public final class MusicClientTest implements FabricClientGameTest {
	private static final int Y = 90;
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		// 시험은 처치 곡이 빨리 사라지게
		ctx.runOnClient(mc -> {
			ClientConfig.get().music.endingFadeStart = 1.0;
			ClientConfig.get().music.endingFadeSeconds = 2.0;
		});
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("time set 6000");
			sp.getServer().runCommand("execute positioned 0.5 " + Y + " 0.5 run huntcraft arena build");
			sp.getServer().runCommand("gamemode survival @a");
			ctx.waitTicks(20);
			ArenaBuilder.Layout l = sp.getServer().computeOnServer(ArenaStore::load);
			sp.getServer().runCommand("execute as @a at @s run huntcraft arena enter");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);

			// 파일
			ctx.runOnClient(mc -> {
				for (String k : new String[] {"intro", "phase1", "phase2", "ending"}) {
					var set = mc.getSoundManager().getSoundEvent(net.minecraft.resources.Identifier.parse("huntcraft:music.prunis_" + k));
					var s = set == null ? null : set.getSound(net.minecraft.util.RandomSource.create());
					expect(s != null && s.shouldStream() && mc.getResourceManager().getResource(s.getPath()).isPresent(), "음악 파일 없음: " + k);
				}
			});
			expect(ctx.computeOnClient(mc -> BossMusic.state()) == BossMusic.State.OFF, "싸우기 전인데 음악이 돎");

			// 안개 문 → 다가가 깨움
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.gate().getX() + 0.5, l.gate().getY(), l.gate().getZ() + 2.0));
			ctx.waitTicks(15);
			ctx.runOnClient(mc -> ShoulderCamera.setAngles(180.0F, 8.0F));
			ctx.getInput().pressKey(HuntKeys.interact);
			ctx.waitTicks(90);
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.bossSpawn().getX() + 0.5, l.bossSpawn().getY(),
					l.bossSpawn().getZ() + 12.0));
			BossMusic.State s = wait(ctx, sp, BossMusic.State.INTRO, 200);
			String track = ctx.computeOnClient(mc -> BossMusic.track());
			log("깨어남: %s · %s · 바닐라 음악 멈춤 %s", s, track, ctx.computeOnClient(mc -> BossMusic.suppressing()));
			expect(s == BossMusic.State.INTRO && "huntcraft:music.prunis_intro".equals(track), "깨어날 때 인트로가 아님: " + s + " " + track);
			ctx.waitTicks(120);
			boolean playing = ctx.computeOnClient(mc -> mc.getSoundManager().getSoundEvent(
					net.minecraft.resources.Identifier.parse("huntcraft:music.prunis_intro")) != null && BossMusic.volume() > 0.0F);
			expect(playing, "인트로 음량이 0");

			// 인트로 끝 (시험은 건너뜀) → 1페이즈 반복
			ctx.runOnClient(mc -> BossMusic.skipIntro());
			s = wait(ctx, sp, BossMusic.State.PHASE1, 60);
			log("인트로 끝: %s · %s · 음량 %.2f", s, ctx.computeOnClient(mc -> BossMusic.track()), ctx.computeOnClient(mc -> BossMusic.volume()));
			expect(s == BossMusic.State.PHASE1 && "huntcraft:music.prunis_phase1".equals(ctx.computeOnClient(mc -> BossMusic.track())), "인트로 뒤 1페이즈가 아님");

			// 헌터가 쓰러짐 → 보스 초기화 → 짧게 줄여 꺼짐 → 다시 도전 (첫 등장은 이미 봄) → 인트로 없이 1페이즈
			ctx.waitTicks(60);
			sp.getServer().runOnServer(server -> Encounter.onHunterDeath(player(server)));
			s = wait(ctx, sp, BossMusic.State.OFF, 120);
			log("헌터가 쓰러짐: %s", s);
			expect(s == BossMusic.State.OFF, "헌터가 쓰러졌는데 음악이 계속됨");
			ctx.waitTicks(60);
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.gate().getX() + 0.5, l.gate().getY(), l.gate().getZ() + 2.0));
			ctx.waitTicks(15);
			ctx.getInput().pressKey(HuntKeys.interact);
			s = wait(ctx, sp, BossMusic.State.PHASE1, 300);
			log("다시 도전 (인트로 없이): %s · %s", s, ctx.computeOnClient(mc -> BossMusic.track()));
			expect(s == BossMusic.State.PHASE1, "다시 도전했는데 1페이즈 곡이 아님: " + s);
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f 180 0", l.bossSpawn().getX() + 0.5, l.bossSpawn().getY(),
					l.bossSpawn().getZ() + 12.0));

			// 분노 (체력 45%) → 2페이즈
			sp.getServer().runOnServer(server -> boss(server).setHp(boss(server).maxHp() * 0.45F));
			s = wait(ctx, sp, BossMusic.State.PHASE2, 900);
			log("2페이즈: %s · %s", s, ctx.computeOnClient(mc -> BossMusic.track()));
			expect(s == BossMusic.State.PHASE2 && "huntcraft:music.prunis_phase2".equals(ctx.computeOnClient(mc -> BossMusic.track())), "분노했는데 2페이즈 곡이 아님");

			// 처치 → 처치 곡 → 사라짐
			ctx.waitTicks(200);
			sp.getServer().runOnServer(server -> boss(server).onHunterHit("body", 1.0E6, 0.0, 0.0));
			s = wait(ctx, sp, BossMusic.State.ENDING, 60);
			float v0 = 0.0F;
			for (int t = 0; t < 70; t++) {
				ctx.waitTick();
				v0 = Math.max(v0, ctx.computeOnClient(mc -> BossMusic.volume()));
			}
			log("처치: %s · %s · 가장 컸을 때 음량 %.2f", s, ctx.computeOnClient(mc -> BossMusic.track()), v0);
			expect(s == BossMusic.State.ENDING && "huntcraft:music.prunis_ending".equals(ctx.computeOnClient(mc -> BossMusic.track())), "처치 곡이 아님");
			ctx.waitTicks(90);
			float v1 = ctx.computeOnClient(mc -> BossMusic.volume());
			s = wait(ctx, sp, BossMusic.State.OFF, 300);
			log("처치 곡 사라짐: 2.7초 뒤 음량 %.2f → 상태 %s", v1, s);
			expect(v1 < v0 && s == BossMusic.State.OFF, "처치 곡이 천천히 사라지지 않음");

			// 전장 초기화 (명령) → 음악 없음 (보스는 다시 앉음)
			sp.getServer().runCommand("huntcraft arena reset");
			s = wait(ctx, sp, BossMusic.State.OFF, 120);
			expect(s == BossMusic.State.OFF, "전장 초기화 뒤 음악이 돎");
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("음악 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("음악 시험 전부 통과");
	}

	private static BossMusic.State wait(ClientGameTestContext ctx, TestSingleplayerContext sp, BossMusic.State want, int ticks) {
		BossMusic.State s = null;
		for (int t = 0; t < ticks; t++) {
			ctx.waitTick();
			sp.getServer().runOnServer(server -> {
				if (!server.getPlayerList().getPlayers().isEmpty()) {
					HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
				}
			});
			s = ctx.computeOnClient(mc -> BossMusic.state());
			if (s == want) {
				return s;
			}
		}
		return s;
	}

	private static MonsterEntity boss(MinecraftServer server) {
		return server.overworld().getEntity(Encounter.bossId()) instanceof MonsterEntity m ? m : null;
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
