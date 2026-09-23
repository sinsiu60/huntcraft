package kr.huntcraft.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공용 설정 — config/huntcraft/common.json (서버 권한 판정에 쓰는 값).
 * 시간 단위는 전부 초(s). 로직 스텝(1/60초)으로의 변환은 FixedTick.ticks() 가 합니다.
 *
 * 무기 모션 · 몬스터 행동 · 판정 도형 같은 큰 데이터는 이후 Phase 에서 별도 JSON 데이터 파일로 분리합니다.
 */
public final class CommonConfig {
	private static CommonConfig instance = new CommonConfig();

	public Damage damage = new Damage();
	public Player player = new Player();
	public Hitstop hitstop = new Hitstop();
	public Debug debug = new Debug();

	/** 대미지 = 무기공격력 × (모션값/100) × (육질/100) × 예리도 × 회심 × 기인 */
	public static final class Damage {
		public double motionValueDivisor = 100.0;
		public double hitzoneDivisor = 100.0;
		/** 예리도 색 → 물리 보정 */
		public Map<String, Double> sharpnessMultiplier = sharpnessDefaults();
		/** 튕김 판정: 육질 × 예리도 보정 이 이 값 미만이면 튕김 */
		public double bounceThreshold = 25.0;
		public double critChance = 0.0;
		public double critMultiplier = 1.25;
		/** 기인 레벨(0 없음, 1 흰, 2 노랑, 3 빨강) → 공격력 보정 */
		public double[] spiritLevelMultiplier = {1.00, 1.05, 1.10, 1.20};
		/** 육질 이 값 이상이면 약점 (대미지 숫자 주황) */
		public double weakHitzoneThreshold = 45.0;

		/** 낮은 색 → 높은 색 순서 유지 (파일에서 읽기 쉽게) */
		private static Map<String, Double> sharpnessDefaults() {
			Map<String, Double> m = new LinkedHashMap<>();
			m.put("red", 0.50);
			m.put("orange", 0.75);
			m.put("yellow", 1.00);
			m.put("green", 1.05);
			m.put("blue", 1.20);
			m.put("white", 1.32);
			return m;
		}
	}

	public static final class Player {
		public double maxHealth = 100.0;
		public double maxStamina = 100.0;
		/** 회피 구르기 무적 */
		public double evadeInvulnSeconds = 0.20;
		/** 날아감 → 기상 시 무적 */
		public double wakeUpInvulnSeconds = 0.50;
		/** 선입력 저장 시간 */
		public double inputBufferSeconds = 0.25;
	}

	public static final class Hitstop {
		/** 기술별 값이 없을 때 쓰는 기본 히트스톱 (4프레임 = 4/60초) */
		public int defaultFrames = 4;
		public boolean enabled = true;
	}

	public static final class Debug {
		/** 명중 로그를 채팅으로 */
		public boolean hitLogChat = true;
		/** 명중 로그를 logs/huntcraft-hits.log 로 */
		public boolean hitLogFile = true;
	}

	public static CommonConfig get() {
		return instance;
	}

	public static void load() {
		instance = ConfigIO.load("common.json", CommonConfig.class, new CommonConfig());
	}
}
