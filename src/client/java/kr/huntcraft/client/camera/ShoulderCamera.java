package kr.huntcraft.client.camera;

import kr.huntcraft.client.config.ClientConfig;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 전용 숄더뷰 카메라 — 1인칭 · 바닐라 3인칭 대신 항상 이 카메라.
 *
 *   회전: 마우스가 플레이어가 아니라 이 카메라의 yaw/pitch 를 돌림 (MouseHandlerTurnMixin). 캐릭터 몸은 이동 입력 방향으로 따로 돎.
 *   위치: 기준점(눈높이 + heightOffset)이 캐릭터를 스프링-댐퍼로 따라오고, 거기서 오른쪽 rightOffset · 뒤로 distance.
 *         렌더 프레임마다 실제 경과 시간으로 적분하므로 60Hz 틱과 무관하게 부드럽습니다.
 *   벽: 눈 → 기준점 → 어깨 → 카메라 순서로 레이캐스트해서 막히면 즉시 당기고, 풀리면 천천히 돌아감.
 *
 * 카메라 엔티티가 내 플레이어가 아니거나(관전) 관전 모드면 바닐라 카메라 그대로.
 */
public final class ShoulderCamera {
	/** 바닐라 Entity.turn 과 같은 마우스 → 각도 배율 */
	private static final double MOUSE_TO_DEGREES = 0.15;
	private static final double MAX_SUBSTEP = 1.0 / 240.0;

	private static boolean initialized;
	private static float yaw;
	private static float pitch;

	private static Vec3 anchor;
	private static double velX;
	private static double velY;
	private static double velZ;
	private static long lastNanos;
	private static double currentDistance = -1.0;

	private static Vec3 position = Vec3.ZERO;
	/** 화면에 쓰는 각도 (연출이 섞이면 조작 각도 yaw/pitch 와 다름) */
	private static float renderYaw;
	private static float renderPitch;

	/** 화면 흔들림 (세기 블록 · 시작 · 길이 초) */
	private static double shakeStrength;
	private static long shakeStartNanos;
	private static double shakeDuration;

	private ShoulderCamera() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
		// 크로스헤어 숨김 (숄더뷰에서는 조준점이 의미 없음)
		HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, vanilla -> (g, dt) -> {
			if (!active(Minecraft.getInstance())) {
				vanilla.extractRenderState(g, dt);
			}
		});
	}

	public static void reset() {
		initialized = false;
		anchor = null;
		currentDistance = -1.0;
	}

	public static boolean active(Minecraft mc) {
		return mc.player != null && mc.level != null && !mc.player.isSpectator() && mc.getCameraEntity() == mc.player;
	}

	public static float yaw() {
		return yaw;
	}

	public static float pitch() {
		return pitch;
	}

	/** 이번 프레임 화면 각도 (CameraMixin) */
	public static float renderYaw() {
		return renderYaw;
	}

	public static float renderPitch() {
		return renderPitch;
	}

	/** 마우스 이동 (바닐라가 플레이어에 주던 값 그대로) */
	public static void turn(LocalPlayer player, double dx, double dy) {
		ensureInit(player);
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		double dYaw = dx * MOUSE_TO_DEGREES * cfg.sensitivity;
		double dPitch = dy * MOUSE_TO_DEGREES * cfg.sensitivity;
		if (LockOn.locked()) {
			// 락온 중: 카메라는 락온이 정하고, 마우스는 대상 전환 (한쪽으로 휙)
			LockOn.mouse(dYaw, dPitch);
			return;
		}
		yaw = Mth.wrapDegrees(yaw + (float) dYaw);
		pitch = Mth.clamp(pitch + (float) dPitch, (float) cfg.pitchMin, (float) cfg.pitchMax);
	}

	/**
	 * 화면 흔들림 — 카메라를 화면 좌우 · 위아래로 떨었다가 제곱으로 잦아듦. 겹치면 남은 세기가 큰 쪽.
	 * camera.skillCameraEffects=false 또는 shakeScale=0 이면 무시.
	 */
	public static void shake(double strength, double duration) {
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		double s = strength * cfg.shakeScale;
		if (!cfg.skillCameraEffects || s <= 0.0 || duration <= 0.0) {
			return;
		}
		if (s >= currentShake()) {
			shakeStrength = s;
			shakeDuration = duration;
			shakeStartNanos = System.nanoTime();
		}
	}

	private static double currentShake() {
		if (shakeDuration <= 0.0) {
			return 0.0;
		}
		double t = (System.nanoTime() - shakeStartNanos) / 1.0E9 / shakeDuration;
		return t >= 1.0 ? 0.0 : shakeStrength * (1.0 - t) * (1.0 - t);
	}

	/** 디버그 · 시험: 지금 흔들림 세기 (블록) */
	public static double shakeAmount() {
		return currentShake();
	}

	/** 카메라 각도를 바로 지정 (시험 · 이후 락온/연출용) */
	public static void setAngles(float newYaw, float newPitch) {
		initialized = true;
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		yaw = Mth.wrapDegrees(newYaw);
		pitch = Mth.clamp(newPitch, (float) cfg.pitchMin, (float) cfg.pitchMax);
	}

	public static void ensureInit(LocalPlayer player) {
		if (!initialized) {
			initialized = true;
			yaw = player.getYRot();
			pitch = (float) ClientConfig.get().camera.initialPitch;
		}
	}

	/** 내 헌터 모션의 root 본이 쉬는 자리보다 올라간 높이 (블록, 아래로는 0) */
	private static double motionLift(float partial) {
		kr.huntcraft.combat.HunterBody body = kr.huntcraft.client.combat.ClientHunters.localBody();
		if (body == null || body.combat().current() == null || body.colliderDef() == null) {
			return 0.0;
		}
		kr.huntcraft.collision.Skeleton sk = body.colliderDef().skeleton;
		int root = sk.find("root");
		kr.huntcraft.collision.Transform[] bones = kr.huntcraft.collision.ColliderInstance.solve(body, partial, null);
		if (root < 0 || bones == null) {
			return 0.0;
		}
		double animated = bones[root].apply(sk.pivot(root)).y;
		double rest = body.root(partial).apply(sk.pivot(root)).y;
		return Math.max(0.0, animated - rest);
	}

	/** 이번 프레임 카메라 위치를 계산 (CameraMixin 이 적용) */
	public static Vec3 update(Minecraft mc, float partialTick) {
		LocalPlayer player = mc.player;
		ensureInit(player);
		ClientConfig.Camera cfg = ClientConfig.get().camera;

		Vec3 feet = player.getPosition(partialTick);
		// 모션이 몸을 띄우면 (투구깨기 점프 등) 카메라도 그만큼 따라 올라감 (camera.followMotionHeight 비율)
		Vec3 eye = feet.add(0.0, player.getEyeHeight() + motionLift(partialTick) * cfg.followMotionHeight, 0.0);
		// 락온 대상이 가까우면 조금 뒤 · 위로 빠짐
		double[] close = LockOn.closeOffset(player, frameDtCache);
		double distance = cfg.distance + close[0];
		Vec3 target = eye.add(0.0, cfg.heightOffset + close[1], 0.0);
		follow(target, cfg);

		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		Vec3 look = new Vec3(-Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad), Math.cos(yawRad) * Math.cos(pitchRad));
		Vec3 right = new Vec3(-Math.cos(yawRad), 0.0, -Math.sin(yawRad));
		Vec3 up = right.cross(look).normalize();

		// 눈 → 기준점 (스프링이 늦어 기준점이 천장 · 벽 속에 있을 수 있음)
		Vec3 pivot = clip(mc, player, eye, anchor, cfg.collisionPadding);
		// 기준점 → 어깨
		Vec3 shoulder = clip(mc, player, pivot, pivot.add(right.scale(cfg.rightOffset)), cfg.collisionPadding);
		// 어깨 → 뒤: 카메라 두께만큼 떨어진 평행선 4개 + 가운데 중 가장 짧은 것
		double wanted = distance;
		double pad = cfg.collisionPadding;
		Vec3[] offsets = {Vec3.ZERO, right.scale(pad), right.scale(-pad), up.scale(pad), up.scale(-pad)};
		for (Vec3 o : offsets) {
			Vec3 from = shoulder.add(o);
			Vec3 to = from.subtract(look.scale(distance + pad));
			HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
			if (hit.getType() != HitResult.Type.MISS) {
				wanted = Math.min(wanted, hit.getLocation().distanceTo(from) - pad);
			}
		}
		wanted = Math.max(0.3, wanted);
		double dt = frameDtCache;
		if (currentDistance < 0.0 || wanted < currentDistance) {
			currentDistance = wanted;
		} else {
			currentDistance = Math.min(wanted, currentDistance + cfg.collisionRecoverSpeed * dt);
		}
		position = shoulder.subtract(look.scale(currentDistance));
		double amp = currentShake();
		if (amp > 0.0) {
			// 서로 다른 주파수 두 개를 섞어 규칙적으로 보이지 않게 (좌우 · 위아래)
			double t = (System.nanoTime() - shakeStartNanos) / 1.0E9;
			double sx = Math.sin(t * 2.0 * Math.PI * 23.0) * 0.7 + Math.sin(t * 2.0 * Math.PI * 37.0 + 1.3) * 0.3;
			double sy = Math.sin(t * 2.0 * Math.PI * 29.0 + 0.5) * 0.7 + Math.sin(t * 2.0 * Math.PI * 41.0 + 2.1) * 0.3;
			position = position.add(right.scale(sx * amp)).add(up.scale(sy * amp));
		}
		renderYaw = yaw;
		renderPitch = pitch;
		// 잡힘 연출 (보스 얼굴 쪽) 을 섞음
		GrabCinematic.Shot shot = GrabCinematic.update(mc, partialTick, dt);
		if (shot != null) {
			double w = shot.weight();
			position = position.lerp(shot.position(), w);
			renderYaw = yaw + (float) (Mth.wrapDegrees(shot.yaw() - yaw) * w);
			renderPitch = pitch + (float) ((shot.pitch() - pitch) * w);
		}
		return position;
	}

	/** 마지막 렌더 프레임 간격 (초) */
	private static double frameDtCache;

	/** 기준점 스프링 (임계 감쇠 기본). 렌더 프레임 간격을 작은 스텝으로 나눠 적분 */
	private static void follow(Vec3 target, ClientConfig.Camera cfg) {
		long now = System.nanoTime();
		double dt = lastNanos == 0L ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0E9);
		lastNanos = now;
		frameDtCache = dt;
		if (anchor == null || anchor.distanceTo(target) > cfg.snapDistance) {
			anchor = target;
			velX = velY = velZ = 0.0;
			return;
		}
		double k = cfg.springStiffness;
		double kv = cfg.verticalSpringStiffness;
		double c = 2.0 * cfg.dampingRatio * Math.sqrt(k);
		double cv = 2.0 * cfg.dampingRatio * Math.sqrt(kv);
		double x = anchor.x;
		double y = anchor.y;
		double z = anchor.z;
		int steps = Math.max(1, (int) Math.ceil(dt / MAX_SUBSTEP));
		double h = dt / steps;
		for (int i = 0; i < steps; i++) {
			velX += (k * (target.x - x) - c * velX) * h;
			velY += (kv * (target.y - y) - cv * velY) * h;
			velZ += (k * (target.z - z) - c * velZ) * h;
			x += velX * h;
			y += velY * h;
			z += velZ * h;
		}
		anchor = new Vec3(x, y, z);
	}

	private static Vec3 clip(Minecraft mc, LocalPlayer player, Vec3 from, Vec3 to, double pad) {
		HitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
		if (hit.getType() == HitResult.Type.MISS) {
			return to;
		}
		Vec3 dir = to.subtract(from);
		double len = dir.length();
		if (len < 1.0E-6) {
			return from;
		}
		double allowed = Math.max(0.0, hit.getLocation().distanceTo(from) - pad);
		return from.add(dir.scale(allowed / len));
	}

	/** 디버그: 기준점이 캐릭터 목표에서 얼마나 늦었는지 (블록) */
	public static double lag(Minecraft mc) {
		if (anchor == null || mc.player == null) {
			return 0.0;
		}
		Vec3 target = mc.player.position().add(0.0, mc.player.getEyeHeight() + ClientConfig.get().camera.heightOffset, 0.0);
		return anchor.distanceTo(target);
	}

	public static double distance() {
		return currentDistance;
	}
}
