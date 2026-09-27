package kr.huntcraft.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 공용 설정 — config/huntcraft/common.json (서버 권한 판정에 쓰는 값).
 * 시간 단위는 전부 초(s). 로직 스텝(1/60초)으로의 변환은 FixedTick.ticks() 가 합니다.
 *
 * 무기 모션 · 몬스터 행동 · 판정 도형 같은 큰 데이터는 이후 Phase 에서 별도 JSON 데이터 파일로 분리합니다.
 */
public final class CommonConfig {
	private static CommonConfig instance = new CommonConfig();
	/** 기본값을 바꿀 때마다 올리고 MIGRATIONS 에 적음 (옛 파일의 옛 기본값만 새 값으로) */
	public static final int VERSION = 5;
	private static final List<ConfigIO.Migration> MIGRATIONS = List.of(
			// Phase 1 (소울류): 구르기 무적 0.4초 · 선입력 0.2초 · 스태미나 회복 지연 0.5초
			new ConfigIO.Migration(2, "player.evadeInvulnSeconds", 0.20, 0.40),
			new ConfigIO.Migration(2, "player.inputBufferSeconds", 0.25, 0.20),
			new ConfigIO.Migration(2, "stamina.regenDelaySeconds", 0.4, 0.5),
			// Phase 3 (소울류): 무기 공격력 (흡수율 · 부위 배율 식) · 한 공격이 같은 대상은 부위와 상관없이 1번
			new ConfigIO.Migration(3, "weapon.attack", 200.0, 120.0),
			new ConfigIO.Migration(3, "collision.hitOncePerTarget", false, true),
			// 골렘 삐걱임 모루 소리 제거
			new ConfigIO.Migration(4, "effects.golem_creak.sound", "minecraft:block.anvil.use", ""),
			// v5: 사운드 강화 — 소리는 assets/huntcraft/sounds.json 의 huntcraft: 사운드 이벤트 (자리표시 파일 · 높낮이는 그쪽에)
			new ConfigIO.Migration(5, "effects.golem_step.sound", "minecraft:entity.iron_golem.step", "huntcraft:golem.step"),
			new ConfigIO.Migration(5, "effects.golem_step.volume", 1.6, 0.5),
			new ConfigIO.Migration(5, "effects.golem_step.pitch", 0.55, 1.0),
			new ConfigIO.Migration(5, "effects.golem_grab.sound", "minecraft:entity.iron_golem.attack", "huntcraft:golem.grab"),
			new ConfigIO.Migration(5, "effects.golem_grab.volume", 1.4, 1.0),
			new ConfigIO.Migration(5, "effects.golem_grab.pitch", 0.6, 1.0),
			new ConfigIO.Migration(5, "effects.hunter_hurt.sound", "minecraft:entity.player.hurt", "huntcraft:hunter.hurt"),
			new ConfigIO.Migration(5, "effects.hunter_hurt.volume", 0.9, 0.75),
			new ConfigIO.Migration(5, "effects.parry.sound", "minecraft:item.trident.hit_ground", "huntcraft:hunter.parry"),
			new ConfigIO.Migration(5, "effects.parry.volume", 1.6, 1.0),
			new ConfigIO.Migration(5, "effects.parry.pitch", 1.4, 1.0),
			new ConfigIO.Migration(5, "effects.guard_break.sound", "minecraft:item.shield.break", "huntcraft:hunter.guard_break"),
			new ConfigIO.Migration(5, "effects.guard_break.volume", 1.2, 1.0),
			new ConfigIO.Migration(5, "effects.guard_break.pitch", 0.8, 1.0));

	public int configVersion = VERSION;

	public Weapon weapon = new Weapon();
	public Damage damage = new Damage();
	public Player player = new Player();
	public Movement movement = new Movement();
	public Stamina stamina = new Stamina();
	public Collision collision = new Collision();
	public Hitstop hitstop = new Hitstop();
	public Boss boss = new Boss();
	/** 진행 흐름 (체크포인트 · 사망 · 안개 문) */
	public Progress progress = new Progress();
	/** 사각지대 커버리지 맵 (디버그 — /huntcraft coverage) */
	public CoverageTool coverage = new CoverageTool();

	/** 격자 간격 (블록) · 반지름 (블록, 보스 가운데 기준) · 프레임당 샘플 수 (빠른 주먹이 헌터를 건너뛰지 않게) */
	public static final class CoverageTool {
		public double spacing = 0.5;
		public double radius = 6.0;
		public int substeps = 8;
	}

	public static final class Progress {
		/** 모닥불 (체크포인트) 에서 휴식할 수 있는 거리 (블록) */
		public double restRange = 3.0;
		/** 쓰러진 뒤 부활까지 (초) — 짧게 (죽음은 학습) */
		public double deathSeconds = 4.0;
		/** 안개 문: 밖에서 이 거리 안이면 E 로 들어감 · 헤치는 시간 (초) · 들어간 뒤 문에서 안쪽으로 떨어진 거리 · 3틱마다 안개 파티클 수 */
		public double fogReach = 3.0;
		public double fogPassSeconds = 1.0;
		public double fogInside = 3.0;
		public int fogParticles = 10;
		/** 처음 들어왔을 때 앉아 있는 보스가 깨어나는 거리 (블록) */
		public double awakenRange = 14.0;
		/** 처치 연출이 끝나고 먼지가 되기 시작할 때까지 · 사라지는 데 걸리는 시간 (초) · 틱당 먼지 수 (가장 많을 때) */
		public double dissolveDelaySeconds = 1.0;
		public double dissolveSeconds = 5.0;
		public int dustPerTick = 8;
	}

	/** 보스 전장 생성 (/huntcraft arena build) */
	public Arena arena = new Arena();
	/** 아이템 (휠로 고르고 E 로 사용) — 순서 = 아이템 바 순서 */
	public List<ItemDef> items = ItemDef.defaults();
	public Debug debug = new Debug();

	/** 장비한 태도 (아이템 · 장비 시스템 전까지 고정값) */
	public static final class Weapon {
		/** 무기 공격력 — 대미지 = 공격력 × 모션값/100 × (1 − 흡수율) × 부위 배율 */
		public double attack = 120.0;
		/** 최대 차지 공격의 모션값 · 강인도 · 자세 대미지 더하기 비율 (차지량 0~1 에 비례) */
		public double chargeDamageBonus = 0.6;
		public double chargePoiseBonus = 0.8;
		public double chargeStanceBonus = 1.5;
	}

	/**
	 * 효과 (사운드 · 파티클) — 사건 이름 → 설정. sound / particle 은 바닐라 id (비우면 없음).
	 * 사건: hit · hit_weak · swing · hunter_hurt · roar · slay · golem_step · golem_slam · golem_creak · golem_grab · golem_charge · golem_wall · rubble_break (보스 행동 events 의 effect 이름)
	 * 파일에 없는 사건은 기본값을 씀 (새 사건이 생겨도 옛 설정 파일이 그대로 동작)
	 *
	 * 보스 공격 레이어 (행동 events 에서 뼈 위치로 틂): golem_tell (일반 예고) · golem_tell_heavy (큰 공격 예고) · golem_warn (가드 불가 · 잡기 통합 경고)
	 *   · golem_kick (도약 · 돌진 발 구름 = 재배치) · golem_swing / golem_swing_heavy (휘두름) · golem_impact / golem_impact_heavy (착지 · 흔들림과 같은 프레임)
	 *   · golem_tail (잔해 여운) · boss_hit (보스 공격이 헌터에 닿음)
	 * 헌터: hunter_hurt / hunter_hurt_heavy (player.heavyHitDamage 기준 2단계) · guard_hit (가드 성공) · guard_break · parry
	 * 볼륨 위계: 착지 1.0 > 경고 0.8~0.95 > 휘두름 0.6 > 발걸음 · 삐걱 0.4~0.5 (1 을 넘기면 소리는 그대로고 들리는 거리만 늘어남)
	 */
	public Map<String, Effect> effects = Effect.defaults();

	public static final class Effect {
		public String sound = "";
		public double volume = 1.0;
		public double pitch = 1.0;
		public String particle = "";
		public int count = 0;
		public double spread = 0.2;
		public double speed = 0.1;
		/** 들리는 거리 (블록, 서버가 이 안의 플레이어에게 보냄) — 0 = 이 사건의 기본값 (기본값도 없으면 바닐라 16×볼륨). 클라이언트 감쇠는 sounds.json 의 attenuation_distance */
		public double range = 0.0;
		/** 높낮이 무작위 ± 비율 (0.05 = ±5%) — 같은 소리가 반복돼도 기계적으로 들리지 않게 */
		public double pitchJitter = 0.05;

		static Effect of(String sound, double volume, double pitch, String particle, int count, double spread, double speed) {
			Effect e = new Effect();
			e.sound = sound;
			e.volume = volume;
			e.pitch = pitch;
			e.particle = particle;
			e.count = count;
			e.spread = spread;
			e.speed = speed;
			return e;
		}

		Effect range(double r) {
			range = r;
			return this;
		}

		public static Map<String, Effect> defaults() {
			Map<String, Effect> m = new LinkedHashMap<>();
			m.put("hit", of("minecraft:entity.player.attack.strong", 0.8, 1.1, "minecraft:crit", 8, 0.15, 0.4));
			m.put("hit_weak", of("minecraft:entity.player.attack.crit", 0.9, 1.0, "minecraft:crit", 14, 0.2, 0.5));
			m.put("swing", of("minecraft:entity.player.attack.sweep", 0.45, 1.3, "", 0, 0.0, 0.0));
			m.put("hunter_hurt", of("huntcraft:hunter.hurt", 0.75, 1.0, "minecraft:damage_indicator", 4, 0.2, 0.1).range(20));
			m.put("hunter_hurt_heavy", of("huntcraft:hunter.hurt_heavy", 1.0, 1.0, "minecraft:damage_indicator", 10, 0.3, 0.15).range(28));
			m.put("guard_hit", of("huntcraft:hunter.guard", 0.9, 1.0, "minecraft:crit", 6, 0.2, 0.25).range(24));
			m.put("roar", of("minecraft:entity.ravager.roar", 2.5, 0.75, "", 0, 0.0, 0.0));
			m.put("slay", of("minecraft:ui.toast.challenge_complete", 1.0, 1.0, "", 0, 0.0, 0.0));
			m.put("golem_step", of("huntcraft:golem.step", 0.5, 1.0, "minecraft:poof", 4, 0.4, 0.02).range(32));
			// 보스 공격 레이어 — 소리 파일 · 자리표시 높낮이 · 감쇠 거리는 assets/huntcraft/sounds.json
			m.put("golem_tell", of("huntcraft:golem.tell", 0.8, 1.0, "minecraft:electric_spark", 5, 0.15, 0.1).range(40));
			m.put("golem_tell_heavy", of("huntcraft:golem.tell_heavy", 0.85, 1.0, "minecraft:electric_spark", 10, 0.3, 0.15).range(48));
			m.put("golem_warn", of("huntcraft:golem.warn", 0.95, 1.0, "minecraft:electric_spark", 18, 0.3, 0.25).range(56));
			// 밀어내기 패턴 공통 예고 (짧고 날카로움)
			m.put("golem_warn_push", of("huntcraft:golem.warn_push", 0.9, 1.0, "minecraft:electric_spark", 10, 0.4, 0.3).range(48));
			m.put("golem_kick", of("huntcraft:golem.kick", 0.9, 1.0, "minecraft:poof", 10, 0.6, 0.05).range(48));
			m.put("golem_swing", of("huntcraft:golem.swing", 0.6, 1.0, "", 0, 0.0, 0.0).range(32));
			m.put("golem_swing_heavy", of("huntcraft:golem.swing_heavy", 0.65, 1.0, "", 0, 0.0, 0.0).range(36));
			m.put("golem_impact", of("huntcraft:golem.impact", 1.0, 1.0, "minecraft:poof", 8, 0.5, 0.04).range(48));
			m.put("golem_impact_heavy", of("huntcraft:golem.impact_heavy", 1.0, 1.0, "minecraft:explosion", 3, 0.8, 0.0).range(64));
			m.put("golem_tail", of("huntcraft:golem.debris", 0.55, 1.0, "minecraft:poof", 4, 0.6, 0.02).range(40));
			m.put("boss_hit", of("huntcraft:golem.impact", 0.9, 1.15, "", 0, 0.0, 0.0).range(40));
			// 삐걱임: 소리 없이 관절 불꽃만 (모루 소리는 부자연스럽고 여러 번 겹쳐서 뺌 — 사용자 요청)
			m.put("golem_creak", of("", 0.45, 0.45, "minecraft:electric_spark", 6, 0.15, 0.08));
			m.put("rubble_break", of("minecraft:block.stone.break", 1.4, 0.6, "minecraft:poof", 12, 0.6, 0.05));
			m.put("golem_grab", of("huntcraft:golem.grab", 1.0, 1.0, "", 0, 0.0, 0.0).range(40));
			m.put("golem_charge", of("minecraft:entity.ravager.step", 1.6, 0.6, "minecraft:poof", 6, 0.6, 0.03));
			m.put("golem_wall", of("minecraft:entity.iron_golem.damage", 1.6, 0.5, "minecraft:explosion", 1, 0.3, 0.0));
			m.put("golem_slam", of("minecraft:entity.generic.explode", 1.8, 0.55, "minecraft:explosion", 3, 0.8, 0.0));
			m.put("parry", of("huntcraft:hunter.parry", 1.0, 1.0, "minecraft:electric_spark", 24, 0.25, 0.4).range(40));
			m.put("critical", of("minecraft:entity.player.attack.crit", 1.6, 0.7, "minecraft:damage_indicator", 20, 0.4, 0.2));
			m.put("guard_break", of("huntcraft:hunter.guard_break", 1.0, 1.0, "minecraft:crit", 12, 0.3, 0.3).range(32));
			m.put("golem_power", of("minecraft:block.beacon.activate", 1.6, 0.55, "minecraft:electric_spark", 40, 0.5, 0.3));
			m.put("golem_steam", of("minecraft:block.fire.extinguish", 1.6, 0.5, "minecraft:cloud", 40, 1.0, 0.15));
			m.put("golem_break", of("minecraft:entity.iron_golem.death", 2.0, 0.5, "minecraft:explosion", 2, 0.6, 0.0));
			m.put("golem_part", of("minecraft:entity.iron_golem.damage", 1.4, 0.4, "minecraft:electric_spark", 24, 0.3, 0.3));
			m.put("fog_enter", of("minecraft:block.portal.travel", 0.25, 1.6, "minecraft:cloud", 30, 0.8, 0.02));
			m.put("rest", of("minecraft:block.fire.ambient", 1.4, 0.8, "minecraft:flame", 16, 0.3, 0.03));
			m.put("stance_break", of("minecraft:entity.iron_golem.damage", 1.8, 0.45, "minecraft:electric_spark", 30, 0.8, 0.3));
			return m;
		}
	}

	/**
	 * 아이템 하나. heal = 회복량 (healSeconds 동안 나눠서).
	 * count = 처음 개수 (-1 = 무한), motion = 사용 모션 (defs/motions 의 id), icon = 아이템 바에 그릴 바닐라 아이템 id
	 */
	public static final class ItemDef {
		public String id;
		public String name;
		public String icon;
		public int count;
		public double heal;
		public double healSeconds = 1.0;
		public String motion;

		static ItemDef of(String id, String name, String icon, int count, double heal, String motion) {
			ItemDef d = new ItemDef();
			d.id = id;
			d.name = name;
			d.icon = icon;
			d.count = count;
			d.heal = heal;
			d.motion = motion;
			return d;
		}

		static List<ItemDef> defaults() {
			List<ItemDef> l = new ArrayList<>();
			l.add(of("flask", "회복 물약", "minecraft:honey_bottle", 5, 45.0, "use_drink"));
			return l;
		}
	}

	/** 대미지 = 무기공격력 × (모션값/100) × (육질/100) × 회심 (소울류 식 — 흡수율 · 부위 배율 — 으로 바뀔 예정) */
	public static final class Damage {
		public double motionValueDivisor = 100.0;
		/** 치명 일격 배율 (Phase 6) */
		public double critMultiplier = 3.0;
	}

	public static final class Player {
		public double maxHealth = 100.0;
		/** 구르기 무적 (시작 1프레임부터, 보통 무게) — 무게별 배율은 rollClasses */
		public double evadeInvulnSeconds = 0.40;
		/** 백스텝 (방향 입력 없이 회피) 무적 */
		public double backstepInvulnSeconds = 0.20;
		/** 장비 무게 (light · medium · heavy) — 장비 시스템 전까지 고정 */
		public String equipLoad = "medium";
		/** 무게별 구르기: 무적 시간 · 이동 거리 · 스태미나 배율 */
		public java.util.Map<String, RollClass> rollClasses = RollClass.defaults();
		/** 슈퍼아머 강인도 보너스 기본값 (모션에 poiseBonus 가 없을 때) — 보스 공격 강인도 대미지가 이하면 경직 없음 */
		public double hyperArmorPoise = 60.0;
		/** 가드: 정면 각도 (°) · 막은 대미지 경감 비율 · 공격의 guardStamina 배율 · guardStamina 가 없는 공격은 대미지당 소모 */
		public double guardAngle = 120.0;
		public double guardAbsorption = 0.85;
		/** 가드로 받은 밀어내기 외력 배율 (가드 붕괴면 그대로) */
		public double guardPushScale = 0.6;
		/** 맞은 소리 2단계: 이 대미지 이상이면 hunter_hurt_heavy */
		public double heavyHitDamage = 40.0;
		public double guardStaminaScale = 1.0;
		public double guardStaminaPerDamage = 0.5;
		/** FP (전투 기술 · 주문 확장용 — 지금은 쓰지 않음) */
		public double maxFp = 60.0;
		/** 패링 구간 길이 (패링 모션의 parry 시작 프레임부터) */
		public double parryWindowSeconds = 0.17;
		/** 치명 일격: 보스 몸 가장자리에서 이 거리 (블록) 안 · 보스 정면 ± 각도 (°) 안이어야 */
		public double critRange = 2.8;
		public double critAngle = 70.0;
		/** 날아감 → 기상 시 무적 */
		public double wakeUpInvulnSeconds = 0.50;
		/** 선입력 저장 시간 (연타가 의도치 않은 추가 공격이 되지 않게 짧게) */
		public double inputBufferSeconds = 0.20;
		/** 공격이 끝나 대기로 돌아간 뒤에도 다음 파생 공격이 나가는 시간 (태도의 긴 콤보 유지). 모션의 comboWindow 가 우선 */
		public double comboWindowSeconds = 0.6;
		/** 몬스터와 겹쳤을 때 헌터가 가져가는 밀림 몫 (몬스터가 훨씬 무거움 — 1 = 헌터만 밀림) */
		public double pushShare = 0.95;
		/** 겹친 깊이 중 한 틱에 풀어 주는 비율 (작을수록 부드럽게) */
		public double pushStiffness = 0.35;
	}

	/** 무게별 구르기 (배율 1 = 보통) */
	public static final class RollClass {
		public double invulnScale = 1.0;
		public double distanceScale = 1.0;
		public double staminaScale = 1.0;

		public RollClass() {}

		RollClass(double invuln, double distance, double stamina) {
			this.invulnScale = invuln;
			this.distanceScale = distance;
			this.staminaScale = stamina;
		}

		static java.util.Map<String, RollClass> defaults() {
			java.util.Map<String, RollClass> m = new LinkedHashMap<>();
			m.put("light", new RollClass(1.1, 1.15, 0.9));
			m.put("medium", new RollClass(1.0, 1.0, 1.0));
			m.put("heavy", new RollClass(0.75, 0.75, 1.25));
			return m;
		}
	}

	/** 지금 장비 무게의 구르기 배율 (없으면 보통) */
	public RollClass rollClass() {
		RollClass r = player.rollClasses.get(player.equipLoad);
		return r != null ? r : new RollClass();
	}

	/**
	 * 플레이어 이동 — 속도는 블록/초 (바닐라 걷기 약 4.3, 달리기 약 5.6). 계산은 클라이언트(예측), 값은 서버가 보내 준 것을 씀.
	 * 몸은 입력 방향으로 turnRate 한계 안에서 돌고, 항상 몸이 보는 방향으로만 걷습니다 (옆걸음 없음).
	 */
	public static final class Movement {
		public double walkSpeed = 4.3;
		/** Space 를 누르고 있으면 (스태미나 소모) */
		public double sprintSpeed = 6.2;
		/** 몸 회전 속도 한계 (°/s) */
		public double turnRate = 720.0;
		/** 입력과 몸 방향 차이만큼 전진 속도를 줄임: cos(차이)^이 값 (0 = 줄이지 않음). 크면 뒤돌 때 제자리에서 돎 */
		public double turnSlowdownExponent = 1.0;
		/** 목표 속도까지 걸리는 시간 상수 (초) — 출발 · 정지 관성 */
		public double accelTime = 0.07;
		public double decelTime = 0.05;
		/** 공중에서 방향 조작 비율 (0 = 관성만) */
		public double airControl = 0.0;
		public double waterSpeedMultiplier = 0.5;
		/** 락온 중 (달리기 아닐 때): 몸은 대상을 보고 입력 방향으로 게걸음 · 뒷걸음 — 걷기 속도 배율 (옆 · 뒤) */
		public double lockStrafeSpeed = 0.85;
		public double lockBackSpeed = 0.7;
		/** 공격 시작 시 입력 방향으로 돌 수 있는 최대 각도 (Phase 3) */
		public double attackStartMaxTurn = 60.0;
	}

	public static final class Stamina {
		public double max = 100.0;
		public double sprintDrainPerSec = 15.0;
		public double regenPerSec = 25.0;
		/** 소모를 멈춘 뒤 회복이 시작되기까지 */
		public double regenDelaySeconds = 0.5;
		/** 가드 중 회복 속도 배율 (가드는 Phase 3) */
		public double guardRegenMultiplier = 0.4;
		/** true: 조금이라도 남아 있으면 행동하고 0 에서 멈춤 (소울류) · false: 모자라면 행동 불가 */
		public boolean allowPartialCost = true;
		/** 바닥나면 이만큼 찰 때까지 달리기 불가 (지침 상태) */
		public double exhaustedResumeAt = 30.0;
	}

	/** 판정 코어 (스윕 · 인스턴스 · 되감기) */
	public static final class Collision {
		/** 스텝 사이 스윕 (끄면 스텝 끝 자세만 검사 → 빠른 공격이 관통할 수 있음, 비교 시험용) */
		public boolean sweepEnabled = true;
		/** 서브스텝 간격 = 가장 얇은 도형 두께 × 이 값 (1 이하면 연속 샘플이 서로 겹침) */
		public double spacingFraction = 0.5;
		/** 간격 하한 (블록) — 아주 얇은 도형에서 서브스텝이 폭증하지 않게 */
		public double minSpacing = 0.02;
		public int maxSubsteps = 64;
		/** 첫 접촉 시각 이분법 횟수 (6 = 서브스텝 길이의 1/64 정밀도) */
		public int toiRefineIterations = 6;
		/** true 면 한 공격 인스턴스가 같은 표적을 부위와 상관없이 1번만 (기본: 같은 부위만 1번) */
		public boolean hitOncePerTarget = true;
		/** 공격자가 플레이어일 때 표적을 되감는 최대 틱 (0 = 끔, 싱글플레이) */
		public int rewindTicks = 0;
		/** 본 변환 기록 길이 (틱) — 되감기 한계 */
		public int historyTicks = 30;
	}

	/** 보스 (정의는 defs/monsters/<id>.json) */
	public static final class Boss {
		/** /huntcraft monster spawn 에서 이름을 안 적었을 때 · 저장된 개체의 정의가 없을 때 */
		public String defaultId = "iron_golem";
	}

	/** 부서진 마을 전장 — 길이는 블록 */
	public static final class Arena {
		/** 같은 seed 면 같은 모양 (명령에서 seed 를 적으면 그 값) */
		public long seed = 1105L;
		/** 전장 반지름 (둘레 건물까지) · 가운데 평평한 싸움터 반지름 */
		public int radius = 34;
		public int fightRadius = 14;
		/** 바깥 벽 두께 · 높이 범위 */
		public int wallThickness = 5;
		public int wallHeightMin = 5;
		public int wallHeightMax = 11;
		/** 위로 비우는 높이 · 아래 땅 두께 */
		public int clearHeight = 28;
		public int groundDepth = 8;
		/** 남쪽 문 폭 · 기둥 높이 · 문 앞으로 비워 두는 각도 (°, 건물 없음) · 문 밖 통로 길이 */
		public int gateWidth = 5;
		public int gateHeight = 7;
		public double gateClearAngle = 32.0;
		public int corridorLength = 10;
		/** 무너진 집 수 · 집 벽 높이 · 종탑 높이 */
		public int houses = 8;
		public int houseWallHeight = 5;
		public int towerHeight = 12;
		/** 둘레에 흩어진 잔해 · 풀 비율 (칸당) · 싸움터 주먹 자국 수 · 양귀비 수 */
		public double debrisDensity = 0.06;
		public int craters = 4;
		public int poppies = 10;
		/** 보스 자리: 가운데에서 북쪽으로 */
		public int bossOffset = 6;
	}

	public static final class Hitstop {
		/** 기술별 값이 없을 때 쓰는 기본 히트스톱 (4프레임 = 4/60초) */
		public int defaultFrames = 4;
		public boolean enabled = true;
	}

	public static final class Debug {
		/** 명중 로그를 채팅으로 (Phase 5 부터 기본 끔 — 대미지 숫자가 대신함, 튜닝할 때 켬) */
		public boolean hitLogChat = false;
		/** 명중 로그를 logs/huntcraft-hits.log 로 */
		public boolean hitLogFile = true;
	}

	public static CommonConfig get() {
		return instance;
	}

	public static void load() {
		instance = ConfigIO.load("common.json", CommonConfig.class, new CommonConfig(), VERSION, MIGRATIONS);
	}

	/** 멀티 서버가 보내 준 설정으로 교체 (접속 중에만, 파일에는 쓰지 않음) */
	public static void applyRemote(String json) {
		CommonConfig remote = ConfigIO.fromJson(json, CommonConfig.class);
		if (remote != null) {
			instance = remote;
		}
	}

	public static String toJson() {
		return ConfigIO.toJson(instance);
	}
}
