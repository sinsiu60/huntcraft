package kr.huntcraft.client.config;

import kr.huntcraft.config.ConfigIO;

/**
 * 클라이언트 설정 — config/huntcraft/client.json (카메라 · 조작감 · HUD 배치).
 * 거리 단위는 블록, 시간은 초, 각도는 도(°).
 */
public final class ClientConfig {
	private static ClientConfig instance = new ClientConfig();

	public Camera camera = new Camera();
	public Movement movement = new Movement();
	public Hud hud = new Hud();

	public static final class Camera {
		public double distance = 3.75;
		/** 눈높이 위로 */
		public double heightOffset = 0.4;
		/** 오른쪽으로 (캐릭터가 화면 중앙보다 왼쪽에 보이게) */
		public double rightOffset = 0.7;
		public double fov = 70.0;
		/** 스프링-댐퍼: 강성(1/s²) · 감쇠비(1 = 임계 감쇠) */
		public double springStiffness = 120.0;
		public double dampingRatio = 1.0;
		/** 벽 충돌 레이캐스트 후 벽에서 띄울 거리 */
		public double collisionPadding = 0.2;
		/** 락온 시 카메라 회전 속도 한계 (°/s) */
		public double lockOnTurnSpeed = 360.0;
		/** 기술별 줌 · 흔들림 연출 */
		public boolean skillCameraEffects = true;
		public double hitShakeStrength = 0.15;
	}

	public static final class Movement {
		/** 캐릭터 몸 회전 속도 한계 (°/s) */
		public double turnRate = 540.0;
		/** 이동 속도 (블록/s) */
		public double sheathedSpeed = 4.3;
		public double drawnSpeed = 3.0;
		public double sprintSpeed = 6.0;
		public double staminaSprintDrainPerSec = 15.0;
		public double staminaRegenPerSec = 25.0;
		/** 공격 시작 시 입력 방향으로 돌 수 있는 최대 각도 */
		public double attackStartMaxTurn = 60.0;
	}

	/** 위치는 화면 모서리 기준 GUI 좌표(px), scale 은 배율 */
	public static final class Hud {
		/** 좌상단: 체력 · 스태미나 · 예리도 · 기인게이지 */
		public Element status = new Element(8, 8, 1.0);
		/** 우하단: 아이템 바 */
		public Element itemBar = new Element(8, 8, 1.0);
		/** 우상단: 다음 연결 기술 · 키 */
		public Element comboGuide = new Element(8, 8, 1.0);
		public double damageNumberScale = 1.0;
		public double damageNumberLifetime = 0.8;
		public int damageColorNormal = 0xFFFFFF;
		public int damageColorWeak = 0xFF9A1F;
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
		instance = ConfigIO.load("client.json", ClientConfig.class, new ClientConfig());
	}
}
