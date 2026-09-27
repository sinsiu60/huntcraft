package kr.huntcraft.client.config;

import kr.huntcraft.config.ConfigIO;

/**
 * 클라이언트 설정 — config/huntcraft/client.json (카메라 · 입력 · HUD 배치). 판정 · 이동 수치는 서버의 common.json.
 * 거리 단위는 블록, 시간은 초, 각도는 도(°).
 */
public final class ClientConfig {
	private static ClientConfig instance = new ClientConfig();
	public static final int VERSION = 3;
	private static final java.util.List<ConfigIO.Migration> MIGRATIONS = java.util.List.of(
			// Phase 1 (소울류): 대형 보스를 담게 멀리 · 조금 높게 · 오른쪽은 살짝
			new ConfigIO.Migration(2, "camera.distance", 3.75, 4.5),
			new ConfigIO.Migration(2, "camera.heightOffset", 0.4, 0.65),
			new ConfigIO.Migration(2, "camera.rightOffset", 0.7, 0.4),
			// Phase 7 (소울류 HUD): HP 빨강 · 잔상 노랑 · 스태미나 초록
			new ConfigIO.Migration(3, "hud.colorHealth", 0xFF3FCF4A, 0xFFA3262A),
			new ConfigIO.Migration(3, "hud.colorDamageTrail", 0xFFF2E6C8, 0xFFE3BE4A),
			new ConfigIO.Migration(3, "hud.colorStamina", 0xFFF0C22E, 0xFF4E9A3E),
			new ConfigIO.Migration(3, "hud.colorStaminaExhausted", 0xFFE0662A, 0xFF9A8A3A));

	public int configVersion = VERSION;

	public Camera camera = new Camera();
	public Input input = new Input();
	public Hud hud = new Hud();
	/** 화면 색 보정 (소울류 — 채도를 빼고 칙칙하게). 세부 값은 assets/huntcraft/post_effect/souls_grade.json (리소스팩) */
	public Grade grade = new Grade();

	public static final class Grade {
		/** 끄면 원래 마인크래프트 색 (설정 다시 읽은 뒤 월드에 다시 들어가거나 관전 전환 때 적용) */
		public boolean enabled = true;
	}

	/** 소리 */
	public Sound sound = new Sound();
	/** 보스 음악 (곡은 몬스터 정의의 music) */
	public Music music = new Music();

	/**
	 * 보스 음악: volume = 배경 수준 배율 (게임의 음악 음량에 곱함) · crossfadeSeconds = 페이즈가 바뀔 때 엇갈려 바뀌는 시간
	 * stopFadeSeconds = 헌터가 쓰러지거나 전장이 초기화될 때 줄여 끄는 시간 · 처치 곡: endingFadeStart 초 뒤부터 endingFadeSeconds 동안 사라짐
	 */
	public static final class Music {
		public boolean enabled = true;
		public double volume = 0.4;
		public double crossfadeSeconds = 2.0;
		public double stopFadeSeconds = 2.5;
		public double endingFadeStart = 6.0;
		public double endingFadeSeconds = 26.0;
	}

	public static final class Sound {
		public Duck duck = new Duck();
	}

	/**
	 * 더킹: 큰 공격 착지 (화면 흔들림 세기 minShake 이상) 때 sources 의 볼륨을 depth 배로 잠깐 낮춤 → 보스 쾅 · 헌터 소리가 묻히지 않음.
	 * attack → hold → release (초). sources 는 소리 종류 이름 (music · records · weather · blocks · hostile · neutral · players · ambient · voice · ui)
	 */
	public static final class Duck {
		public boolean enabled = true;
		public double minShake = 0.25;
		public double depth = 0.35;
		public double attackSeconds = 0.04;
		public double holdSeconds = 0.35;
		public double releaseSeconds = 0.9;
		public java.util.List<String> sources = new java.util.ArrayList<>(java.util.List.of("music", "records", "weather", "blocks", "neutral", "ambient"));
	}

	/** 락온 게걸음 다리 (렌더) */
	public Strafe strafe = new Strafe();

	/** 게걸음 — 각도는 라디안, stride = 이동 거리 → 걸음 위상 (바닐라 걷기와 같은 0.6662 면 발이 미끄러지지 않음) */
	public static final class Strafe {
		/** 옆 이동 비율이 이 이상이어야 게걸음 */
		public double minSideShare = 0.2;
		public float stride = 0.6662F;
		/** 바닐라 걷기 세기 → 게걸음 세기 배율 (최대 1) */
		public float speedToAmplitude = 1.3F;
		/** 앞 다리가 옆으로 나가는 각도 · 뒤 다리는 그 비율만큼 반대로 */
		public double stepOut = 0.34;
		public double trailShare = 0.55;
		/** 들리는 다리를 앞으로 드는 각도 (무릎 들기) · 디딜 때 발끝을 가는 쪽으로 돌리는 각도 · 몸 기울기 */
		public double lift = 0.42;
		public double toeOut = 0.22;
		public double sway = 0.07;
	}

	public static final class Input {
		/** 키 배치 버전 (옛 설치의 키를 한 번 옮김 — HuntKeys.migrateLayout, 직접 고치지 마세요) */
		public int keyLayout = 0;
		/** Space 를 이보다 오래 누르면 달리기, 그 전에 떼면 구르기 (뗄 때 구름) */
		public double evadeHoldSeconds = 0.25;
	}

	public static final class Camera {
		public double distance = 4.5;
		/** 눈높이 위로 */
		public double heightOffset = 0.65;
		/** 오른쪽으로 (캐릭터가 화면 중앙보다 살짝 왼쪽에 보이게) */
		public double rightOffset = 0.4;
		public double fov = 70.0;
		/** 마우스 감도 배율 (바닐라 감도 설정 위에 곱함) */
		public double sensitivity = 1.0;
		/** 세로 각도 한계 (아래가 +) */
		public double pitchMin = -55.0;
		public double pitchMax = 70.0;
		/** 월드에 들어왔을 때 세로 각도 */
		public double initialPitch = 12.0;
		/**
		 * 스프링-댐퍼: 카메라 기준점이 캐릭터를 따라오는 강성(1/s²) · 감쇠비(1 = 임계 감쇠, 튕김 없음).
		 * 150 이면 약 0.3초에 따라붙고, 달리는 중에는 약 1블록 늦게 따라옵니다. 회전(마우스)은 늦추지 않습니다.
		 */
		public double springStiffness = 150.0;
		public double dampingRatio = 1.0;
		/** 세로(계단 · 낙하)는 따로 — 너무 늦으면 떨어질 때 캐릭터가 화면 밖으로 나감 */
		public double verticalSpringStiffness = 260.0;
		/** 기준점이 캐릭터에서 이보다 멀어지면 즉시 붙임 (순간이동 · 리스폰) */
		public double snapDistance = 6.0;
		/** 벽 충돌: 벽에서 띄울 거리 · 카메라 두께 */
		public double collisionPadding = 0.2;
		/** 벽에서 벗어난 뒤 원래 거리로 돌아가는 속도 (블록/초) — 당길 때는 즉시 */
		public double collisionRecoverSpeed = 5.0;
		/** 락온 시 카메라 회전 속도 한계 (°/s) */
		public double lockOnTurnSpeed = 360.0;
		/** 락온할 수 있는 거리 · 이보다 멀어지면 풀림 (블록) */
		public double lockOnRange = 40.0;
		public double lockOnKeepRange = 64.0;
		/** 락온 카메라 세로: 헌터와 지점 사이 이 비율 쪽을 봄 (0 = 헌터, 1 = 지점) · 더하는 각도 · 한계 (위 -, 아래 +) · 회전 속도 (°/s) */
		public double lockOnFrameBias = 0.6;
		public double lockOnPitchOffset = 4.0;
		public double lockOnPitchMin = -30.0;
		public double lockOnPitchMax = 35.0;
		public double lockOnPitchSpeed = 240.0;
		/** 보스가 이 거리 (몸 가장자리까지, 블록) 안이면 카메라가 뒤로 · 위로 빠짐 (가장 가까울 때 양) · 따라가는 빠르기 (1/s) */
		public double lockOnCloseRange = 5.0;
		public double lockOnCloseDistance = 1.2;
		public double lockOnCloseHeight = 0.6;
		public double lockOnCloseSmooth = 6.0;
		/** 대상 전환: 마우스를 이만큼 (도) 휙 움직이면 · 그 방향 ± 각도 안의 후보 · 전환 뒤 쉬는 시간 · 틱마다 남기는 비율 (천천히 흘리면 안 바뀜) */
		public double lockOnSwitchThreshold = 12.0;
		public double lockOnSwitchCone = 60.0;
		public double lockOnSwitchCooldown = 0.3;
		public double lockOnSwitchDecay = 0.85;
		/** 이만큼 (초) 가려져 있으면 락온 풀림 */
		public double lockOnLoseSightSeconds = 2.0;
		/** 모션이 몸을 띄울 때 카메라가 따라 올라가는 비율 (0 = 안 따라감) */
		public double followMotionHeight = 0.8;
		/** 기술별 줌 · 흔들림 연출 (false 면 전부 끔) */
		public boolean skillCameraEffects = true;
		/** 모션 데이터의 흔들림 세기 배율 (0 = 끔) */
		public double shakeScale = 1.0;
		public double hitShakeStrength = 0.15;
		/**
		 * 보스에게 잡혔을 때 연출: 헌터 어깨 너머로 보스 얼굴 (hold 의 stare) 을 봄.
		 * 헌터에서 얼굴 반대쪽으로 grabBack → grabBackEnd 까지 grabDolly 초 동안 천천히 다가감 · 옆 grabSide · 위 grabUp (블록),
		 * 보는 곳 = 헌터와 얼굴 사이 grabLookBias (1 = 얼굴), 들어갈 때 grabBlendIn · 나올 때 grabBlendOut 초
		 */
		public boolean grabCinematic = true;
		/** 보스 연출 (깨어남 · 페이즈 전환 · 처치) 동안 카메라가 보스를 비춤 (끄면 평소 카메라) */
		public boolean bossCinematic = true;
		public double grabBack = 3.2;
		public double grabBackEnd = 2.3;
		public double grabDolly = 1.6;
		public double grabSide = 1.7;
		public double grabUp = 0.2;
		public double grabLookBias = 0.85;
		public double grabBlendIn = 0.35;
		public double grabBlendOut = 0.6;
	}

	/** 위치는 화면 모서리 기준 GUI 좌표(px) — 그 모서리에서 안쪽으로 x · y, scale 은 배율. 색은 0xAARRGGBB */
	public static final class Hud {
		/** 바닐라 HUD (핫바 · 하트 · 허기 · 경험치 · 방어구 · 공기 · 들고 있는 아이템 이름) 숨김 */
		public boolean hideVanilla = true;
		/** 좌상단: HP · FP · 스태미나 */
		public Element status = new Element(8, 8, 1.0);
		/** 좌하단: 네 방향 장비칸 (모서리에서 안쪽 x · y) */
		public Element equipment = new Element(12, 12, 1.0);
		/** 하단 가운데: 보스 체력바 (x = 가운데에서 옆으로 · y = 아래에서 위로) */
		public Element bossBar = new Element(0, 40, 1.0);
		/** 오른손 무기 칸 아이콘 (아이템 id) */
		public String weaponIcon = "minecraft:iron_sword";

		/** 체력 · 스태미나 막대 길이 = 최대값 × 이 값 (px) — 최대가 늘면 막대도 길어짐 (몬헌) */
		public double pxPerHealth = 1.3;
		public double pxPerStamina = 1.3;
		public double pxPerFp = 1.3;
		public int healthHeight = 7;
		public int fpHeight = 4;
		public int staminaHeight = 4;
		public int barGap = 3;
		/** 맞은 뒤 줄어드는 노란 잔상 시간 (초) */
		public double damageTrailSeconds = 0.6;

		public int colorHealth = 0xFFA3262A;
		public int colorDamageTrail = 0xFFE3BE4A;
		public int colorFp = 0xFF3C62C8;
		public int colorStamina = 0xFF4E9A3E;
		public int colorStaminaExhausted = 0xFF9A8A3A;

		/** 보스 체력바: 길이 · 두께 (px) · 색 · 잔상 · 넣은 대미지 숫자 (마지막 명중 뒤 이 시간 동안) · 자세 게이지 (옵션) */
		public int bossBarWidth = 280;
		public int bossBarHeight = 5;
		/** 보스 체력바 최대 길이 = 화면 폭 × 이 비율 (작은 화면) */
		public double bossBarMaxShare = 0.62;
		public int colorBoss = 0xFF8E1E1E;
		public double bossTrailSeconds = 0.8;
		public double bossDamageKeepSeconds = 2.0;
		public int colorBossDamage = 0xFFE8DCC0;
		public boolean showBossStance = true;
		public int colorStance = 0xFFD6B25A;
		/** 월드 명중 자리에 대미지 숫자 (몬헌식) — 소울류는 끔 (보스 체력바 옆에 모아서) */
		public boolean damageNumbersInWorld = false;

		/** 큰 글씨 연출: 들어옴 · 머묾 · 사라짐 (초) · 글씨 배율 · 문구 · 색 */
		public double bannerFadeIn = 0.6;
		public double bannerHold = 2.2;
		public double bannerFadeOut = 1.0;
		public double bannerScale = 3.0;
		public String textDeath = "쓰러졌다";
		public String textVictory = "강적 격파";
		public String textRest = "모닥불 곁에서 숨을 골랐다";
		public int colorDeath = 0xFFB02020;
		public int colorVictory = 0xFFE0B040;
		public int colorRest = 0xFFE8D9B0;
		/** 상호작용 안내 글씨 색 */
		public int colorPrompt = 0xFFE8DCC0;
		public int colorBarBack = 0xB0101010;
		public int colorFrame = 0xFF6A6150;

		public double damageNumberScale = 1.0;
		public double damageNumberLifetime = 0.8;
		/** 떠오르는 높이 (블록) */
		public double damageNumberRise = 0.7;
		public int damageColorNormal = 0xFFFFFF;
		public int damageColorWeak = 0xFF9A1F;
		public int damageColorBounce = 0x9A9A9A;

		/** 락온 표시: 몬스터 키 × 이 비율 높이 · 크기(px) · 색 */
		public double lockOnHeight = 0.55;
		public int lockOnSize = 9;
		public int lockOnColor = 0xFFFF8A2A;

		/** 칼 궤적: 색 · 남는 시간 (초) · 끄기 */
		public boolean bladeTrail = true;
		public int trailColor = 0x90FFFFFF;
		public double trailSeconds = 0.14;

		public int debugTextColor = 0xE0E0E0;
	}

	public static final class Element {
		public int x;
		public int y;
		public double scale;

		public Element(int x, int y, double scale) {
			this.x = x;
			this.y = y;
			this.scale = scale;
		}
	}

	public static ClientConfig get() {
		return instance;
	}

	public static void load() {
		instance = ConfigIO.load("client.json", ClientConfig.class, new ClientConfig(), VERSION, MIGRATIONS);
	}
}
