package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.debug.DebugState;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * 몬스터 갤러리 (opt-in) — 행동마다 여러 프레임에서 멈춰 옆 · 앞에서 찍음. 판정 도형(F7)이 메시에 겹치는지,
 * 공격 판정(빨강)이 켜지는 구간이 동작과 맞는지 눈으로 확인.
 *   ./gradlew runClientGameTest -Ponly=MonsterGalleryClientTest [-Pmotion=claw,roar] [-Pshapes=false (메시만)]
 *     [-Pframes=0-32:4 (프레임 지정)] [-Pviews=side] [-Pboss=iron_golem]
 * 결과: build/run/clientGameTest/screenshots/mgallery_<행동>_<시점>_f<프레임>[_hit].png
 */
public final class MonsterGalleryClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	/** -Pboss=<보스 id> (기본 boss.defaultId) */
	private static final String BOSS = System.getProperty("huntcraft.boss", kr.huntcraft.config.CommonConfig.get().boss.defaultId);

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), true)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -20 " + Y + " -20 20 " + Y + " 20 minecraft:smooth_stone");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " -12 0 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			int id = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), BOSS, new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(10);
			// 몬스터가 움직이지 않게 서버 틱을 멈춤 (자세는 미리보기로)
			sp.getServer().runCommand("effect give @a minecraft:invisibility infinite 0 true");
			ctx.waitTicks(5);
			sp.getServer().runCommand("tick freeze");
			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
				DebugState.showShapes = !"false".equals(System.getProperty("huntcraft.shapes"));
				ClientConfig.Camera cam = ClientConfig.get().camera;
				cam.distance = 0.6;
				cam.rightOffset = 0.0;
				cam.heightOffset = 0.8;
			});
			String only = System.getProperty("huntcraft.motion", "");
			MonsterDef def = Defs.monster(BOSS);
			for (String action : new TreeSet<>(def.actions.keySet())) {
				if (!only.isBlank() && java.util.Arrays.stream(only.split(",")).noneMatch(action::equals)) {
					continue;
				}
				MonsterDef.Action a = def.actions.get(action);
				List<Integer> frames = new ArrayList<>();
				String fixed = System.getProperty("huntcraft.frames", "");
				if (!fixed.isBlank()) {
					// "0-32:4" = 0 부터 32 까지 4 간격, "0,10,20" = 그 프레임들
					for (String part : fixed.split(",")) {
						if (part.contains("-")) {
							String[] r = part.split("[-:]");
							int step = r.length > 2 ? Integer.parseInt(r[2]) : 1;
							for (int f = Integer.parseInt(r[0]); f <= Integer.parseInt(r[1]); f += step) {
								frames.add(f);
							}
						} else {
							frames.add(Integer.parseInt(part.trim()));
						}
					}
				} else {
					for (int i = 0; i < 8; i++) {
						frames.add((int) Math.round(i * (a.length() - 1) / 7.0));
					}
					for (MonsterDef.Hit h : a.hits()) {
						frames.add(h.from());
						frames.add((h.from() + h.to()) / 2);
					}
				}
				String[] views = System.getProperty("huntcraft.views", "side,front").split(",");
				for (int f : new TreeSet<>(frames)) {
					boolean hit = a.hitIndex(f) >= 0;
					for (String view : views) {
						// 몬스터는 +Z 를 봄. 옆 = 오른쪽(-X)에서, 앞 = 비스듬히 앞에서
						Vec3 camFrom = view.equals("side") ? new Vec3(-9.5, Y + 1, 0.5) : new Vec3(-6.5, Y + 1, 9.0);
						float yaw = view.equals("side") ? -90.0F : -140.0F;
						sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f %.1f 0", camFrom.x, Y + 1, camFrom.z, yaw));
						ctx.waitTicks(3);
						ctx.runOnClient(mc -> {
							var e = mc.level.getEntity(id);
							if (e instanceof MonsterEntity m) {
								m.setPreview(action, f);
							}
							mc.player.setYRot(yaw);
							ShoulderCamera.setAngles(yaw, 12.0F);
						});
						ctx.waitTicks(3);
						String name = String.format(Locale.ROOT, "mgallery_%s_%s_f%03d%s", action, view, f, hit ? "_hit" : "");
						System.out.println("[huntcraft-gallery] " + ctx.takeScreenshot(name));
					}
				}
			}
			sp.getServer().runCommand("tick unfreeze");
			ctx.runOnClient(mc -> {
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
				ClientConfig.load();
			});
		}
	}
}
