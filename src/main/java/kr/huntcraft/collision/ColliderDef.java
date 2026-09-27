package kr.huntcraft.collision;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/**
 * 엔티티 하나의 판정 도형 정의 (defs/colliders/*.json).
 *
 * 도형 좌표는 Blockbench 에서 보이는 그대로 — 기본 자세 모델 공간의 픽셀 좌표. 로드할 때 붙을 본 기준으로 바꿔 두고,
 * 매 스텝 본의 월드 변환을 곱해 월드 도형을 만듭니다.
 *
 *   { "skeleton": "training_dummy", "scale": 1.0,
 *     "hurtboxes": [ { "id": "head", "part": "head", "bone": "head",
 *                      "shape": { "type": "sphere", "center": [0, 28, 0], "radius": 4 } } ],
 *     "hitboxes":  [ { "id": "blade", "bone": "rod",
 *                      "shape": { "type": "capsule", "a": [0,16,4], "b": [0,16,48], "radius": 1.5 } } ] }
 *
 * 도형 type: sphere(center, radius) · capsule(a, b, radius) · obb(center, size, rotation[도])
 */
public final class ColliderDef {
	public final String id;
	public final Skeleton skeleton;
	public final double scale;
	public final List<Box> hurtboxes;
	public final List<Box> hitboxes;
	/** 부위 → 대미지 배율 · 약점 (없는 부위는 {@link Part#DEFAULT}) */
	public final java.util.Map<String, Part> parts;
	/** 대상 흡수율 0~1 (대미지 × (1 − 흡수율)) — "absorption" */
	public final double absorption;

	/** 부위 — 대미지 배율 (약점 1~2곳만 1.2~1.5, 나머지 1.0 근처) · 약점 표시. 약점 노출 타이밍은 보스 행동 데이터로 확장 */
	public record Part(double multiplier, boolean weak) {
		public static final Part DEFAULT = new Part(1.0, false);
	}

	/** 판정 도형 하나 — part 는 피격 부위 이름 (hitbox 는 null) */
	public record Box(String id, String part, int bone, LocalShape shape) {}

	/** 본 기준 좌표의 도형 */
	public sealed interface LocalShape permits LocalCapsule, LocalObb {
		Shape toWorld(Transform bone);
	}

	public record LocalCapsule(Vec3 a, Vec3 b, double radius) implements LocalShape {
		@Override
		public Shape toWorld(Transform bone) {
			return new Shape.Capsule(bone.apply(a), bone.apply(b), radius * bone.scale);
		}
	}

	public record LocalObb(Vec3 center, Quaterniond rotation, Vec3 half) implements LocalShape {
		@Override
		public Shape toWorld(Transform bone) {
			Transform t = bone.then(new Transform(center, rotation, 1.0));
			return new Shape.Obb(t.pos, t.rotate(new Vec3(1, 0, 0)), t.rotate(new Vec3(0, 1, 0)), t.rotate(new Vec3(0, 0, 1)),
					half.scale(bone.scale));
		}
	}

	private ColliderDef(String id, Skeleton skeleton, double scale, List<Box> hurtboxes, List<Box> hitboxes,
						java.util.Map<String, Part> parts, double absorption) {
		this.id = id;
		this.skeleton = skeleton;
		this.scale = scale;
		this.hurtboxes = hurtboxes;
		this.hitboxes = hitboxes;
		this.parts = parts;
		this.absorption = absorption;
	}

	public Part part(String part) {
		return parts.getOrDefault(part, Part.DEFAULT);
	}

	public Box hitbox(String boxId) {
		for (Box b : hitboxes) {
			if (b.id.equals(boxId)) {
				return b;
			}
		}
		return null;
	}

	public static ColliderDef parse(String id, JsonObject o, java.util.function.Function<String, Skeleton> skeletons) {
		String skId = o.get("skeleton").getAsString();
		Skeleton sk = skeletons.apply(skId);
		if (sk == null) {
			throw new IllegalArgumentException("스켈레톤 '" + skId + "' 이 없음");
		}
		double scale = o.has("scale") ? o.get("scale").getAsDouble() : 1.0;
		java.util.Map<String, Part> parts = new java.util.HashMap<>();
		if (o.has("parts")) {
			for (java.util.Map.Entry<String, JsonElement> e : o.getAsJsonObject("parts").entrySet()) {
				JsonObject z = e.getValue().getAsJsonObject();
				parts.put(e.getKey(), new Part(z.has("multiplier") ? z.get("multiplier").getAsDouble() : 1.0,
						z.has("weak") && z.get("weak").getAsBoolean()));
			}
		}
		return new ColliderDef(id, sk, scale, boxes(o.getAsJsonArray("hurtboxes"), sk, true),
				boxes(o.getAsJsonArray("hitboxes"), sk, false), parts, o.has("absorption") ? o.get("absorption").getAsDouble() : 0.0);
	}

	private static List<Box> boxes(JsonArray arr, Skeleton sk, boolean hurt) {
		List<Box> out = new ArrayList<>();
		if (arr == null) {
			return out;
		}
		for (JsonElement e : arr) {
			JsonObject b = e.getAsJsonObject();
			String boxId = b.get("id").getAsString();
			int bone = sk.indexOf(b.get("bone").getAsString());
			String part = hurt ? (b.has("part") ? b.get("part").getAsString() : boxId) : null;
			out.add(new Box(boxId, part, bone, shape(b.getAsJsonObject("shape"), sk.rest(bone).inverse())));
		}
		return out;
	}

	/** 모델 공간 픽셀 도형 → 본 기준 도형 */
	private static LocalShape shape(JsonObject s, Transform toBone) {
		String type = s.get("type").getAsString();
		switch (type) {
			case "sphere" -> {
				Vec3 c = toBone.apply(Skeleton.pixels(s.getAsJsonArray("center")));
				return new LocalCapsule(c, c, s.get("radius").getAsDouble() / 16.0);
			}
			case "capsule" -> {
				return new LocalCapsule(toBone.apply(Skeleton.pixels(s.getAsJsonArray("a"))),
						toBone.apply(Skeleton.pixels(s.getAsJsonArray("b"))), s.get("radius").getAsDouble() / 16.0);
			}
			case "obb" -> {
				JsonArray size = s.getAsJsonArray("size");
				Vec3 half = new Vec3(size.get(0).getAsDouble(), size.get(1).getAsDouble(), size.get(2).getAsDouble()).scale(0.5 / 16.0);
				double rx = 0;
				double ry = 0;
				double rz = 0;
				if (s.has("rotation")) {
					JsonArray r = s.getAsJsonArray("rotation");
					rx = -r.get(0).getAsDouble();
					ry = -r.get(1).getAsDouble();
					rz = r.get(2).getAsDouble();
				}
				Transform model = new Transform(Skeleton.pixels(s.getAsJsonArray("center")), Transform.eulerZYX(rx, ry, rz), 1.0);
				Transform local = toBone.then(model);
				return new LocalObb(local.pos, local.rot(), half);
			}
			default -> throw new IllegalArgumentException("모르는 도형 type: " + type);
		}
	}
}
