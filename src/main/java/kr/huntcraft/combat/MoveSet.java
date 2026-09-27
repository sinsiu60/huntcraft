package kr.huntcraft.combat;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 무기 하나의 모션 데이터 (defs/motions/<무기>.json) — 코드 수정 없이 콤보 · 모션값 · 판정 프레임 · 캔슬 · 이동량을 튜닝.
 * 프레임 = 1/60초 (로직 스텝).
 *
 *   "idle": { "sheathed": {입력: 모션}, "drawn": {입력: 모션} }       서 있을 때 입력 → 모션
 *   "motions": { "<id>": {
 *       "animation": "animation.huntcraft.longsword.overhead",    Blockbench 애니메이션 이름
 *       "length": 42,                     모션 길이 (프레임)
 *       "turn": 60,                       시작할 때 입력 방향으로 돌 수 있는 최대 각도 (도). turnFree=true 면 제한 없음
 *       "hits": [ {"frames": [10, 16], "motionValue": 26, "hitstop": 4} ],   활성 구간마다 공격 인스턴스 1개
 *       "cancel": 24,                     이 프레임부터 next 로 넘어갈 수 있음 (선입력은 그 전부터 저장)
 *       "evadeCancel": 20,                이 프레임부터 회피 가능
 *       "root": [[프레임, 앞(블록), 오른쪽(블록)], ...]   누적 이동량 (사이는 직선 보간)
 *       "next": {입력: 모션},
 *       "draws"/"sheathes": 시작 시 발도 / 끝날 때 납도,  "directional": 입력 방향(뒤 · 왼쪽 · 오른쪽)으로 이동 방향을 돌림,
 *       "invuln": [[a, b]] 무적 프레임,   "stamina": 소모량,   "walk": 이 모션 중 걷기 속도 배율 (0 = 못 걸음)
 *       "idleNext": next 에 없는 입력은 대기 자세의 연결을 씀 (회피 뒤 발도/납도 상태에 맞는 기술)
 *       "grip": "both" | "right" | "none" | "item" — 칼자루를 쥐는 손 (기본 both). item = 오른손을 held 본(든 아이템)으로 뻗음 (납도여도).
 *               키프레임이 있는 팔은 키프레임 우선
 *       "name": "세로베기" — HUD 콤보 안내에 쓰는 이름
 *       "itemSelect": true — 고른 아이템의 motion 으로 바꿔서 시작 (아이템 사용 입력), 개수가 0 이면 입력을 버림
 *       "itemFrame": 48 — 이 프레임을 지날 때 고른 아이템의 효과 (회복 · 숫돌) · 개수 -1
 *       "fov": [[0, 0], [20, 8]]  카메라 시야각 더하기 (도, 프레임별 · camera.skillCameraEffects)
 *       "parry": [2, 12]          패링 구간 — 이 동안 보스 공격이 닿으면 맞지 않고 패링 이벤트 (Phase 에서 규칙 추가)
 *       "shake": [{"frame": 25, "strength": 0.06, "duration": 0.18}] — 그 프레임에 내 화면 흔들림 (camera.shakeScale 로 조절)
 *       "evade": true — 회피 모션 (player.evadeInvulnSeconds × 무게 배율 동안 무적, 이동량 · 스태미나도 무게 배율)
 *       "backstep": true — 백스텝 (evade 대신 player.backstepInvulnSeconds 동안 무적)
 *       "neutral": "backstep" — 방향 입력 없이 시작하면 이 모션을 대신 (구르기 → 백스텝)
 *       "hyperArmor": [a, b], "poiseBonus": 60 — 그 프레임 동안 강인도 보너스 (보스 공격 강인도 대미지가 이하이면 경직 없음, 없으면 player.hyperArmorPoise)
 *       "charge": {"at": 22, "into": "heavy_charge"} — at 프레임에 강공격 키를 누르고 있으면 차지 모션으로
 *       "chargeHold": {"max": 60, "release": "heavy_charged"} — 차지 모션: 키를 떼거나 max 프레임이 되면 release 로 (차지량 0~1)
 *       "charged": true — 차지량만큼 모션값 · 강인도 · 자세 대미지가 커짐 (weapon.charge*Bonus)
 *       "guardHold": true — 가드 키를 누르고 있는 동안만 이어짐 · "guardFrom": 3 — 이 프레임부터 가드 (정면 player.guardAngle)
 *       "guardNext": "guard" — 끝났을 때 가드 키를 누르고 있으면 이 모션으로 (막기 경직 → 다시 가드)
 *       "plunge": {"frame": 6, "speed": 18} — 공중이면 그 프레임에 아래로 speed (블록/초) — 점프 공격 내려찍기
 *       "critical": {"frame": 40, "motionValue": 300} — 치명 일격: 시작할 때 앞의 치명 구간 보스를 붙잡고 frame 에 대미지 (× damage.critMultiplier)
 *       "parry": [시작, 끝] — 패링 구간 시작 프레임 (길이는 player.parryWindowSeconds)
 *       "commit": true — 맞아도 끊기지 않음 (대미지만 받음, 회복 물약 마시기)
 *   hits 의 "poise" (강인도 대미지) · "stance" (보스 자세 대미지)
 *       "wakeUp": true — 끝난 뒤 player.wakeUpInvulnSeconds 동안 무적 (날아감 → 기상)
 *       "comboWindow": 36 — 모션이 끝나 대기로 돌아간 뒤에도 이 프레임 동안은 next 파생이 나감 (없으면 player.comboWindowSeconds)
 *       "variants": {"forward": id, "left": id, "right": id, "back": id} — 시작할 때 입력 방향(몸 기준)으로 골라 그 모션을 대신 시작
 *                  (입력이 없으면 forward). 이 모션 자체에는 animation · length 가 필요 없음
 *   } }
 */
public final class MoveSet {
	public final String id;
	public final String idleDrawnAnimation;
	/** 납도 대기 자세 (칼을 등에 멘 모습 — 보통 katana 본만 키프레임, 나머지는 바닐라 걷기) */
	public final String idleSheathedAnimation;
	/** 발도 대기 중 칼을 쥐는 손 */
	public final Grip idleDrawnGrip;
	public final Map<CombatInput, String> idleSheathed;
	public final Map<CombatInput, String> idleDrawn;
	/** 달리는 중 대기 입력 (달리기 공격) · 공중 대기 입력 (점프 공격) */
	public final Map<CombatInput, String> sprint;
	public final Map<CombatInput, String> air;
	/** 락온 중 입력표 (받아들여질 때 이것으로 바꿈 — 회피 → 방향별 구르기) */
	public final Map<CombatInput, String> locked;
	/** 앞에 치명 구간 보스가 있을 때 입력표 (받아들여질 때 이것으로 — 공격 → 치명 일격) */
	public final Map<CombatInput, String> critical;
	public final Map<String, Motion> motions;

	public record Hit(int from, int to, double motionValue, int hitstop, double poise, double stance) {}

	/** 소울류 모션 확장 (차지 · 가드 · 슈퍼아머 · 내려찍기). 없는 값은 -1 / null / false */
	public record Extra(int chargeAt, String chargeInto, int chargeMax, String chargeRelease, boolean charged, boolean guardHold,
						int guardFrom, String guardNext, int[] hyperArmor, double poiseBonus, int plungeFrame, double plungeSpeed, int critFrame,
						double critMotionValue, boolean commit) {
		public boolean hyperArmorAt(double frame) {
			return hyperArmor != null && frame >= hyperArmor[0] && frame <= hyperArmor[1];
		}

		public boolean guardAt(double frame) {
			return guardFrom >= 0 && frame >= guardFrom;
		}
	}

	/** 입력 방향 (몸 방향 기준) — 방향별 변형 모션 고르기 */
	public enum Direction {
		FORWARD, LEFT, RIGHT, BACK;

		/** 몸 방향 기준 입력 각도(도, + = 오른쪽) → 방향. 앞 ±45°, 옆 45~135°, 나머지 뒤 */
		public static Direction of(float relativeYaw) {
			float a = Math.abs(relativeYaw);
			if (a <= 45.0F) {
				return FORWARD;
			}
			if (a < 135.0F) {
				return relativeYaw > 0.0F ? RIGHT : LEFT;
			}
			return BACK;
		}

		/** 이 방향이 몸 방향에서 몇 도 돌아 있는지 (+ = 오른쪽) */
		public float offset() {
			return switch (this) {
				case FORWARD -> 0.0F;
				case RIGHT -> 90.0F;
				case LEFT -> -90.0F;
				case BACK -> 180.0F;
			};
		}
	}

	/** 카메라 흔들림 (내 헌터 화면에만) — frame 에 strength(블록) 세기로 duration(초) 동안, 점점 약해짐 */
	public record Shake(int frame, double strength, double duration) {}

	/** 칼자루를 쥐는 손 — 키프레임이 없는 팔은 grip_right / grip_left 본을 향해 자동으로 뻗음 */
	public enum Grip {
		BOTH, RIGHT, NONE, ITEM;

		static Grip parse(String s, Grip def) {
			return s == null ? def : Grip.valueOf(s.toUpperCase(java.util.Locale.ROOT));
		}
	}

	public record Motion(String id, String animation, int length, double turn, boolean turnFree, List<Hit> hits,
						 int cancel, int evadeCancel, double[][] root, Map<CombatInput, String> next,
						 boolean draws, boolean sheathes, boolean directional, int[][] invuln, double stamina, double walk,
						 boolean idleNext, Grip grip, List<Shake> shakes, boolean evade, Map<Direction, String> variants,
						 int comboWindow, boolean wakeUp, String name, boolean itemSelect, int itemFrame, double[][] fov, int[] parry,
						 String neutral, boolean backstep, Extra x) {

		/** 패링 구간인지 */
		public boolean parryAt(double frame) {
			return parry != null && frame >= parry[0] && frame <= parry[1];
		}

		/** 프레임의 시야각 더하기 (사이는 직선) */
		public double fovAt(double frame) {
			if (fov.length == 0) {
				return 0.0;
			}
			if (frame <= fov[0][0]) {
				return fov[0][1];
			}
			for (int i = 0; i + 1 < fov.length; i++) {
				if (frame < fov[i + 1][0]) {
					double f = (frame - fov[i][0]) / Math.max(1.0E-9, fov[i + 1][0] - fov[i][0]);
					return fov[i][1] + (fov[i + 1][1] - fov[i][1]) * f;
				}
			}
			return fov[fov.length - 1][1];
		}

		/** 프레임(소수 가능)이 속한 활성 구간 번호 (없으면 -1) */
		public int hitIndex(double frame) {
			for (int i = 0; i < hits.size(); i++) {
				Hit h = hits.get(i);
				if (frame >= h.from && frame <= h.to) {
					return i;
				}
			}
			return -1;
		}

		public boolean invulnerable(double frame) {
			for (int[] r : invuln) {
				if (frame >= r[0] && frame <= r[1]) {
					return true;
				}
			}
			return false;
		}

		/** 누적 이동량 [앞, 오른쪽] (블록) */
		public double[] rootOffset(double frame) {
			if (root.length == 0) {
				return new double[] {0.0, 0.0};
			}
			if (frame <= root[0][0]) {
				return new double[] {root[0][1], root[0][2]};
			}
			for (int i = 0; i < root.length - 1; i++) {
				double[] a = root[i];
				double[] b = root[i + 1];
				if (frame < b[0]) {
					double f = (frame - a[0]) / Math.max(1.0E-9, b[0] - a[0]);
					return new double[] {a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f};
				}
			}
			double[] last = root[root.length - 1];
			return new double[] {last[1], last[2]};
		}
	}

	private MoveSet(String id, String idleDrawnAnimation, String idleSheathedAnimation, Grip idleDrawnGrip,
					Map<CombatInput, String> idleSheathed, Map<CombatInput, String> idleDrawn, Map<CombatInput, String> sprint,
					Map<CombatInput, String> air, Map<CombatInput, String> locked, Map<CombatInput, String> critical, Map<String, Motion> motions) {
		this.id = id;
		this.idleDrawnAnimation = idleDrawnAnimation;
		this.idleSheathedAnimation = idleSheathedAnimation;
		this.idleDrawnGrip = idleDrawnGrip;
		this.idleSheathed = idleSheathed;
		this.idleDrawn = idleDrawn;
		this.sprint = sprint;
		this.air = air;
		this.locked = locked;
		this.critical = critical;
		this.motions = motions;
	}

	public Motion motion(String motionId) {
		return motionId == null ? null : motions.get(motionId);
	}

	public static MoveSet parse(String id, JsonObject o) {
		JsonObject idle = o.getAsJsonObject("idle");
		Map<String, Motion> motions = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("motions").entrySet()) {
			try {
				motions.put(e.getKey(), motion(e.getKey(), e.getValue().getAsJsonObject()));
			} catch (RuntimeException ex) {
				throw new IllegalArgumentException("모션 " + e.getKey() + ": " + ex.getMessage(), ex);
			}
		}
		MoveSet set = new MoveSet(id, str(o, "idleDrawnAnimation"), str(o, "idleSheathedAnimation"),
				Grip.parse(str(o, "idleDrawnGrip"), Grip.RIGHT),
				inputs(idle.getAsJsonObject("sheathed")), inputs(idle.getAsJsonObject("drawn")), inputs(idle.getAsJsonObject("sprint")),
				inputs(idle.getAsJsonObject("air")), inputs(idle.getAsJsonObject("locked")), inputs(idle.getAsJsonObject("critical")), motions);
		// 연결 대상이 실제로 있는지 (오타를 로드할 때 잡음)
		List<String> refs = new ArrayList<>(set.idleSheathed.values());
		refs.addAll(set.idleDrawn.values());
		refs.addAll(set.sprint.values());
		refs.addAll(set.air.values());
		refs.addAll(set.locked.values());
		refs.addAll(set.critical.values());
		motions.values().forEach(m -> {
			refs.addAll(m.next.values());
			refs.addAll(m.variants.values());
			for (String r : new String[] {m.neutral, m.x.chargeInto(), m.x.chargeRelease(), m.x.guardNext()}) {
				if (r != null) {
					refs.add(r);
				}
			}
		});
		for (String r : refs) {
			if (!motions.containsKey(r)) {
				throw new IllegalArgumentException("없는 모션으로 연결: " + r);
			}
		}
		return set;
	}

	private static Motion motion(String id, JsonObject m) {
		List<Hit> hits = new ArrayList<>();
		if (m.has("hits")) {
			for (JsonElement h : m.getAsJsonArray("hits")) {
				JsonObject ho = h.getAsJsonObject();
				JsonArray f = ho.getAsJsonArray("frames");
				hits.add(new Hit(f.get(0).getAsInt(), f.get(1).getAsInt(), num(ho, "motionValue", 0.0),
						ho.has("hitstop") ? ho.get("hitstop").getAsInt() : -1, num(ho, "poise", 0.0), num(ho, "stance", 0.0)));
			}
		}
		double[][] root = new double[0][];
		if (m.has("root")) {
			JsonArray r = m.getAsJsonArray("root");
			root = new double[r.size()][];
			for (int i = 0; i < r.size(); i++) {
				JsonArray k = r.get(i).getAsJsonArray();
				root[i] = new double[] {k.get(0).getAsDouble(), k.get(1).getAsDouble(), k.size() > 2 ? k.get(2).getAsDouble() : 0.0};
			}
		}
		int[][] invuln = new int[0][];
		if (m.has("invuln")) {
			JsonArray r = m.getAsJsonArray("invuln");
			invuln = new int[r.size()][];
			for (int i = 0; i < r.size(); i++) {
				JsonArray k = r.get(i).getAsJsonArray();
				invuln[i] = new int[] {k.get(0).getAsInt(), k.get(1).getAsInt()};
			}
		}
		List<Shake> shakes = new ArrayList<>();
		if (m.has("shake")) {
			for (JsonElement e : m.getAsJsonArray("shake")) {
				JsonObject so = e.getAsJsonObject();
				shakes.add(new Shake(so.get("frame").getAsInt(), num(so, "strength", 0.05), num(so, "duration", 0.15)));
			}
		}
		// 방향별 변형만 있는 모션 (예: 발도 회피 → 앞 · 왼 · 오른 · 뒤 구르기) — 자체 애니메이션 · 길이 없음
		Map<Direction, String> variants = new EnumMap<>(Direction.class);
		if (m.has("variants")) {
			for (Map.Entry<String, JsonElement> e : m.getAsJsonObject("variants").entrySet()) {
				variants.put(Direction.valueOf(e.getKey().toUpperCase(java.util.Locale.ROOT)), e.getValue().getAsString());
			}
		}
		int length = m.has("length") ? m.get("length").getAsInt() : 1;
		return new Motion(id, m.has("animation") ? m.get("animation").getAsString() : null, length, num(m, "turn", 0.0), bool(m, "turnFree"), hits,
				m.has("cancel") ? m.get("cancel").getAsInt() : length, m.has("evadeCancel") ? m.get("evadeCancel").getAsInt() : length,
				root, inputs(m.getAsJsonObject("next")), bool(m, "draws"), bool(m, "sheathes"), bool(m, "directional"), invuln,
				num(m, "stamina", 0.0), num(m, "walk", 0.0), bool(m, "idleNext"), Grip.parse(str(m, "grip"), Grip.BOTH),
				shakes, bool(m, "evade"), variants, m.has("comboWindow") ? m.get("comboWindow").getAsInt() : -1,
				bool(m, "wakeUp"), str(m, "name"), bool(m, "itemSelect"), m.has("itemFrame") ? m.get("itemFrame").getAsInt() : -1,
				curve(m, "fov"), m.has("parry") ? new int[] {m.getAsJsonArray("parry").get(0).getAsInt(), m.getAsJsonArray("parry").get(1).getAsInt()} : null,
				str(m, "neutral"), bool(m, "backstep"), extra(m));
	}

	private static Extra extra(JsonObject m) {
		JsonObject ch = m.has("charge") ? m.getAsJsonObject("charge") : null;
		JsonObject hold = m.has("chargeHold") ? m.getAsJsonObject("chargeHold") : null;
		JsonObject pl = m.has("plunge") ? m.getAsJsonObject("plunge") : null;
		JsonObject cr = m.has("critical") ? m.getAsJsonObject("critical") : null;
		int[] ha = null;
		if (m.has("hyperArmor")) {
			JsonArray a = m.getAsJsonArray("hyperArmor");
			ha = new int[] {a.get(0).getAsInt(), a.get(1).getAsInt()};
		}
		return new Extra(ch == null ? -1 : ch.get("at").getAsInt(), ch == null ? null : str(ch, "into"),
				hold == null ? -1 : hold.get("max").getAsInt(), hold == null ? null : str(hold, "release"), bool(m, "charged"),
				bool(m, "guardHold"), m.has("guardFrom") ? m.get("guardFrom").getAsInt() : -1, str(m, "guardNext"), ha,
				num(m, "poiseBonus", -1.0), pl == null ? -1 : pl.get("frame").getAsInt(), pl == null ? 0.0 : num(pl, "speed", 15.0),
				cr == null ? -1 : cr.get("frame").getAsInt(), cr == null ? 0.0 : num(cr, "motionValue", 300.0), bool(m, "commit"));
	}

	/** [[프레임, 값], ...] (없으면 빈 배열) */
	private static double[][] curve(JsonObject o, String key) {
		if (!o.has(key)) {
			return new double[0][];
		}
		JsonArray f = o.getAsJsonArray(key);
		double[][] out = new double[f.size()][];
		for (int i = 0; i < f.size(); i++) {
			JsonArray k = f.get(i).getAsJsonArray();
			out[i] = new double[] {k.get(0).getAsDouble(), k.get(1).getAsDouble()};
		}
		return out;
	}

	private static Map<CombatInput, String> inputs(JsonObject o) {
		Map<CombatInput, String> map = new EnumMap<>(CombatInput.class);
		if (o != null) {
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				map.put(CombatInput.valueOf(e.getKey()), e.getValue().getAsString());
			}
		}
		return map;
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsString() : null;
	}

	private static double num(JsonObject o, String key, double def) {
		return o.has(key) ? o.get(key).getAsDouble() : def;
	}

	private static boolean bool(JsonObject o, String key) {
		return o.has(key) && o.get(key).getAsBoolean();
	}
}
