package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.render.ShockwaveRenderer;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.fx.Shockwaves;
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
 * 철골렘 보스 — 모델 · 크기 · 내려치기 충격파 (부채꼴 100° · 20칸).
 *   정면에 서 있으면 맞음 · 부채꼴 밖이면 안 맞음 · 앞머리가 올 때 점프하면 넘어감 · 구르기 무적이면 흘려보냄.
 *   스크린샷: 등 뒤 연기 (기 모으기), 충격파가 헌터 쪽으로 밀려오는 모습.
 */
public final class GolemClientTest implements FabricClientGameTest {
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
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " -20.5 0 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			// 골렘: (0.5, 0.5) 에서 +Z 를 봄, 행동 선택 끔 (내려치기만 강제)
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			size(ctx, sp);
			Case hit = slam(ctx, sp, 0.0, 12.0, null, true);
			Case outside = slam(ctx, sp, 0.0, 10.0, "side", false);
			Case jump = slam(ctx, sp, 0.0, 12.0, "jump", false);
			Case roll = slam(ctx, sp, 0.0, 12.0, "roll", false);
			log("충격파 — 정면 12칸 서 있기: 맞음 %s (체력 %.0f → %.0f, 반응 %s) · 블록 연출 최대 %d개", hit.hit, hit.hp0, hit.hp1, hit.reaction, hit.drawn);
			log("  부채꼴 밖 (75°): 맞음 %s · 점프: 맞음 %s (피함 %s) · 구르기: 맞음 %s (피함 %s)", outside.hit, jump.hit, jump.evaded, roll.hit, roll.evaded);
			expect(hit.hit && hit.hp1 < hit.hp0, "정면에 서 있는데 충격파에 맞지 않음");
			expect("hit_launch".equals(hit.reaction), "충격파 반응이 날아감이 아님: " + hit.reaction);
			expect(hit.drawn > 20, "충격파 블록 연출이 거의 없음: " + hit.drawn);
			expect(!outside.hit, "부채꼴 밖인데 맞음");
			expect(!jump.hit && jump.evaded, "점프로 넘지 못함");
			expect(!roll.hit && roll.evaded, "구르기 무적으로 피하지 못함");
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("골렘 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("골렘 시험 전부 통과");
	}

	private static void size(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		float[] wh = sp.getServer().computeOnServer(server -> new float[] {golem(server).getBbWidth(), golem(server).getBbHeight()});
		float[] cwh = ctx.computeOnClient(mc -> mc.level.getEntity(golemId) instanceof MonsterEntity m ? new float[] {m.getBbWidth(), m.getBbHeight()} : null);
		log("골렘 크기: 서버 %.1f × %.1f · 클라이언트 %s", wh[0], wh[1], cwh == null ? "없음" : String.format(Locale.ROOT, "%.1f × %.1f", cwh[0], cwh[1]));
		expect(Math.abs(wh[1] - 5.4F) < 0.01F && cwh != null && Math.abs(cwh[1] - 5.4F) < 0.01F, "골렘 충돌 상자 크기가 정의와 다름");
	}

	private record Case(boolean hit, boolean evaded, double hp0, double hp1, String reaction, int drawn) {}

	/** 골렘 앞 (몸 방향에서 angle 도 · distance 칸)에 서서 내려치기를 받음. how = null (서 있기) · "jump" · "roll" */
	private static Case slam(ClientGameTestContext ctx, TestSingleplayerContext sp, double angle, double distance, String how, boolean shots) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 400);
		sp.getServer().runOnServer(server -> {
			HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
			golem(server).startAction("idle");
		});
		ctx.waitTicks(10);
		double rad = Math.toRadians(angle);
		Vec3 at = new Vec3(0.5 - Math.sin(rad) * distance, Y + 1, 0.5 + Math.cos(rad) * distance);
		float face = (float) Math.toDegrees(Math.atan2(-(0.5 - at.x), 0.5 - at.z));
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %d %.3f %.2f 0", at.x, Y + 1, at.z, face));
		ctx.waitTicks(8);
		ctx.runOnClient(mc -> {
			mc.player.setYRot(face);
			ShoulderCamera.setAngles(face, 8.0F);
		});
		ctx.waitTicks(4);
		int hits0 = Shockwaves.hits();
		int evaded0 = Shockwaves.evaded();
		double hp0 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		sp.getServer().runOnServer(server -> golem(server).startAction("punch"));
		// 앞머리가 도착하는 프레임 = 내려친 프레임(110) + (주먹이 떨어진 곳에서 헌터까지 거리) / 속도.
		// 주먹 자리는 내려치기 자세의 주먹 끝 (몸 앞 약 3블록) — 판정과 같은 계산으로 미리 구함
		double fistAhead = sp.getServer().computeOnServer(server -> {
			MonsterEntity g = golem(server);
			g.setPreview("punch", 110);
			Vec3 fist = g.bonePoint("right_arm2", new double[] {-11, 4, 0});
			g.setPreview(null, 0);
			return Math.hypot(fist.x - g.getX(), fist.z - g.getZ());
		});
		int arrive = 110 + (int) Math.round((distance - fistAhead) / 24.0 * 60.0);
		boolean acted = false;
		int drawn = 0;
		String reaction = null;
		for (int t = 0; t < 230; t++) {
			ctx.waitTick();
			double frame = sp.getServer().computeOnServer(server -> golem(server).frame(0.0F));
			if (!acted && "jump".equals(how) && frame >= arrive - 10) {
				ctx.runOnClient(mc -> mc.player.jumpFromGround());
				acted = true;
			}
			if (!acted && "roll".equals(how) && frame >= arrive - 5) {
				ctx.getInput().pressKey(HuntKeys.evade);
				acted = true;
			}
			if ("side".equals(how) && !acted && frame >= 70) {
				// 조준(처음 1초)이 끝난 뒤 옆으로 비킴 — 부채꼴 밖 (몸 방향에서 75°)
				double r2 = Math.toRadians(sp.getServer().computeOnServer(server -> golem(server).getYRot()) + 75.0);
				Vec3 side = new Vec3(0.5 - Math.sin(r2) * distance, Y + 1, 0.5 + Math.cos(r2) * distance);
				sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.3f %d %.3f", side.x, Y + 1, side.z));
				acted = true;
			}
			if (how != null && frame >= arrive - 8 && frame <= arrive + 4) {
				double fr = frame;
				String dbg = sp.getServer().computeOnServer(server -> {
					ServerPlayer p = player(server);
					var body = HunterServer.body(p);
					return String.format(Locale.ROOT, "f%.0f y+%.2f 거리 %.2f 모션 %s 무적 %s", fr, p.getY() - (Y + 1), Math.hypot(p.getX() - 0.5, p.getZ() - 0.5),
							HunterServer.combat(p).motionId(), body != null && !body.hurtboxesActive(body.animTime(0.0F)));
				});
				log("    [%s] %s", how, dbg);
			}
			if (shots && (frame == 90 || frame == 113 || frame == 118 || frame == 124)) {
				ctx.takeScreenshot("golem_slam_f" + (int) frame);
			}
			drawn = Math.max(drawn, ctx.computeOnClient(mc -> ShockwaveRenderer.lastDrawn()));
			String m = ctx.computeOnClient(mc -> ClientHunters.local().motionId());
			if (m != null && m.startsWith("hit_") && reaction == null) {
				reaction = m;
			}
		}
		double hp1 = sp.getServer().computeOnServer(server -> HunterServer.get(player(server)).health);
		return new Case(Shockwaves.hits() > hits0, Shockwaves.evaded() > evaded0, hp0, hp1, reaction, drawn);
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
