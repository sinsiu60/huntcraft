package kr.huntcraft.monster;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 보스 행동의 연출 · 특수 공격 데이터 (행동 JSON 의 "events" · "emitters"). 위치는 본 기준 모델 좌표(Blockbench 픽셀) —
 * 본이 움직이면 따라감 (주먹 끝 · 등 뒤 등).
 *
 *   "events": [ {"frame": 110, "every": 0,            그 프레임을 지날 때 한 번 (every > 0 이면 그 간격으로 반복 — 걷기 발소리)
 *                "bone": "right_arm2", "at": [-11, 3, 0],
 *                "effect": "golem_slam",               common.json effects 의 사건 이름 (사운드 · 파티클)
 *                "shake": {"radius": 24, "strength": 0.35, "duration": 0.6},     주변 플레이어 화면 흔들림 (거리에 따라 약해짐)
 *                "shockwave": {"angle": 100, "range": 20, "speed": 22, "width": 3, "height": 0.7,
 *                              "damage": 30, "reaction": "launch", "jumpClear": 0.9}} ]
 *       shockwave: 몸 방향 부채꼴(angle 도)로 땅을 타고 퍼지는 파도 — 초당 speed 블록, 앞머리 두께 width, 블록이 height 까지 솟음.
 *                  앞머리가 지나갈 때 땅에 있는 헌터가 맞음 (발이 땅에서 jumpClear 블록 넘게 떠 있으면 넘어감 · 구르기 무적이면 피함)
 *   "emitters": [ {"from": 18, "to": 104, "every": 2, "bone": "body", "at": [0, 27, 7],
 *                  "particle": "minecraft:campfire_cosy_smoke", "count": 1, "spread": 0.25, "speed": 0.01} ]
 */
public final class ActionFx {
	private ActionFx() {}

	public record Shockwave(double angle, double range, double speed, double width, double height, double damage, String reaction,
							double jumpClear) {}

	public record Shake(double radius, double strength, double duration) {}

	/** power: true = 전원이 켜짐 (눈에 불) · false = 꺼짐 — 텍스처가 바뀜 (정의의 textureOff) */
	public record Event(int frame, int every, String bone, double[] at, String effect, Shake shake, Shockwave shockwave, Boolean power) {
		/** 이번 스텝(prev, now] 에 이 사건이 일어나는지 */
		public boolean firesBetween(double prev, double now) {
			if (every <= 0) {
				return prev < frame && now >= frame;
			}
			if (now < frame) {
				return false;
			}
			double k0 = Math.floor((prev - frame) / every);
			double k1 = Math.floor((now - frame) / every);
			return k1 > k0 || (prev < frame && now >= frame);
		}
	}

	public record Emitter(int from, int to, int every, String bone, double[] at, String particle, int count, double spread, double speed) {}

	/** 카메라 연출: 가까운 헌터의 카메라가 until 프레임까지 bone · at 을 봄 */
	public record Cinematic(String bone, double[] at, int until) {}

	/** 연결 공격 하나: 이 행동이 끝나면 chance (분노 때 rageChance) 확률로 바로 이어서 (숨 고르기 없이) */
	public record Follow(String action, double chance, double rageChance) {}

	/**
	 * 잡은 헌터를 손에 쥐고 있기: bone · at 자리에 붙어 있다가 release 프레임에 대미지 · 반응 (던지기).
	 * stare {"bone", "at", "until"} (없으면 null) = 쥐고 쳐다보는 얼굴 자리 — 잡힌 헌터의 카메라 연출이 until 프레임까지 이곳을 봄
	 */
	public record Hold(String bone, double[] at, int release, double damage, String reaction, String stareBone, double[] stareAt, int stareUntil) {}

	/**
	 * 던지는 투사체 (잔해): frame 에 bone · at 에서 떠나 목표의 그 순간 자리로 포물선 (비행 시간 = 거리 / speed, flight 범위 안).
	 * 블록 모양 (block) · 크기 (scale) · 판정 반지름 (radius) · 대미지 · 반응.
	 * grip (프레임, 없으면 -1) = 그때부터 던질 때까지 잔해가 bone · at 에 붙어 손에 들려 보임 (판정 없음)
	 */
	public record Projectile(int frame, String bone, double[] at, String block, double scale, double radius, double speed, double[] flight,
							 double gravity, double damage, String reaction, int grip) {}

	/**
	 * 행동 흐름 (행동 JSON):
	 *   "followups": [{"action": "swing_2", "chance": 0.6, "rageChance": 0.8}]   끝나면 확률로 바로 이어서 (1~3연타 · 1~3바퀴)
	 *   "onGrab": "grab_hold"      잡기 판정이 닿으면 이 행동으로 (잡힌 헌터는 그 행동의 hold 로 손에 붙음)
	 *   "missFollow": "grab_miss"  잡기 판정이 하나도 안 닿고 끝나면 이 행동 (헛잡고 휘청 = 처벌 기회)
	 *   "wallStop": "charge_wall", "wallWindow": [a, b]   그 프레임 동안 벽에 막히면 이 행동으로 (돌진 → 벽에 부딪혀 경직)
	 *   "rootToTarget": [최소 배율, 최대 배율, 앞에서 멈출 거리]   root 의 앞 이동량을 목표까지 거리에 맞춰 늘이고 줄임 (뛰어올라 내려찍기)
	 *   "hold": {...} · "projectiles": [...]
	 *   "critOpen": [a, b]   그 프레임 동안 치명 일격을 받을 수 있음 (패링당함 · 자세 붕괴 행동)
	 *   "riposted": "riposted_kneel"   이 행동 중 치명 일격을 맞으면 roles.riposted 대신 이 행동 (무릎 꿇은 자세에서 이어지게)
	 *   "invuln": true   이 행동 동안 맞지 않음 (페이즈 전환 연출)
	 *   "cinematic": {"bone", "at", "until"}   가까운 헌터의 카메라가 until 프레임까지 이 자리를 봄 (깨어남 · 페이즈 전환 · 처치 연출)
	 *   "push": true   밀어내기 패턴 — 근접 압박이 임계치를 넘으면 가중치가 크게 오름 (MonsterDef.pressure), 쓰면 압박 0 + 쿨다운
	 */
	public record Flow(List<Follow> followups, String onGrab, String missFollow, String wallStop, int[] wallWindow, double[] rootToTarget,
					   Hold hold, List<Projectile> projectiles, int[] critOpen, String riposted, boolean invuln, Cinematic cinematic,
					   boolean push) {
		public boolean grabs() {
			return onGrab != null;
		}
	}

	static Flow flow(JsonObject a) {
		List<Follow> follows = new ArrayList<>();
		if (a.has("followups")) {
			for (JsonElement e : a.getAsJsonArray("followups")) {
				JsonObject o = e.getAsJsonObject();
				double chance = num(o, "chance", 0.5);
				follows.add(new Follow(o.get("action").getAsString(), chance, num(o, "rageChance", chance)));
			}
		}
		Hold hold = null;
		if (a.has("hold")) {
			JsonObject h = a.getAsJsonObject("hold");
			JsonObject st = h.has("stare") ? h.getAsJsonObject("stare") : null;
			hold = new Hold(h.get("bone").getAsString(), vec(h, "at"), h.get("release").getAsInt(), num(h, "damage", 40),
					h.has("reaction") ? h.get("reaction").getAsString() : "launch",
					st == null ? null : st.get("bone").getAsString(), st == null ? null : vec(st, "at"),
					st == null || !st.has("until") ? h.get("release").getAsInt() : st.get("until").getAsInt());
		}
		List<Projectile> projs = new ArrayList<>();
		if (a.has("projectiles")) {
			for (JsonElement e : a.getAsJsonArray("projectiles")) {
				JsonObject o = e.getAsJsonObject();
				JsonArray fl = o.has("flight") ? o.getAsJsonArray("flight") : null;
				projs.add(new Projectile(o.get("frame").getAsInt(), o.has("bone") ? o.get("bone").getAsString() : null, vec(o, "at"),
						o.has("block") ? o.get("block").getAsString() : "minecraft:cobblestone", num(o, "scale", 1.5), num(o, "radius", 0.8),
						num(o, "speed", 16), fl == null ? new double[] {0.6, 1.4} : new double[] {fl.get(0).getAsDouble(), fl.get(1).getAsDouble()},
						num(o, "gravity", 20), num(o, "damage", 30), o.has("reaction") ? o.get("reaction").getAsString() : "knockback",
						o.has("grip") ? o.get("grip").getAsInt() : -1));
			}
		}
		int[] window = null;
		if (a.has("wallWindow")) {
			JsonArray w = a.getAsJsonArray("wallWindow");
			window = new int[] {w.get(0).getAsInt(), w.get(1).getAsInt()};
		}
		double[] toTarget = null;
		if (a.has("rootToTarget")) {
			JsonArray t = a.getAsJsonArray("rootToTarget");
			toTarget = new double[] {t.get(0).getAsDouble(), t.get(1).getAsDouble(), t.size() > 2 ? t.get(2).getAsDouble() : 1.5};
		}
		int[] crit = null;
		if (a.has("critOpen")) {
			JsonArray c = a.getAsJsonArray("critOpen");
			crit = new int[] {c.get(0).getAsInt(), c.get(1).getAsInt()};
		}
		Cinematic cin = null;
		if (a.has("cinematic")) {
			JsonObject c = a.getAsJsonObject("cinematic");
			cin = new Cinematic(c.get("bone").getAsString(), vec(c, "at"), c.has("until") ? c.get("until").getAsInt() : Integer.MAX_VALUE);
		}
		return new Flow(follows, str(a, "onGrab"), str(a, "missFollow"), str(a, "wallStop"), window, toTarget, hold, projs, crit, str(a, "riposted"),
				a.has("invuln") && a.get("invuln").getAsBoolean(), cin,
				a.has("push") && a.get("push").getAsBoolean());
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsString() : null;
	}

	static List<Event> events(JsonObject a) {
		List<Event> out = new ArrayList<>();
		if (!a.has("events")) {
			return out;
		}
		for (JsonElement e : a.getAsJsonArray("events")) {
			JsonObject o = e.getAsJsonObject();
			Shake shake = null;
			if (o.has("shake")) {
				JsonObject s = o.getAsJsonObject("shake");
				shake = new Shake(num(s, "radius", 16), num(s, "strength", 0.2), num(s, "duration", 0.4));
			}
			Shockwave wave = null;
			if (o.has("shockwave")) {
				JsonObject w = o.getAsJsonObject("shockwave");
				wave = new Shockwave(num(w, "angle", 100), num(w, "range", 20), num(w, "speed", 20), num(w, "width", 3), num(w, "height", 0.7),
						num(w, "damage", 30), w.has("reaction") ? w.get("reaction").getAsString() : "launch", num(w, "jumpClear", 0.9));
			}
			out.add(new Event(o.get("frame").getAsInt(), o.has("every") ? o.get("every").getAsInt() : 0,
					o.has("bone") ? o.get("bone").getAsString() : null, vec(o, "at"),
					o.has("effect") ? o.get("effect").getAsString() : null, shake, wave, o.has("power") ? o.get("power").getAsBoolean() : null));
		}
		return out;
	}

	static List<Emitter> emitters(JsonObject a) {
		List<Emitter> out = new ArrayList<>();
		if (!a.has("emitters")) {
			return out;
		}
		for (JsonElement e : a.getAsJsonArray("emitters")) {
			JsonObject o = e.getAsJsonObject();
			out.add(new Emitter(o.get("from").getAsInt(), o.get("to").getAsInt(), o.has("every") ? o.get("every").getAsInt() : 1,
					o.has("bone") ? o.get("bone").getAsString() : null, vec(o, "at"), o.get("particle").getAsString(),
					o.has("count") ? o.get("count").getAsInt() : 1, num(o, "spread", 0.2), num(o, "speed", 0.02)));
		}
		return out;
	}

	private static double[] vec(JsonObject o, String key) {
		if (!o.has(key)) {
			return new double[] {0, 0, 0};
		}
		JsonArray v = o.getAsJsonArray(key);
		return new double[] {v.get(0).getAsDouble(), v.get(1).getAsDouble(), v.get(2).getAsDouble()};
	}

	private static double num(JsonObject o, String key, double def) {
		return o.has(key) ? o.get(key).getAsDouble() : def;
	}
}
