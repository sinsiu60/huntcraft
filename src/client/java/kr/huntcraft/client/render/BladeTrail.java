package kr.huntcraft.client.render;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.combat.MoveSet;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * 칼 궤적 — 공격 구간 동안 칼날 뿌리(blade_1 시작) · 끝(blade_3 끝)을 매 스텝 기록해 사이를 반투명 띠로 그림.
 * 판정 캡슐과 같은 본 변환이라 궤적 = 실제로 판정이 지나간 자리.
 */
public final class BladeTrail {
	private record Sample(Vec3 base, Vec3 tip, long nanos, int color) {}

	private static final Map<Integer, Deque<Sample>> TRAILS = new HashMap<>();

	private BladeTrail() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BladeTrail::tick);
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> submit(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	/** 시험: 지금 남아 있는 궤적 점 수 (모든 헌터) */
	public static int samples() {
		int n = 0;
		for (Deque<Sample> d : TRAILS.values()) {
			n += d.size();
		}
		return n;
	}

	private static void tick(Minecraft mc) {
		ClientConfig.Hud cfg = ClientConfig.get().hud;
		if (mc.level == null || !cfg.bladeTrail) {
			TRAILS.clear();
			return;
		}
		long now = System.nanoTime();
		long keep = (long) (cfg.trailSeconds * 1.0E9);
		for (HunterBody body : ClientHunters.all()) {
			MoveSet.Motion m = body.combat().current();
			Deque<Sample> d = TRAILS.computeIfAbsent(body.player().getId(), k -> new ArrayDeque<>());
			double time = body.animTime(0.0F);
			if (m != null && m.hitIndex(time) >= 0) {
				Vec3[] seg = blade(body);
				if (seg != null) {
					d.addLast(new Sample(seg[0], seg[1], now, color(body, m, cfg)));
				}
			}
			while (!d.isEmpty() && now - d.peekFirst().nanos() > keep) {
				d.removeFirst();
			}
		}
		TRAILS.values().removeIf(Deque::isEmpty);
	}

	private static int color(HunterBody body, MoveSet.Motion m, ClientConfig.Hud cfg) {
		return cfg.trailColor;
	}

	/** [뿌리, 끝] 월드 좌표 */
	private static Vec3[] blade(HunterBody body) {
		ColliderDef def = body.colliderDef();
		Transform[] bones = def == null ? null : ColliderInstance.solve(body, 0.0F, null);
		if (bones == null) {
			return null;
		}
		ColliderDef.Box first = def.hitbox("blade_1");
		ColliderDef.Box last = def.hitbox("blade_3");
		if (first == null || last == null) {
			return null;
		}
		Shape a = first.shape().toWorld(bones[first.bone()]);
		Shape b = last.shape().toWorld(bones[last.bone()]);
		if (!(a instanceof Shape.Capsule ca) || !(b instanceof Shape.Capsule cb)) {
			return null;
		}
		return new Vec3[] {ca.a(), cb.b()};
	}

	private static void submit(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || TRAILS.isEmpty()) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		Vec3 cam = camera.position();
		long now = System.nanoTime();
		double keep = ClientConfig.get().hud.trailSeconds * 1.0E9;
		collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, buffer) -> {
			for (Deque<Sample> d : TRAILS.values()) {
				Sample prev = null;
				Iterator<Sample> it = d.iterator();
				while (it.hasNext()) {
					Sample s = it.next();
					if (prev != null) {
						quad(p, buffer, cam, prev, s, fade(prev, now, keep), fade(s, now, keep));
					}
					prev = s;
				}
			}
		});
	}

	private static int fade(Sample s, long now, double keep) {
		double f = Math.max(0.0, 1.0 - (now - s.nanos()) / keep);
		int a = (int) (((s.color() >>> 24) & 0xFF) * f);
		return (a << 24) | (s.color() & 0xFFFFFF);
	}

	private static void quad(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, Sample a, Sample b, int ca, int cb) {
		// 뿌리 쪽은 옅게 (칼끝이 가장 진함)
		int ra = (((ca >>> 24) / 4) << 24) | (ca & 0xFFFFFF);
		int rb = (((cb >>> 24) / 4) << 24) | (cb & 0xFFFFFF);
		Vec3[] v = {a.base().subtract(cam), a.tip().subtract(cam), b.tip().subtract(cam), b.base().subtract(cam)};
		int[] c = {ra, ca, cb, rb};
		for (int i = 0; i < 4; i++) {
			buf.addVertex(p, (float) v[i].x, (float) v[i].y, (float) v[i].z).setColor(c[i]);
		}
		for (int i = 3; i >= 0; i--) {
			buf.addVertex(p, (float) v[i].x, (float) v[i].y, (float) v[i].z).setColor(c[i]);
		}
	}
}
