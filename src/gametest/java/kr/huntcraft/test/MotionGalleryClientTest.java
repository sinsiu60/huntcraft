package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.debug.DebugState;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.combat.HunterCombat;
import kr.huntcraft.combat.MoveSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.world.phys.Vec3;

/**
 * 모션 갤러리 (opt-in) — 모션마다 여러 프레임에서 멈춰 옆 · 앞에서 찍음. 판정 도형도 같이 보여서
 * 칼 궤적 · 활성 구간(빨강)을 눈으로 확인할 때 씁니다.
 *   ./gradlew runClientGameTest -Ponly=MotionGalleryClientTest
 * 결과: build/run/clientGameTest/screenshots/gallery_<모션>_<시점>_f<프레임>[_hit].png
 */
public final class MotionGalleryClientTest implements FabricClientGameTest {
	private static final int Y = 150;

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), true)) {
			return;
		}
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode creative @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -12 " + Y + " -12 12 " + Y + " 12 minecraft:smooth_stone");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 0.5 0 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(20);
			ctx.runOnClient(mc -> {
				if (!mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
				DebugState.showShapes = true;
				ClientConfig.Camera cam = ClientConfig.get().camera;
				cam.distance = 5.0;
				cam.rightOffset = 0.0;
				cam.heightOffset = -0.3;
				mc.player.setYRot(0.0F);
			});
			ctx.waitTicks(10);
			String only = System.getProperty("huntcraft.motion", "");
			MoveSet set = HunterCombat.moveSet();
			List<String> motions = new ArrayList<>(List.of("#idle_sheathed", "#idle_drawn"));
			motions.addAll(new TreeSet<>(set.motions.keySet()));
			for (String id : motions) {
				if (!only.isBlank() && java.util.Arrays.stream(only.split(",")).noneMatch(id::equals)) {
					continue;
				}
				MoveSet.Motion m = set.motion(id);
				if (m != null && m.animation() == null) {
					continue; // 방향 고르기 전용 (variants)
				}
				int length = m == null ? 1 : m.length();
				List<Integer> frames = new ArrayList<>();
				if (m == null) {
					frames.add(0);
				} else {
					for (int i = 0; i < 10; i++) {
						frames.add((int) Math.round(i * (length - 1) / 9.0));
					}
					for (MoveSet.Hit h : m.hits()) {
						frames.add(h.from());
						frames.add((h.from() + h.to()) / 2);
						frames.add(h.to());
					}
				}
				for (int f : new TreeSet<>(frames)) {
					boolean hit = m != null && m.hitIndex(f) >= 0;
					for (String view : new String[] {"side", "front"}) {
						float yaw = view.equals("side") ? -90.0F : 150.0F;
						ctx.runOnClient(mc -> {
							HunterBody body = ClientHunters.localBody();
							body.setPreview(new HunterBody.Preview(id, f));
							mc.player.setYRot(0.0F);
							mc.player.yRotO = 0.0F;
							mc.player.setDeltaMovement(Vec3.ZERO);
							ShoulderCamera.setAngles(yaw, 8.0F);
						});
						ctx.waitTicks(3);
						String name = String.format(Locale.ROOT, "gallery_%s_%s_f%02d%s", id.replace("#", ""), view, f, hit ? "_hit" : "");
						System.out.println("[huntcraft-gallery] " + ctx.takeScreenshot(name));
					}
				}
			}
			ctx.runOnClient(mc -> {
				ClientHunters.localBody().setPreview(null);
				if (mc.gui.hud.isHidden()) {
					mc.gui.hud.toggle();
				}
				ClientConfig.load();
			});
		}
	}
}
