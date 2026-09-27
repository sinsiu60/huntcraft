package kr.huntcraft.collision;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;

/**
 * 본 계층 — Blockbench(Bedrock) .geo.json 의 bones 만 읽습니다 (큐브는 렌더 전용이라 무시).
 * 서버도 이걸로 본 월드 변환을 직접 계산하므로 판정이 렌더러(GeckoLib, 클라이언트 전용)에 의존하지 않습니다.
 *
 * 좌표 규칙은 GeckoLib 과 같게 맞춤 (확인 필요: Phase 4 에서 GeckoLib 렌더 결과와 대조 시험):
 *   픽셀 → 블록 ÷16, X 부호 반전 (pivot · 좌표 · 위치 키프레임)
 *   회전: (-rx, -ry, rz) 도, 벡터에 X → Y → Z 순서
 *   본 변환 = 부모 · T(pivot + 위치) · R(기본 회전 + 애니 회전) · T(-pivot)
 */
public final class Skeleton {
	public final String id;
	private final List<Bone> bones;
	private final Map<String, Integer> index = new HashMap<>();
	/** 기본 자세(애니 없음)의 모델 공간 본 변환 — 도형 좌표를 본 기준으로 바꿀 때 씀 */
	private final Transform[] rest;

	public record Bone(String name, int parent, Vec3 pivot, double rx, double ry, double rz, List<Cube> cubes) {}

	/** 큐브 (모델 공간 블록 좌표의 두 모서리) — 판정에는 쓰지 않고, 무기처럼 모드가 직접 그리는 부품에만 씀 */
	public record Cube(Vec3 min, Vec3 max) {}

	private Skeleton(String id, List<Bone> bones) {
		this.id = id;
		this.bones = bones;
		for (int i = 0; i < bones.size(); i++) {
			index.put(bones.get(i).name, i);
		}
		this.rest = new Transform[bones.size()];
		solveModel(new Pose(bones.size()), rest);
	}

	public int size() {
		return bones.size();
	}

	public int indexOf(String bone) {
		Integer i = index.get(bone);
		if (i == null) {
			throw new IllegalArgumentException("스켈레톤 " + id + " 에 본 '" + bone + "' 이 없음 (있는 본: " + index.keySet() + ")");
		}
		return i;
	}

	/** 본 번호 (없으면 -1) */
	public int find(String bone) {
		Integer i = index.get(bone);
		return i == null ? -1 : i;
	}

	/** 부모 본 번호 (루트는 -1) */
	public int parent(int i) {
		return bones.get(i).parent;
	}

	public List<Cube> cubes(int i) {
		return bones.get(i).cubes;
	}

	public Vec3 pivot(int i) {
		return bones.get(i).pivot;
	}

	public String boneName(int i) {
		return bones.get(i).name;
	}

	public Transform rest(int i) {
		return rest[i];
	}

	/** 모델 공간 본 변환 (루트 변환 미적용) */
	public void solveModel(Pose pose, Transform[] out) {
		for (int i = 0; i < bones.size(); i++) {
			Bone b = bones.get(i);
			Transform parent = b.parent < 0 ? Transform.IDENTITY : out[b.parent];
			Vec3 offset = pose.position(i);
			Quaterniond r = Transform.eulerZYX(b.rx - pose.rx(i), b.ry - pose.ry(i), b.rz + pose.rz(i));
			Transform local = Transform.translation(b.pivot.add(offset))
					.then(Transform.rotation(r))
					.then(Transform.translation(b.pivot.scale(-1.0)));
			out[i] = parent.then(local);
		}
	}

	/** 월드 공간 본 변환 */
	public void solve(Pose pose, Transform root, Transform[] out) {
		solveModel(pose, out);
		for (int i = 0; i < out.length; i++) {
			out[i] = root.then(out[i]);
		}
	}

	/** Blockbench 픽셀 좌표 → 모델 공간 블록 좌표 */
	public static Vec3 pixels(double x, double y, double z) {
		return new Vec3(-x / 16.0, y / 16.0, z / 16.0);
	}

	public static Vec3 pixels(JsonArray a) {
		return pixels(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
	}

	public static Skeleton parseGeo(String id, JsonObject root) {
		JsonArray geos = root.getAsJsonArray("minecraft:geometry");
		if (geos == null || geos.isEmpty()) {
			throw new IllegalArgumentException("minecraft:geometry 가 없음");
		}
		JsonArray arr = geos.get(0).getAsJsonObject().getAsJsonArray("bones");
		// 부모가 먼저 오도록 정렬 (Blockbench 는 보통 이미 그 순서지만 보장은 아님)
		List<JsonObject> pending = new ArrayList<>();
		for (JsonElement e : arr) {
			pending.add(e.getAsJsonObject());
		}
		List<Bone> bones = new ArrayList<>();
		Map<String, Integer> placed = new HashMap<>();
		while (!pending.isEmpty()) {
			boolean progress = false;
			for (int i = 0; i < pending.size(); i++) {
				JsonObject o = pending.get(i);
				String parent = o.has("parent") ? o.get("parent").getAsString() : null;
				if (parent != null && !placed.containsKey(parent)) {
					continue;
				}
				Vec3 pivot = o.has("pivot") ? pixels(o.getAsJsonArray("pivot")) : Vec3.ZERO;
				double rx = 0;
				double ry = 0;
				double rz = 0;
				if (o.has("rotation")) {
					JsonArray r = o.getAsJsonArray("rotation");
					rx = -r.get(0).getAsDouble();
					ry = -r.get(1).getAsDouble();
					rz = r.get(2).getAsDouble();
				}
				String name = o.get("name").getAsString();
				List<Cube> cubes = new ArrayList<>();
				if (o.has("cubes")) {
					for (JsonElement c : o.getAsJsonArray("cubes")) {
						JsonObject co = c.getAsJsonObject();
						JsonArray origin = co.getAsJsonArray("origin");
						JsonArray size = co.getAsJsonArray("size");
						Vec3 a = pixels(origin.get(0).getAsDouble(), origin.get(1).getAsDouble(), origin.get(2).getAsDouble());
						Vec3 b = pixels(origin.get(0).getAsDouble() + size.get(0).getAsDouble(), origin.get(1).getAsDouble() + size.get(1).getAsDouble(),
								origin.get(2).getAsDouble() + size.get(2).getAsDouble());
						cubes.add(new Cube(new Vec3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z)),
								new Vec3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z))));
					}
				}
				placed.put(name, bones.size());
				bones.add(new Bone(name, parent == null ? -1 : placed.get(parent), pivot, rx, ry, rz, cubes));
				pending.remove(i--);
				progress = true;
			}
			if (!progress) {
				throw new IllegalArgumentException("부모를 찾을 수 없는 본: " + pending.get(0).get("name"));
			}
		}
		return new Skeleton(id, bones);
	}
}
