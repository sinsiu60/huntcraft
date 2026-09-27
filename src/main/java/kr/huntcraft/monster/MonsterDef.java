package kr.huntcraft.monster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
* 몬스터 정의 (defs/monsters/<id>.json) — 능력치 · 강인도 · 자세 · 분노 · 행동 전부 데이터. 프레임 = 1/60초.
 *
 *   "collider": 판정 도형 id,   "health", "size": [폭, 높이] (지형 충돌 상자 — 배율 적용 뒤 블록),
 *   "model": GeckoLib 모델 · 애니메이션 파일 이름 (기본 = collider), "texture": 텍스처 id (기본 huntcraft:textures/entity/<model>.png),
 *   "detectRange" (발견) · "loseRange" (놓침) · "roarRadius" (포효 귀막기 범위),
 *   "poise": {"max", "regenDelay" (초), "regenPerSec"} — 헌터 공격의 강인도 대미지로 0 이 되면 경직 (roles.flinch, 끊을 수 있는 행동일 때)
 *   "stance": {"max", "regenDelay", "regenPerSec", "parry"} — 자세 게이지: 가득 차면 자세 붕괴 (roles.stanceBreak, 치명 구간) · 패링당하면 parry 만큼
 *   roles: parried (패링당함) · stanceBreak (자세 붕괴) · riposted (치명 일격을 맞음) — 앞의 둘은 행동의 critOpen 구간에 치명 일격
 *   "lockOn": [{"name": "머리", "bone": "head", "at": [x, y, z]}] — 락온 지점 (대형 보스는 여러 개, 마우스로 전환). 없으면 몸 가운데
 *   "rage": {"healthBelow": 0.5, "speed": 1.2, "action": "roar_rage"},
 *   "actions": {"<id>": 행동} — {@link Action}
 */
public final class MonsterDef {
	public final String id;
	public final String collider;
	/** GeckoLib 모델 · 애니메이션 파일 이름 (assets/huntcraft/geckolib/{models,animations}/entity/<model>.*) */
	public final String model;
	/** 텍스처 id (바닐라 텍스처를 그대로 가리켜도 됨 — 복사하지 않음) */
	public final String texture;
	/** 전원이 꺼졌을 때 텍스처 (눈 불 꺼짐 — 없으면 texture 그대로) */
	public final String textureOff;
	/** 처음 보스전에 들어왔을 때: 전원이 꺼진 채 앉아 있는 행동 · 다가오면 깨어나는 행동 (없으면 바로 싸움) */
	public final String dormant;
	public final String awaken;
	public final double health;
	public final double width;
	public final double height;
	public final double detectRange;
	/** 이름 (토벌 제목 등) */
	public final String displayName;
	public final double loseRange;
	public final double roarRadius;
	/** 강인도 · 자세: 최대 · 회복 대기 (초) · 초당 회복 */
	public final double poiseMax;
	public final double poiseRegenDelay;
	public final double poiseRegenPerSec;
	public final double stanceMax;
	public final double stanceRegenDelay;
	public final double stanceRegenPerSec;
	/** 락온 지점 (비면 몸 가운데 하나) */
	public final List<LockPoint> lockOn;

	/** 락온 지점: 본 위 한 점 (모델 픽셀) */
	public record LockPoint(String name, String bone, double[] at) {}
	public final double rageBelow;
	public final double rageSpeed;
	public final String rageAction;
	public final String idleAction;
	public final String turnLeft;
	public final String turnRight;
	public final String chase;
	public final String pause;
	public final String discover;
	public final String flinch;
	/** 패링당해 크게 휘청 (치명 구간) · 자세 붕괴 무릎 꿇음 (치명 구간) · 치명 일격을 맞음 */
	public final String parried;
	public final String stanceBreak;
	public final String riposted;
	/** 패링당할 때 쌓이는 자세 대미지 */
	public final double parryStance;
	public final String knockdown;
	public final String death;
	/**
	 * 공격 뒤 틈 (몬헌식 — 한 번 공격하면 대부분 숨을 고름): afterAttack {"pause": [최소, 최대 초], "chain": 바로 다음 공격 확률,
	 * "ragePause": [분노 때 초], "rageChain": 분노 때 확률}. 숨 고르기 동안 pause 행동(보통 대기 동작 + 느린 추적)
	 */
	public final double[] pauseSeconds;
	public final double[] ragePauseSeconds;
	public final double chainChance;
	public final double rageChainChance;
	public final Map<String, Action> actions;
	/** 근접 압박 게이지 — {@link Pressure} */
	public final Pressure pressure;
	/** 보스 음악 (없으면 null) — {@link Music} */
	public final Music music;

	/**
	 * "music": {"intro", "phase1", "phase2", "ending"} — 사운드 이벤트 id (assets/huntcraft/sounds.json).
	 *   intro = 깨어날 때 한 번 → phase1 반복 · 분노하면 phase2 반복 · 처치하면 ending 한 번 (천천히 사라짐). 비운 칸은 건너뜀
	 */
	public record Music(String intro, String phase1, String phase2, String ending) {}

	/**
	 * "pressure": 근접 압박 (행동 선택 조건). 목표가 밀착 구역이면 초당 innerRate, 근접이면 closeRate 만큼 오르고 그 밖이면 decay 로 내려감,
	 *   밀착 · 근접에서 헌터에게 맞으면 hitAdd. threshold 이상이면 밀어내기 (push) 행동 가중치 × weightBoost.
	 *   밀어내기를 쓰면 0 으로 · cooldown 초 동안 밀어내기 안 함. max = 상한.
	 *   임계치 이상이면 공격 뒤 숨 고르기 (afterAttack.pause) 를 건너뜀 · forceAtMax = 최대면 근접 방어 (밀어내기 · 고를 수 있는 재배치) 만 고름
	 *   "reposition": {@link RepositionRule} — 재배치 패턴을 고르는 조건
	 */
	public record Pressure(double max, double innerRate, double closeRate, double decay, double hitAdd, double threshold, double weightBoost,
						   double cooldown, boolean forceAtMax, RepositionRule reposition) {}

	/**
	 * "pressure.reposition": 재배치 (보스가 직접 거리를 벌림 — 근접 방어의 하나) 를 고르는 조건.
	 *   압박이 min 이상 + 밀어내기를 쓴 지 afterPush 초 안 + 마지막 재배치 뒤에 밀어내기를 한 번 이상 씀 (밀어내기와 번갈아)
	 *   + 재배치 공통 쿨다운 (cooldown 초 — 계속 도망만 다니지 않게). 조건이 되면 가중치 × weightBoost. 쓰면 압박 0
	 */
	public record RepositionRule(double min, double afterPush, double cooldown, double weightBoost) {}

	/** 근접 구역 (보스 기준 — 몸 hurtbox 바닥 윤곽에서 잰 수평 거리, 블록 · 방향 각도). 커버리지 도구 · 근접 압박이 씀 */
	public final Zones zones;

	/**
	 * "zones": {"inner": 1.0, "close": 3.0, "mid": 8.0, "front": 45, "back": 135, "bodyParts": ["body", "leg_right", "leg_left"]}
	 *   inner 이하 = 밀착 (윤곽 안 = 하부 · 배 밑 · 다리 사이) · close 이하 = 근접 · mid 이하 = 중거리 · 그 밖 = 원거리
	 *   방향: |각도| ≤ front = 정면 · ≥ back = 후방 · 사이 = 좌 / 우 (+ = 오른쪽)
	 *   closeTurnRate: 목표가 밀착 · 근접 구역이면 예비 동작 (track 구간) 선회 속도 배율 (closeRangeTurnRateMultiplier — 활성 구간은 여전히 안 돎)
	 */
	public record Zones(double inner, double close, double mid, double front, double back, Set<String> bodyParts, double closeTurnRate) {}

	/**
	 * 공격 판정 구간 — 켜지는 hitbox · 대미지 · 헌터 반응 (flinch · knockback · launch).
	 * 소울류 필드: parryable (패링 가능) · unblockable (가드 불가 — 잡기 등) · poise (강인도 대미지) · guardStamina (가드 시 스태미나 소모),
	 * grab (닿으면 대미지 대신 잡힘 → 행동의 onGrab 으로)
	 * inner: 근접 보조 판정 — 본 판정과 같은 공격 인스턴스 (둘 중 먼저 닿은 것만), 대미지 × damageScale · 강인도 · 반응만 따로
	 * push (블록) · pushSeconds: 밀어내기 — 보스 중심에서 헌터 쪽으로 수평 외력 (감속 곡선, 헌터 속도에 더함 · 벽에 막히면 그냥 멈춤)
	 */
	public record Hit(int from, int to, Set<String> hitboxes, double damage, String reaction, boolean parryable, boolean unblockable,
					  double poise, double guardStamina, boolean grab, List<Inner> inner, double push, double pushSeconds) {
		/** frame 에 켜진 판정 (본 + 보조) */
		public boolean active(double frame) {
			if (frame >= from && frame <= to) {
				return true;
			}
			for (Inner i : inner) {
				if (frame >= i.from() && frame <= i.to()) {
					return true;
				}
			}
			return false;
		}

		/** frame 에 hitboxId 가 켜져 있는지 */
		public boolean boxActive(String hitboxId, double frame) {
			if (frame >= from && frame <= to && hitboxes.contains(hitboxId)) {
				return true;
			}
			for (Inner i : inner) {
				if (frame >= i.from() && frame <= i.to() && i.hitboxes().contains(hitboxId)) {
					return true;
				}
			}
			return false;
		}

		/** frame 에 켜진 판정 id 전부 */
		public Set<String> activeBoxes(double frame) {
			Set<String> out = new java.util.HashSet<>();
			if (frame >= from && frame <= to) {
				out.addAll(hitboxes);
			}
			for (Inner i : inner) {
				if (frame >= i.from() && frame <= i.to()) {
					out.addAll(i.hitboxes());
				}
			}
			return out;
		}

		/** 닿은 판정이 보조 판정이면 그 대미지 · 강인도 · 반응으로 (본 판정이면 그대로) */
		public Hit resolved(String hitboxId, double frame) {
			if (hitboxId == null || (frame >= from && frame <= to && hitboxes.contains(hitboxId))) {
				return this;
			}
			for (Inner i : inner) {
				if (i.hitboxes().contains(hitboxId)) {
					return new Hit(from, to, hitboxes, damage * i.damageScale(), i.reaction() != null ? i.reaction() : reaction, parryable,
							unblockable, i.poise() >= 0.0 ? i.poise() : poise, guardStamina, grab, inner, push, pushSeconds);
				}
			}
			return this;
		}
	}

	/** 근접 보조 판정 — frames · hitboxes · damageScale (본 대미지 배율) · poise (-1 = 본과 같음) · reaction (null = 본과 같음) */
	public record Inner(int from, int to, Set<String> hitboxes, double damageScale, double poise, String reaction) {}

	/**
	 * 행동 하나.
	 *   고르기 조건: range [최소, 최대] 거리 · angle [최소, 최대] 목표 방향(몸 기준, + = 오른쪽) · weight · cooldown(초) · rage (분노 때만 = true)
	 *   추적: track [시작, 끝] 프레임 동안 trackRate(°/s)로 목표를 향해 돎 (예비 동작 = 텔레그래프)
	 *         aimRight (°) = 목표를 정면이 아니라 그만큼 오른쪽에 두고 조준 (오른 앞발 공격이 목표에 떨어지게, - = 왼쪽)
	 *   회전: spin {"from", "to", "degrees"} — 그 구간 동안 몸이 그만큼 돎 (꼬리 휘두르기)
	 *   이동: root [[프레임, 앞, 오른쪽, 위]] 누적 이동량 (사이는 곡선 — 관성 · 감속), moveSpeed (추격: 초당 블록, 앞으로)
	 *   추격 끝: untilRange (목표가 이 거리 안이면 끝)
	 *   포효: roar [시작, 끝] 프레임 동안 범위 안 헌터 귀막기
	 *   interruptible: 경직에 끊길 수 있는지 (기본 true)
	 *   events · emitters: 연출 · 충격파 ({@link ActionFx})
	 */
	public record Action(String id, String animation, int length, boolean loop, double[] range, double[] angle, double weight,
						 double cooldown, boolean rageOnly, int[] track, double trackRate, int[] spin, double spinDegrees,
						 List<Hit> hits, double[][] root, double moveSpeed, double untilRange, int[] roar, boolean interruptible,
						 double aimRight, List<ActionFx.Event> events, List<ActionFx.Emitter> emitters, ActionFx.Flow flow) {

		public int hitIndex(double frame) {
			for (int i = 0; i < hits.size(); i++) {
				if (hits.get(i).active(frame)) {
					return i;
				}
			}
			return -1;
		}

		public boolean tracking(double frame) {
			return track != null && frame >= track[0] && frame <= track[1];
		}

		/** 누적 이동량 [앞, 오른쪽] — 키 사이는 부드러운 곡선 (smoothstep: 출발 · 정지가 완만해 관성처럼 보임) */
		/** 누적 이동량 [앞, 오른쪽, 위] (모델 블록) */
		public double[] rootOffset(double frame) {
			if (root.length == 0) {
				return new double[] {0.0, 0.0, 0.0};
			}
			if (frame <= root[0][0]) {
				return new double[] {root[0][1], root[0][2], root[0][3]};
			}
			for (int i = 0; i < root.length - 1; i++) {
				double[] a = root[i];
				double[] b = root[i + 1];
				if (frame < b[0]) {
					double f = (frame - a[0]) / Math.max(1.0E-9, b[0] - a[0]);
					f = f * f * (3.0 - 2.0 * f);
					return new double[] {a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f, a[3] + (b[3] - a[3]) * f};
				}
			}
			double[] last = root[root.length - 1];
			return new double[] {last[1], last[2], last[3]};
		}

		/** 위로 움직이는 구간이 있는 행동인지 (뛰어오르기 — 그 동안 중력 끔) */
		public boolean hasUp() {
			for (double[] r : root) {
				if (Math.abs(r[3]) > 1.0E-6) {
					return true;
				}
			}
			return false;
		}

		/** frame 까지의 누적 회전 (도) */
		public double spinAt(double frame) {
			if (spin == null || spinDegrees == 0.0) {
				return 0.0;
			}
			double f = (frame - spin[0]) / Math.max(1.0, spin[1] - spin[0]);
			f = Math.max(0.0, Math.min(1.0, f));
			return spinDegrees * f * f * (3.0 - 2.0 * f);
		}

		public boolean roaring(double frame) {
			return roar != null && frame >= roar[0] && frame <= roar[1];
		}
	}

	private MonsterDef(String id, JsonObject o) {
		this.id = id;
		collider = o.get("collider").getAsString();
		model = o.has("model") ? o.get("model").getAsString() : collider;
		texture = o.has("texture") ? o.get("texture").getAsString() : "huntcraft:textures/entity/" + model + ".png";
		textureOff = o.has("textureOff") ? o.get("textureOff").getAsString() : texture;
		health = num(o, "health", 2000);
		JsonArray size = o.getAsJsonArray("size");
		width = size == null ? 3.0 : size.get(0).getAsDouble();
		height = size == null ? 2.5 : size.get(1).getAsDouble();
		detectRange = num(o, "detectRange", 24);
		displayName = o.has("name") ? o.get("name").getAsString() : id;
		loseRange = num(o, "loseRange", 64);
		roarRadius = num(o, "roarRadius", 12);
		JsonObject po = o.has("poise") ? o.getAsJsonObject("poise") : new JsonObject();
		poiseMax = num(po, "max", 100);
		poiseRegenDelay = num(po, "regenDelay", 3.0);
		poiseRegenPerSec = num(po, "regenPerSec", 30);
		JsonObject sta = o.has("stance") ? o.getAsJsonObject("stance") : new JsonObject();
		stanceMax = num(sta, "max", 500);
		stanceRegenDelay = num(sta, "regenDelay", 5.0);
		stanceRegenPerSec = num(sta, "regenPerSec", 25);
		List<LockPoint> points = new ArrayList<>();
		if (o.has("lockOn")) {
			for (JsonElement e : o.getAsJsonArray("lockOn")) {
				JsonObject lp = e.getAsJsonObject();
				JsonArray at = lp.getAsJsonArray("at");
				points.add(new LockPoint(lp.has("name") ? lp.get("name").getAsString() : "", lp.get("bone").getAsString(),
						new double[] {at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble()}));
			}
		}
		lockOn = List.copyOf(points);
		JsonObject rage = o.getAsJsonObject("rage");
		rageBelow = rage == null ? 0.0 : num(rage, "healthBelow", 0.5);
		rageSpeed = rage == null ? 1.0 : num(rage, "speed", 1.2);
		rageAction = rage == null ? null : str(rage, "action");
		JsonObject roles = o.getAsJsonObject("roles");
		idleAction = str(roles, "idle");
		turnLeft = str(roles, "turnLeft");
		turnRight = str(roles, "turnRight");
		chase = str(roles, "chase");
		pause = str(roles, "pause");
		discover = str(roles, "discover");
		flinch = str(roles, "flinch");
		parried = str(roles, "parried");
		stanceBreak = str(roles, "stanceBreak");
		riposted = str(roles, "riposted");
		dormant = str(roles, "dormant");
		awaken = str(roles, "awaken");
		parryStance = num(sta, "parry", 150);
		knockdown = str(roles, "knockdown");
		death = str(roles, "death");
		JsonObject after = o.has("afterAttack") ? o.getAsJsonObject("afterAttack") : new JsonObject();
		pauseSeconds = after.has("pause") ? range(after, "pause") : new double[] {1.0, 2.0};
		ragePauseSeconds = after.has("ragePause") ? range(after, "ragePause") : pauseSeconds;
		chainChance = num(after, "chain", 0.1);
		rageChainChance = num(after, "rageChain", chainChance);
		JsonObject zo = o.has("zones") ? o.getAsJsonObject("zones") : new JsonObject();
		Set<String> parts = new java.util.HashSet<>();
		if (zo.has("bodyParts")) {
			zo.getAsJsonArray("bodyParts").forEach(e -> parts.add(e.getAsString()));
		} else {
			parts.addAll(List.of("body", "leg_right", "leg_left"));
		}
		JsonObject mu = o.has("music") ? o.getAsJsonObject("music") : null;
		music = mu == null ? null : new Music(str(mu, "intro"), str(mu, "phase1"), str(mu, "phase2"), str(mu, "ending"));
		JsonObject pr = o.has("pressure") ? o.getAsJsonObject("pressure") : new JsonObject();
		JsonObject rr = pr.has("reposition") ? pr.getAsJsonObject("reposition") : new JsonObject();
		pressure = new Pressure(num(pr, "max", 100), num(pr, "innerRate", 25), num(pr, "closeRate", 8), num(pr, "decay", 15), num(pr, "hitAdd", 10),
				num(pr, "threshold", 60), num(pr, "weightBoost", 8), num(pr, "cooldown", 6),
				!pr.has("forceAtMax") || pr.get("forceAtMax").getAsBoolean(),
				new RepositionRule(num(rr, "min", 35), num(rr, "afterPush", 10), num(rr, "cooldown", 9), num(rr, "weightBoost", 6)));
		zones = new Zones(num(zo, "inner", 1.0), num(zo, "close", 3.0), num(zo, "mid", 8.0), num(zo, "front", 45), num(zo, "back", 135), Set.copyOf(parts),
				num(zo, "closeTurnRate", 1.0));
		actions = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("actions").entrySet()) {
			try {
				actions.put(e.getKey(), action(e.getKey(), e.getValue().getAsJsonObject()));
			} catch (RuntimeException ex) {
				throw new IllegalArgumentException("행동 " + e.getKey() + ": " + ex.getMessage(), ex);
			}
		}
		for (String role : new String[] {idleAction, turnLeft, turnRight, chase, pause, discover, flinch, knockdown, death, rageAction, parried,
				stanceBreak, riposted, dormant, awaken}) {
			if (role != null && !actions.containsKey(role)) {
				throw new IllegalArgumentException("roles 가 없는 행동을 가리킴: " + role);
			}
		}
		for (Action a : actions.values()) {
			for (ActionFx.Combo c : a.flow().combo()) {
				if (!actions.containsKey(c.action())) {
					throw new IllegalArgumentException("행동 " + a.id() + ": combo 가 없는 행동을 가리킴: " + c.action());
				}
			}
			if (a.flow().repositions()) {
				for (String alt : a.flow().reposition().alts()) {
					if (!actions.containsKey(alt)) {
						throw new IllegalArgumentException("행동 " + a.id() + ": reposition.alts 가 없는 행동을 가리킴: " + alt);
					}
				}
			}
		}
	}

	public static MonsterDef parse(String id, JsonObject o) {
		return new MonsterDef(id, o);
	}

	private static Action action(String id, JsonObject a) {
		List<Hit> hits = new ArrayList<>();
		if (a.has("hits")) {
			for (JsonElement h : a.getAsJsonArray("hits")) {
				JsonObject ho = h.getAsJsonObject();
				JsonArray f = ho.getAsJsonArray("frames");
				java.util.Set<String> boxes = new java.util.HashSet<>();
				for (JsonElement b : ho.getAsJsonArray("hitboxes")) {
					boxes.add(b.getAsString());
				}
				List<Inner> inner = new ArrayList<>();
				if (ho.has("inner")) {
					for (JsonElement ie : ho.getAsJsonArray("inner")) {
						JsonObject io = ie.getAsJsonObject();
						JsonArray fr = io.getAsJsonArray("frames");
						Set<String> ib = new java.util.HashSet<>();
						io.getAsJsonArray("hitboxes").forEach(b -> ib.add(b.getAsString()));
						inner.add(new Inner(fr.get(0).getAsInt(), fr.get(1).getAsInt(), Set.copyOf(ib), num(io, "damageScale", 0.6), num(io, "poise", -1),
								str(io, "reaction")));
					}
				}
				hits.add(new Hit(f.get(0).getAsInt(), f.get(1).getAsInt(), boxes, num(ho, "damage", 10), str(ho, "reaction"),
						bool(ho, "parryable"), bool(ho, "unblockable"), num(ho, "poise", 0), num(ho, "guardStamina", 0), bool(ho, "grab"),
						List.copyOf(inner), num(ho, "push", 0), num(ho, "pushSeconds", 0.5)));
			}
		}
		double[][] root = new double[0][];
		if (a.has("root")) {
			JsonArray r = a.getAsJsonArray("root");
			root = new double[r.size()][];
			for (int i = 0; i < r.size(); i++) {
				JsonArray k = r.get(i).getAsJsonArray();
				root[i] = new double[] {k.get(0).getAsDouble(), k.get(1).getAsDouble(), k.size() > 2 ? k.get(2).getAsDouble() : 0.0,
						k.size() > 3 ? k.get(3).getAsDouble() : 0.0};
			}
		}
		int[] spin = null;
		double spinDeg = 0.0;
		if (a.has("spin")) {
			JsonObject s = a.getAsJsonObject("spin");
			spin = new int[] {s.get("from").getAsInt(), s.get("to").getAsInt()};
			spinDeg = s.get("degrees").getAsDouble();
		}
		return new Action(id, a.get("animation").getAsString(), a.get("length").getAsInt(), a.has("loop") && a.get("loop").getAsBoolean(),
				range(a, "range"), range(a, "angle"), num(a, "weight", 0), num(a, "cooldown", 0),
				a.has("rage") && a.get("rage").getAsBoolean(), ints(a, "track"), num(a, "trackRate", 150), spin, spinDeg, hits, root,
				num(a, "moveSpeed", 0), num(a, "untilRange", 0), ints(a, "roar"),
				!a.has("interruptible") || a.get("interruptible").getAsBoolean(), num(a, "aimRight", 0),
				ActionFx.events(a), ActionFx.emitters(a), ActionFx.flow(a));
	}

	private static double[] range(JsonObject o, String key) {
		if (!o.has(key)) {
			return null;
		}
		JsonArray r = o.getAsJsonArray(key);
		return new double[] {r.get(0).getAsDouble(), r.get(1).getAsDouble()};
	}

	private static int[] ints(JsonObject o, String key) {
		if (!o.has(key)) {
			return null;
		}
		JsonArray r = o.getAsJsonArray(key);
		return new int[] {r.get(0).getAsInt(), r.get(1).getAsInt()};
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) ? o.get(key).getAsString() : null;
	}

	private static double num(JsonObject o, String key, double def) {
		return o.has(key) ? o.get(key).getAsDouble() : def;
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && o.get(key).getAsBoolean();
	}
}
