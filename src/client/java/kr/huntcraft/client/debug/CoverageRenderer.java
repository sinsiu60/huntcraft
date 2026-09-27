package kr.huntcraft.client.debug;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kr.huntcraft.net.CoveragePayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 사각지대 커버리지 맵 (디버그) — 몬스터 발밑에 격자를 색으로: 맞히는 패턴 0 빨강 · 1 주황 · 2~3 노랑 · 4+ 초록.
 * 격자는 몬스터 기준 (처음 방향) 이라 몬스터가 돌면 함께 돎.
 */
public final class CoverageRenderer {
	private static CoveragePayload map;

	private CoverageRenderer() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(CoveragePayload.TYPE, (msg, ctx) -> map = msg.monsterId() < 0 ? null : msg);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> map = null);
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> submit(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	/** 시험: 받은 칸 수 (없으면 0) */
	public static int cells() {
		return map == null ? 0 : map.count().length;
	}

	public static int color(int count) {
		return count <= 0 ? 0xA0E02828 : count == 1 ? 0xA0F08A1E : count <= 3 ? 0xA0E8D22A : 0xA034C04A;
	}

	private static void submit(PoseStack pose, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		CoveragePayload m = map;
		if (mc.level == null || m == null) {
			return;
		}
		Entity e = mc.level.getEntity(m.monsterId());
		Camera camera = mc.gameRenderer.mainCamera();
		if (e == null || !camera.isInitialized()) {
			return;
		}
		Vec3 cam = camera.position();
		Vec3 base = e.position();
		double rad = Math.toRadians(e.getYRot());
		double cos = Math.cos(rad);
		double sin = Math.sin(rad);
		double h = m.spacing() * 0.46;
		double y = base.y + 0.03 - cam.y;
		collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, buffer) -> {
			for (int i = 0; i < m.count().length; i++) {
				int c = color(m.count()[i]);
				double[][] corners = {{-h, -h}, {h, -h}, {h, h}, {-h, h}};
				float[] vx = new float[4];
				float[] vz = new float[4];
				for (int k = 0; k < 4; k++) {
					double lx = m.x()[i] + corners[k][0];
					double lz = m.z()[i] + corners[k][1];
					vx[k] = (float) (base.x + lx * cos - lz * sin - cam.x);
					vz[k] = (float) (base.z + lx * sin + lz * cos - cam.z);
				}
				quad(p, buffer, vx, vz, (float) y, c);
			}
		});
	}

	private static void quad(PoseStack.Pose p, VertexConsumer buf, float[] vx, float[] vz, float y, int c) {
		for (int k = 0; k < 4; k++) {
			buf.addVertex(p, vx[k], y, vz[k]).setColor(c);
		}
		for (int k = 3; k >= 0; k--) {
			buf.addVertex(p, vx[k], y, vz[k]).setColor(c);
		}
	}
}
