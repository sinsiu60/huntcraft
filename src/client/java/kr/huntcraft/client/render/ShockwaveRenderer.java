package kr.huntcraft.client.render;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.net.CameraShakePayload;
import kr.huntcraft.net.ShockwavePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 충격파 연출 — 부채꼴 안 땅 블록이 앞머리를 따라 솟았다 내려앉는 파도 (서버 판정과 같은 앞머리: 반지름 = speed × 경과 시간).
 * 원래 블록은 그대로 두고, 같은 블록 모델을 위로 띄워 그림 (떨어지는 블록과 같은 방식) — 월드는 바뀌지 않음.
 *   높이: 앞머리 두께(width) 안에서 sin 모양, 멀어질수록 조금 낮아짐. 파도 뒤쪽은 살짝 튀었다 가라앉음.
 */
public final class ShockwaveRenderer {
	private record Wave(ShockwavePayload p, long startNanos) {}

	private static final List<Wave> WAVES = new ArrayList<>();
	/** 시험: 받은 충격파 수 · 마지막 프레임에 그린 블록 수 */
	private static int received;
	private static int lastDrawn;

	private ShockwaveRenderer() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(ShockwavePayload.TYPE, (msg, ctx) -> {
			WAVES.add(new Wave(msg, System.nanoTime()));
			received++;
		});
		ClientPlayNetworking.registerGlobalReceiver(CameraShakePayload.TYPE, (msg, ctx) -> {
			ShoulderCamera.shake(msg.strength(), msg.duration());
			kr.huntcraft.client.sound.SoundDuck.onShake(msg.strength());
		});
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> WAVES.clear());
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> submit(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	public static int received() {
		return received;
	}

	public static int lastDrawn() {
		return lastDrawn;
	}

	private static void submit(PoseStack pose, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		ClientLevel level = mc.level;
		if (level == null || WAVES.isEmpty()) {
			lastDrawn = 0;
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		Vec3 cam = camera.position();
		long now = System.nanoTime();
		int drawn = 0;
		Iterator<Wave> it = WAVES.iterator();
		while (it.hasNext()) {
			Wave w = it.next();
			ShockwavePayload p = w.p();
			double t = (now - w.startNanos()) / 1.0E9;
			double front = p.speed() * t;
			if (front - p.width() * 2.0 > p.range()) {
				it.remove();
				continue;
			}
			double inner = Math.max(0.0, front - p.width() * 2.0);
			double outer = Math.min(p.range(), front);
			int r = (int) Math.ceil(outer) + 1;
			int cx = Mth.floor(p.x());
			int cz = Mth.floor(p.z());
			int baseY = Mth.floor(p.y()) - 1;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					double bx = cx + dx + 0.5;
					double bz = cz + dz + 0.5;
					double ox = bx - p.x();
					double oz = bz - p.z();
					double d = Math.hypot(ox, oz);
					if (d < inner || d > outer || d < 0.8) {
						continue;
					}
					float dir = (float) Math.toDegrees(Math.atan2(-ox, oz));
					if (Math.abs(Mth.wrapDegrees(dir - p.yaw())) > p.angle() / 2.0F) {
						continue;
					}
					double h = height(front - d, p.width(), p.height()) * (1.0 - 0.45 * d / Math.max(1.0, p.range()));
					if (h < 0.02) {
						continue;
					}
					BlockPos top = surface(level, cx + dx, baseY, cz + dz);
					if (top == null) {
						continue;
					}
					BlockState state = level.getBlockState(top);
					if (state.getRenderShape() != RenderShape.MODEL) {
						continue;
					}
					MovingBlockRenderState rs = new MovingBlockRenderState();
					rs.randomSeedPos = top;
					rs.blockPos = top.above();
					rs.blockState = state;
					rs.biome = level.getBiome(top);
					rs.cardinalLighting = level.cardinalLighting();
					rs.lightEngine = level.getLightEngine();
					pose.pushPose();
					pose.translate(top.getX() - cam.x, top.getY() + h - cam.y, top.getZ() - cam.z);
					collector.submitMovingBlock(pose, rs, 0);
					pose.popPose();
					drawn++;
				}
			}
		}
		lastDrawn = drawn;
	}

	/**
	 * 앞머리에서 뒤로 s 블록 떨어진 자리의 높이 — 앞머리 두께 w 안에서 한 번 솟고 (sin),
	 * 그 뒤 w 만큼은 작게 한 번 더 튀었다 가라앉음 (흙이 흔들리는 느낌)
	 */
	private static double height(double s, double w, double amp) {
		if (s < 0.0) {
			return 0.0;
		}
		if (s < w) {
			return amp * Math.sin(Math.PI * s / w);
		}
		if (s < w * 2.0) {
			return amp * 0.22 * Math.sin(Math.PI * (s - w) / w);
		}
		return 0.0;
	}

	/** (x, z) 기둥에서 파도 시작 높이 근처의 땅 윗면 블록 (위가 비어 있는 단단한 블록) */
	private static BlockPos surface(ClientLevel level, int x, int y0, int z) {
		for (int y = y0 + 2; y >= y0 - 3; y--) {
			BlockPos pos = new BlockPos(x, y, z);
			BlockState s = level.getBlockState(pos);
			if (!s.isAir() && s.isSolidRender() && level.getBlockState(pos.above()).isAir()) {
				return pos;
			}
		}
		return null;
	}
}
