package kr.huntcraft.client.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.ColliderOwner;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.entity.ColliderTestEntity;
import kr.huntcraft.net.HitDebugPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 판정 도형 디버그 렌더 — F7 로 켜고 끔 (시험 엔티티는 모델이 없어서 항상 보임).
 *   공격 판정(hitbox): 켜짐 빨강 · 꺼짐 회색     피격 판정(hurtbox): 파랑 · 무적 중 노랑 · 패링 활성 초록
 *   방금 맞은 부위: 주황 (0.35초)                접촉점: 흰 십자 (1초)
 *
 * 도형은 서버와 같은 정의 · 같은 "시간 → 자세" 함수로 클라이언트가 직접 계산하고, 렌더 프레임 시점으로 보간합니다.
 * 명중(부위 · 접촉점)은 서버가 보낸 값 그대로 — 판정은 서버 권한.
 */
public final class ShapeDebugRenderer {
	private static final int HIT_ACTIVE = 0xFFFF3030;
	private static final int HIT_INACTIVE = 0xFF8C8C8C;
	private static final int HURT = 0xFF3399FF;
	private static final int INVULN = 0xFFFFE030;
	private static final int PARRY = 0xFF30E050;
	private static final int FLASH = 0xFFFF8A1A;
	private static final int CONTACT = 0xFFFFFFFF;
	private static final float LINE_WIDTH = 2.0F;
	private static final long FLASH_MS = 350L;
	private static final long CONTACT_MS = 1000L;

	private record Flash(int targetId, String hurtbox, long until) {}

	private record Mark(Vec3 point, long until) {}

	private static final List<Flash> FLASHES = new ArrayList<>();
	private static final List<Mark> MARKS = new ArrayList<>();
	/** 공격자 id → 명중 수 (F8 표시 · 시험) */
	private static final Map<Integer, Integer> HITS = new HashMap<>();
	private static HitDebugPayload last;
	/** 이번 프레임에 그린 도형 수 (시험용) */
	private static int drawnShapes;

	private ShapeDebugRenderer() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(HitDebugPayload.TYPE, (msg, ctx) -> onHit(msg));
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> submit(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	/** 피격 판정 색: 무적 중 노랑 · 패링 활성 초록 · 그 외 파랑 (시험도 씀) */
	public static int hurtColor(ColliderOwner owner, double time) {
		return !owner.hurtboxesActive(time) ? INVULN : owner.parryActive(time) ? PARRY : HURT;
	}

	public static boolean isInvulnColor(int color) {
		return color == INVULN;
	}

	public static int hits(int attackerId) {
		return HITS.getOrDefault(attackerId, 0);
	}

	public static HitDebugPayload lastHit() {
		return last;
	}

	public static int drawnShapes() {
		return drawnShapes;
	}

	private static void onHit(HitDebugPayload msg) {
		long now = System.currentTimeMillis();
		FLASHES.add(new Flash(msg.targetId(), msg.hurtbox(), now + FLASH_MS));
		MARKS.add(new Mark(new Vec3(msg.x(), msg.y(), msg.z()), now + CONTACT_MS));
		HITS.merge(msg.attackerId(), 1, Integer::sum);
		last = msg;
		kr.huntcraft.client.combat.ClientHunters.onHit(msg);
		kr.huntcraft.client.hud.DamageNumbers.onHit(msg);
	}

	private static void submit(PoseStack pose, net.minecraft.client.renderer.SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		drawnShapes = 0;
		if (mc.level == null) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		long now = System.currentTimeMillis();
		FLASHES.removeIf(f -> f.until < now);
		MARKS.removeIf(m -> m.until < now);
		Vec3 cam = camera.position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

		collector.submitCustomGeometry(pose, RenderTypes.lines(), (p, buffer) -> {
			Lines lines = new Lines(p, buffer, cam);
			List<ColliderOwner> owners = new ArrayList<>();
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e instanceof ColliderOwner o && (DebugState.showShapes || e instanceof ColliderTestEntity)) {
					owners.add(o);
				}
			}
			if (DebugState.showShapes) {
				owners.addAll(kr.huntcraft.client.combat.ClientHunters.all());
			}
			for (ColliderOwner owner : owners) {
				Entity e = owner.self();
				if (e.distanceToSqr(cam) > 96.0 * 96.0) {
					continue;
				}
				ColliderDef def = owner.colliderDef();
				Transform[] bones = ColliderInstance.solve(owner, partial, null);
				if (def == null || bones == null) {
					continue;
				}
				double time = owner.animTime(partial);
				for (ColliderDef.Box h : def.hitboxes) {
					lines.shape(h.shape().toWorld(bones[h.bone()]), owner.hitboxActive(h.id(), time) ? HIT_ACTIVE : HIT_INACTIVE);
					drawnShapes++;
				}
				// 무적 (구르기 · 백스텝 · 기상) = 노랑, 패링 구간 = 초록
				int hurt = hurtColor(owner, time);
				for (ColliderDef.Box u : def.hurtboxes) {
					lines.shape(u.shape().toWorld(bones[u.bone()]), flashing(e.getId(), u.id()) ? FLASH : hurt);
					drawnShapes++;
				}
			}
			for (Mark m : MARKS) {
				lines.cross(m.point, 0.12, CONTACT);
			}
		});
	}

	private static boolean flashing(int targetId, String hurtbox) {
		for (Iterator<Flash> it = FLASHES.iterator(); it.hasNext(); ) {
			Flash f = it.next();
			if (f.targetId == targetId && f.hurtbox.equals(hurtbox)) {
				return true;
			}
		}
		return false;
	}

	/** 카메라 기준 좌표로 선분을 쌓는 도우미 */
	private static final class Lines {
		private static final int SEGMENTS = 16;
		private final PoseStack.Pose pose;
		private final VertexConsumer buffer;
		private final Vec3 cam;

		Lines(PoseStack.Pose pose, VertexConsumer buffer, Vec3 cam) {
			this.pose = pose;
			this.buffer = buffer;
			this.cam = cam;
		}

		void line(Vec3 a, Vec3 b, int color) {
			Vec3 n = b.subtract(a);
			double len = n.length();
			if (len < 1.0E-6) {
				return;
			}
			n = n.scale(1.0 / len);
			vertex(a, n, color);
			vertex(b, n, color);
		}

		private void vertex(Vec3 p, Vec3 n, int color) {
			buffer.addVertex(pose, (float) (p.x - cam.x), (float) (p.y - cam.y), (float) (p.z - cam.z))
					.setColor(color)
					.setNormal(pose, (float) n.x, (float) n.y, (float) n.z)
					.setLineWidth(LINE_WIDTH);
		}

		void shape(Shape s, int color) {
			if (s instanceof Shape.Obb o) {
				obb(o, color);
			} else if (s instanceof Shape.Capsule c) {
				capsule(c, color);
			}
		}

		void obb(Shape.Obb o, int color) {
			Vec3[] c = o.corners(); // 인덱스 = sx*4 + sy*2 + sz (각 0/1)
			int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};
			for (int[] e : edges) {
				line(c[e[0]], c[e[1]], color);
			}
		}

		void capsule(Shape.Capsule c, int color) {
			Vec3 axis = c.b().subtract(c.a());
			double len = axis.length();
			double r = c.radius();
			if (len < 1.0E-6) {
				// 구
				circle(c.a(), new Vec3(1, 0, 0), new Vec3(0, 1, 0), r, 0.0, Math.PI * 2.0, color);
				circle(c.a(), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, 0.0, Math.PI * 2.0, color);
				circle(c.a(), new Vec3(0, 1, 0), new Vec3(0, 0, 1), r, 0.0, Math.PI * 2.0, color);
				return;
			}
			Vec3 u = axis.scale(1.0 / len);
			Vec3 helper = Math.abs(u.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0);
			Vec3 v = u.cross(helper).normalize();
			Vec3 w = u.cross(v).normalize();
			circle(c.a(), v, w, r, 0.0, Math.PI * 2.0, color);
			circle(c.b(), v, w, r, 0.0, Math.PI * 2.0, color);
			for (Vec3 side : new Vec3[] {v, v.scale(-1), w, w.scale(-1)}) {
				line(c.a().add(side.scale(r)), c.b().add(side.scale(r)), color);
			}
			// 반구: 축 방향 반원 두 개씩
			circle(c.b(), v, u, r, 0.0, Math.PI, color);
			circle(c.b(), w, u, r, 0.0, Math.PI, color);
			circle(c.a(), v, u.scale(-1), r, 0.0, Math.PI, color);
			circle(c.a(), w, u.scale(-1), r, 0.0, Math.PI, color);
		}

		/** center 에서 x·cos + y·sin 방향 원호 */
		void circle(Vec3 center, Vec3 x, Vec3 y, double r, double from, double to, int color) {
			int n = Math.max(4, (int) Math.ceil(SEGMENTS * (to - from) / (Math.PI * 2.0)));
			Vec3 prev = null;
			for (int i = 0; i <= n; i++) {
				double a = from + (to - from) * i / n;
				Vec3 p = center.add(x.scale(Math.cos(a) * r)).add(y.scale(Math.sin(a) * r));
				if (prev != null) {
					line(prev, p, color);
				}
				prev = p;
			}
		}

		void cross(Vec3 p, double size, int color) {
			line(p.add(-size, 0, 0), p.add(size, 0, 0), color);
			line(p.add(0, -size, 0), p.add(0, size, 0), color);
			line(p.add(0, 0, -size), p.add(0, 0, size), color);
		}
	}
}
