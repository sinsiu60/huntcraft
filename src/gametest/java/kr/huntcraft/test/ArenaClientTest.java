package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 전장 생성 — /huntcraft arena build · enter · boss.
 *   저장된 자리 (문 · 체크포인트 · 보스) · 싸움터가 평평한지 (한 칸 턱 없음) · 문이 뚫려 있는지 · 보스가 싸움터에서 헌터를 쫓아오는지.
 *   스크린샷: 하늘에서 두 장, 문 밖 체크포인트에서, 싸움터 가운데에서, 숄더뷰로 보스와 마주한 모습.
 */
public final class ArenaClientTest implements FabricClientGameTest {
	private static final int Y = 120;
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("time set 6000");
			sp.getServer().runCommand("tp @a 0.5 " + Y + " 0.5 0 0");
			ctx.waitTicks(20);
			sp.getServer().runCommand("gamemode creative @a");
			// 떨어지기 전 자리로 고정해서 만듦
			sp.getServer().runCommand("execute positioned 0.5 " + Y + " 0.5 run huntcraft arena build");
			ctx.waitTicks(20);
			ArenaBuilder.Layout l = sp.getServer().computeOnServer(ArenaStore::load);
			expect(l != null, "전장 자리가 저장되지 않음");
			if (l == null) {
				throw new AssertionError(String.join("\n", FAILURES));
			}
			log("전장: 가운데 %s · 문 %s · 체크포인트 %s · 보스 %s", l.center().toShortString(), l.gate().toShortString(), l.checkpoint().toShortString(),
					l.bossSpawn().toShortString());

			// 싸움터: 발 높이에 막힌 칸 (한 칸 턱) 이 없어야 함 — 반블록 · 꽃 · 풀은 괜찮음
			int blocked = sp.getServer().computeOnServer(server -> {
				int n = 0;
				int F = l.fightRadius();
				for (int dx = -F; dx <= F; dx++) {
					for (int dz = -F; dz <= F; dz++) {
						if (dx * dx + dz * dz >= F * F) {
							continue;
						}
						BlockPos p = l.center().offset(dx, 0, dz);
						var s = server.overworld().getBlockState(p);
						if (s.getCollisionShape(server.overworld(), p).max(net.minecraft.core.Direction.Axis.Y) > 0.6) {
							n++;
						}
					}
				}
				return n;
			});
			// 문: 보스를 쓰러뜨리기 전까지 안개 문 (보이지 않는 벽) 으로 막혀 있음
			boolean gateOpen = sp.getServer().computeOnServer(server -> {
				for (int y = 0; y < 3; y++) {
					if (!server.overworld().getBlockState(l.gate().above(y)).is(Blocks.BARRIER)) {
						return false;
					}
				}
				return true;
			});
			boolean campfire = sp.getServer().computeOnServer(server -> {
				BlockPos cp = l.checkpoint();
				for (BlockPos p : BlockPos.betweenClosed(cp.offset(-4, 0, -3), cp.offset(4, 0, 3))) {
					if (server.overworld().getBlockState(p).is(Blocks.CAMPFIRE)) {
						return true;
					}
				}
				return false;
			});
			log("싸움터 턱 %d칸 · 안개 문 막힘 %s · 체크포인트 모닥불 %s", blocked, gateOpen, campfire);
			expect(blocked == 0, "싸움터에 걸리는 턱이 있음: " + blocked);
			expect(gateOpen, "안개 문 (보이지 않는 벽) 이 없음");
			expect(campfire, "체크포인트 모닥불이 없음");

			// 스크린샷 (관전: 바닐라 1인칭 카메라)
			sp.getServer().runCommand("gamemode spectator @a");
			shot(ctx, sp, l, 0, 42, 52, 180.0F, 38.0F, "arena_air_south");
			shot(ctx, sp, l, -48, 34, -30, -120.0F, 30.0F, "arena_air_west");
			shot(ctx, sp, l, 0, 2, l.checkpoint().getZ() - l.center().getZ() + 2, 180.0F, 2.0F, "arena_checkpoint");
			shot(ctx, sp, l, 0, 3, 4, 120.0F, 5.0F, "arena_center_ring");
			shot(ctx, sp, l, 0, 3, -4, -40.0F, 5.0F, "arena_center_ring2");
			// 색 보정 (소울류) 켜짐 / 꺼짐 비교
			String effect = ctx.computeOnClient(mc -> String.valueOf(mc.gameRenderer.currentPostEffect()));
			ctx.runOnClient(mc -> {
				kr.huntcraft.client.config.ClientConfig.get().grade.enabled = false;
				mc.gameRenderer.checkEntityPostEffect(mc.getCameraEntity());
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("arena_grade_off");
			String off = ctx.computeOnClient(mc -> String.valueOf(mc.gameRenderer.currentPostEffect()));
			ctx.runOnClient(mc -> {
				kr.huntcraft.client.config.ClientConfig.get().grade.enabled = true;
				mc.gameRenderer.checkEntityPostEffect(mc.getCameraEntity());
			});
			ctx.waitTicks(5);
			ctx.takeScreenshot("arena_grade_on");
			log("색 보정: 켜짐 %s · 끔 %s", effect, off);
			expect("huntcraft:souls_grade".equals(effect) && "null".equals(off), "색 보정 효과가 걸리지 않음");

			// 보스: 체크포인트로 들어가 보스를 부름 → 싸움터로 걸어 들어가면 쫓아옴
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("execute as @a at @s run huntcraft arena enter");
			ctx.waitTicks(10);
			// 전장을 만들면 보스가 첫 등장 자세 (전원 꺼진 채 앉음) 로 놓여 있음 — 여기서는 깨운 셈 치고 바로 싸움
			ctx.waitTicks(10);
			int id = sp.getServer().computeOnServer(server -> {
				for (var e : server.overworld().getAllEntities()) {
					if (e instanceof MonsterEntity m) {
						m.resetForFight(Vec3.atBottomCenterOf(l.bossSpawn()), l.bossYaw(), true);
						return m.getId();
					}
				}
				return -1;
			});
			expect(id >= 0, "보스가 나오지 않음");
			BlockPos stand = l.center().offset(0, 0, 8);
			sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d.5 %d %d.5 180 0", stand.getX(), stand.getY(), stand.getZ()));
			ctx.runOnClient(mc -> ShoulderCamera.setAngles(180.0F, 4.0F));
			double d0 = sp.getServer().computeOnServer(server -> dist(server, id));
			String firstAttack = null;
			for (int t = 0; t < 600 && firstAttack == null; t++) {
				ctx.waitTick();
				String a = sp.getServer().computeOnServer(server -> ((MonsterEntity) server.overworld().getEntity(id)).actionId());
				if (a != null && !a.equals("idle") && !a.equals("chase") && !a.startsWith("turn_") && !a.equals("pause")) {
					firstAttack = a;
				}
				if (t == 90) {
					ctx.takeScreenshot("arena_boss_approach");
				}
			}
			double d1 = sp.getServer().computeOnServer(server -> dist(server, id));
			double gy = sp.getServer().computeOnServer(server -> server.overworld().getEntity(id).getY() - l.center().getY());
			log("보스: 처음 거리 %.1f → 첫 공격 때 %.1f · 첫 공격 %s · 발 높이 %.2f", d0, d1, firstAttack, gy);
			expect(firstAttack != null, "10초 안에 공격하지 않음");
			expect(Math.abs(gy) < 0.1, "보스가 땅 위에 있지 않음");
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("전장 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("전장 시험 전부 통과");
	}

	private static double dist(MinecraftServer server, int id) {
		ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
		return ((MonsterEntity) server.overworld().getEntity(id)).horizontalDistance(p);
	}

	private static void shot(ClientGameTestContext ctx, TestSingleplayerContext sp, ArenaBuilder.Layout l, int dx, int dy, int dz, float yaw,
							 float pitch, String name) {
		BlockPos c = l.center();
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d.5 %d %d.5 %.1f %.1f", c.getX() + dx, c.getY() + dy, c.getZ() + dz, yaw, pitch));
		ctx.waitTicks(40);
		sp.getConnection().waitForChunksRender();
		ctx.takeScreenshot(name);
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
