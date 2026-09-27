package kr.huntcraft.anim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Skeleton;

/**
 * Blockbench(Bedrock) .animation.json 의 애니메이션 하나 — 서버(판정)와 클라이언트(렌더)가 같은 계산을 씁니다.
 *
 * 채널: rotation(도) · position(픽셀). 값은 Blockbench 에 적힌 그대로 (좌표 변환은 Skeleton 이 함).
 * 보간: linear · catmullrom(lerp_mode, 앞뒤 어느 한쪽이라도 smooth 면 곡선) · pre/post (순간 전환).
 *       Blockbench(timeline_animators.js)와 같은 규칙 — 부드러운 구간의 바깥 이웃이 없으면 구간 끝값을 씀.
 *       GeckoLib 이징 ("vector" + "easing" · "easingArgs") — 그 키프레임으로 들어가는 구간에 {@link Easing} 적용.
 *       채널 전체가 {"vector": [...]} 하나면 고정값.
 * 식(Molang): 숫자만 지원 — 문자열 식은 로드할 때 오류로 알려 줌 (확인 필요: 식이 필요해지면 평가기 추가).
 */
public final class Animation {
	public static final int ROTATION = 0;
	public static final int POSITION = 1;

	public final String name;
	/** 초 */
	public final double length;
	public final boolean loop;
	/** 본 이름 → [rotation, position] 채널 (없으면 null) */
	private final Map<String, Channel[]> bones;

	private record Key(double time, double[] pre, double[] post, boolean smooth, java.util.function.DoubleUnaryOperator ease) {}

	private static final class Channel {
		final List<Key> keys = new ArrayList<>();

		double[] sample(double t) {
			if (keys.size() == 1 || t <= keys.getFirst().time) {
				return keys.getFirst().pre;
			}
			Key last = keys.getLast();
			if (t >= last.time) {
				return last.post;
			}
			for (int i = 0; i < keys.size() - 1; i++) {
				Key a = keys.get(i);
				Key b = keys.get(i + 1);
				if (t < b.time) {
					double f = (t - a.time) / Math.max(1.0E-9, b.time - a.time);
					double[] out = new double[3];
					if (a.smooth || b.smooth) {
						double[] v0 = i > 0 ? keys.get(i - 1).post : a.post;
						double[] v3 = i + 2 < keys.size() ? keys.get(i + 2).pre : b.pre;
						for (int k = 0; k < 3; k++) {
							out[k] = catmullRom(v0[k], a.post[k], b.pre[k], v3[k], f);
						}
					} else {
						double e = b.ease != null ? b.ease.applyAsDouble(f) : f;
						for (int k = 0; k < 3; k++) {
							out[k] = a.post[k] + (b.pre[k] - a.post[k]) * e;
						}
					}
					return out;
				}
			}
			return last.post;
		}
	}

	private Animation(String name, double length, boolean loop, Map<String, Channel[]> bones) {
		this.name = name;
		this.length = length;
		this.loop = loop;
		this.bones = bones;
	}

	public boolean animates(String bone) {
		return bones.containsKey(bone);
	}

	/** seconds 시점 자세를 out 에 더함 (스켈레톤에 없는 본은 무시) */
	public void sample(double seconds, Skeleton skeleton, Pose out) {
		double t = seconds;
		if (loop && length > 0.0) {
			t = t % length;
			if (t < 0.0) {
				t += length;
			}
		}
		for (Map.Entry<String, Channel[]> e : bones.entrySet()) {
			int bone = skeleton.find(e.getKey());
			if (bone < 0) {
				continue;
			}
			Channel rot = e.getValue()[ROTATION];
			Channel pos = e.getValue()[POSITION];
			if (rot != null) {
				double[] r = rot.sample(t);
				out.rotate(bone, r[0], r[1], r[2]);
			}
			if (pos != null) {
				double[] p = pos.sample(t);
				out.move(bone, p[0], p[1], p[2]);
			}
		}
	}

	/** 파일 하나에 든 애니메이션 전부 */
	public static Map<String, Animation> parseFile(JsonObject root) {
		Map<String, Animation> out = new HashMap<>();
		JsonObject anims = root.getAsJsonObject("animations");
		if (anims == null) {
			throw new IllegalArgumentException("animations 가 없음");
		}
		for (Map.Entry<String, JsonElement> a : anims.entrySet()) {
			String name = a.getKey();
			try {
				out.put(name, parse(name, a.getValue().getAsJsonObject()));
			} catch (RuntimeException ex) {
				throw new IllegalArgumentException(name + ": " + ex.getMessage(), ex);
			}
		}
		return out;
	}

	private static Animation parse(String name, JsonObject o) {
		double length = o.has("animation_length") ? o.get("animation_length").getAsDouble() : 0.0;
		boolean loop = o.has("loop") && o.get("loop").isJsonPrimitive() && o.get("loop").getAsJsonPrimitive().isBoolean()
				&& o.get("loop").getAsBoolean();
		Map<String, Channel[]> bones = new HashMap<>();
		JsonObject b = o.getAsJsonObject("bones");
		if (b != null) {
			for (Map.Entry<String, JsonElement> e : b.entrySet()) {
				JsonObject bone = e.getValue().getAsJsonObject();
				Channel[] ch = new Channel[2];
				ch[ROTATION] = channel(bone.get("rotation"), e.getKey() + ".rotation");
				ch[POSITION] = channel(bone.get("position"), e.getKey() + ".position");
				if (ch[ROTATION] != null || ch[POSITION] != null) {
					bones.put(e.getKey(), ch);
				}
			}
		}
		return new Animation(name, length, loop, bones);
	}

	private static Channel channel(JsonElement el, String where) {
		if (el == null) {
			return null;
		}
		Channel c = new Channel();
		if (el.isJsonArray() || el.isJsonPrimitive()) {
			double[] v = vector(el, where);
			c.keys.add(new Key(0.0, v, v, false, null));
			return c;
		}
		if (el.getAsJsonObject().has("vector")) {
			// GeckoLib 고정값 채널 {"vector": [...]}
			double[] v = vector(el.getAsJsonObject().get("vector"), where);
			c.keys.add(new Key(0.0, v, v, false, null));
			return c;
		}
		List<Key> keys = new ArrayList<>();
		for (Map.Entry<String, JsonElement> k : el.getAsJsonObject().entrySet()) {
			double time = Double.parseDouble(k.getKey());
			JsonElement v = k.getValue();
			if (v.isJsonObject()) {
				JsonObject ko = v.getAsJsonObject();
				double[] post = ko.has("post") ? vector(ko.get("post"), where) : ko.has("vector") ? vector(ko.get("vector"), where) : null;
				double[] pre = ko.has("pre") ? vector(ko.get("pre"), where) : post;
				if (post == null) {
					post = pre;
				}
				if (pre == null) {
					throw new IllegalArgumentException(where + " @" + time + ": 값이 없음");
				}
				String easing = ko.has("easing") ? ko.get("easing").getAsString() : null;
				boolean smooth = (ko.has("lerp_mode") && "catmullrom".equals(ko.get("lerp_mode").getAsString()))
						|| "catmullrom".equalsIgnoreCase(easing);
				Double arg = ko.has("easingArgs") && ko.getAsJsonArray("easingArgs").size() > 0 ? ko.getAsJsonArray("easingArgs").get(0).getAsDouble() : null;
				keys.add(new Key(time, pre, post, smooth, smooth ? null : Easing.of(easing, arg)));
			} else {
				double[] vec = vector(v, where);
				keys.add(new Key(time, vec, vec, false, null));
			}
		}
		keys.sort((x, y) -> Double.compare(x.time, y.time));
		// Blockbench 검사기와 같은 경고: 부드러운(catmullrom) 키프레임은 이웃에도 부드러운 키프레임이 있어야 곡선이 됨
		for (int i = 0; i < keys.size(); i++) {
			boolean prev = i > 0 && keys.get(i - 1).smooth;
			boolean next = i + 1 < keys.size() && keys.get(i + 1).smooth;
			if (keys.get(i).smooth && !prev && !next) {
				kr.huntcraft.HuntCraft.LOGGER.warn("애니메이션 {} @{}: 부드러운 키프레임 앞뒤에 부드러운 키프레임이 없음 (Blockbench 경고와 같음)",
						where, keys.get(i).time);
			}
		}
		c.keys.addAll(keys);
		return c.keys.isEmpty() ? null : c;
	}

	private static double[] vector(JsonElement el, String where) {
		if (el.isJsonPrimitive()) {
			double v = number(el, where);
			return new double[] {v, v, v};
		}
		JsonArray a = el.getAsJsonArray();
		return new double[] {number(a.get(0), where), number(a.get(1), where), number(a.get(2), where)};
	}

	private static double number(JsonElement el, String where) {
		try {
			return el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : Double.parseDouble(el.getAsString().trim());
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(where + ": 식 '" + el.getAsString() + "' 은 아직 지원하지 않음 (숫자만)");
		}
	}

	private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
		double t2 = t * t;
		double t3 = t2 * t;
		return 0.5 * (2.0 * p1 + (-p0 + p2) * t + (2.0 * p0 - 5.0 * p1 + 4.0 * p2 - p3) * t2 + (-p0 + 3.0 * p1 - 3.0 * p2 + p3) * t3);
	}
}
