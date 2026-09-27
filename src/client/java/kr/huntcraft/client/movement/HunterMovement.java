package kr.huntcraft.client.movement;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.combat.HunterCombat;
import kr.huntcraft.combat.MoveSet;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.hunter.HunterRules;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.net.HunterSyncPayload;
import kr.huntcraft.net.IntentPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

/**
 * 헌터 이동 (클라이언트 예측) — 바닐라 이동 계산을 대체합니다. 충돌 · 중력 · 계단 오르기는 바닐라 그대로.
 *
 *   방향: WASD 를 카메라 yaw 기준 월드 방향으로 바꿈
 *   회전: 몸(yRot)이 그 방향으로 turnRate(°/s) 한계 안에서 돎 — 카메라를 따라 돌지 않음
 *   전진: 항상 몸이 보는 방향으로만 (옆걸음 없음). 입력과 몸 방향이 벌어질수록 cos(차이) 만큼 느려져, 뒤돌 때는 제자리에서 돎
 *   속도: 납도 · 발도 · 달리기 속도(블록/초)를 accelTime · decelTime 관성으로 따라감. 공중에서는 관성 유지
 *   스태미나: 서버와 같은 규칙(HunterRules)으로 예측하고, 서버 값이 오면 맞춤
 *   모션 중(공격 · 회피): 몸 방향 고정, 이동은 모션 데이터의 루트모션 (walk 가 있는 모션은 느리게 걸을 수 있음)
 *
 * 매 틱 PlayerTravelMixin 이 {@link #travel} 을 부릅니다 (바닐라 travel 직전).
 */
public final class HunterMovement {
	private static HunterState state = new HunterState(100.0);
	/** 틱당 블록 (수평) */
	private static double velX;
	private static double velZ;
	private static double desiredYaw;
	private static boolean moving;
	private static boolean lastSentSprint;
	private static boolean lastSentMoving;
	private static boolean intentSent;
	/** Phase 3: 공격 중 회전 금지 */
	private static boolean rotationLocked;

	private HunterMovement() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> CommonConfig.load());
		ClientTickEvents.END_CLIENT_TICK.register(HunterMovement::endTick);
		ClientPlayNetworking.registerGlobalReceiver(HunterSyncPayload.TYPE, (msg, ctx) -> onSync(msg));
		ClientPlayNetworking.registerGlobalReceiver(kr.huntcraft.net.ConfigSyncPayload.TYPE,
				(msg, ctx) -> CommonConfig.applyRemote(msg.json()));
	}

	public static HunterState state() {
		return state;
	}

	public static double desiredYaw() {
		return desiredYaw;
	}

	public static double horizontalSpeed() {
		return Math.sqrt(velX * velX + velZ * velZ) * FixedTick.HZ;
	}

	public static void setRotationLocked(boolean locked) {
		rotationLocked = locked;
	}

	/** 우리 이동을 적용할 상황인지 (비행 · 탈것 · 겉날개 · 관전은 바닐라) */
	public static boolean controls(LocalPlayer p) {
		return ShoulderCamera.active(Minecraft.getInstance()) && !p.getAbilities().flying && !p.isPassenger() && !p.isFallFlying();
	}

	/** 바닐라 travel 직전: 몸 회전 · 수평 속도를 정하고, 바닐라에는 입력 0 을 넘김 */
	public static Vec3 travel(LocalPlayer p, Vec3 vanillaInput) {
		Minecraft mc = Minecraft.getInstance();
		if (!ShoulderCamera.active(mc)) {
			return vanillaInput;
		}
		ShoulderCamera.ensureInit(p);
		if (!controls(p)) {
			// 비행 등: 바닐라 이동을 쓰되 기준 방향만 카메라로
			p.setYRot(ShoulderCamera.yaw());
			return vanillaInput;
		}
		CommonConfig.Movement cfg = CommonConfig.get().movement;
		HunterCombat combat = ClientHunters.local();
		MoveSet.Motion motion = combat.current();
		// C 점프: 땅 위 · 모션 중이 아닐 때만 (바닐라 점프 — Space 는 회피라 바닐라 점프 키 기능은 꺼져 있음)
		if (kr.huntcraft.client.input.ComboResolver.takeJump() && motion == null && p.onGround() && !p.isInWater()) {
			p.jumpFromGround();
		}
		if (motion != null && motion.walk() <= 0.0) {
			return motionTravel(p, combat, motion);
		}
		double walkFactor = motion != null ? motion.walk() : 1.0;
		Vec2 mv = p.input.getMoveVector();
		float bodyYaw = p.getYRot();
		// 락온 (달리기 아님): 몸은 대상을 보고, 이동은 입력 방향 그대로 (게걸음 · 뒷걸음)
		float lockYaw = kr.huntcraft.client.camera.LockOn.bodyYaw(p);
		if (!Float.isNaN(lockYaw) && !state.sprinting) {
			return lockedTravel(p, mv, lockYaw, walkFactor, cfg);
		}
		double speed = 0.0;
		moving = mv.lengthSquared() > 1.0E-4F;
		if (moving) {
			double camRad = Math.toRadians(ShoulderCamera.yaw());
			double fx = -Math.sin(camRad);
			double fz = Math.cos(camRad);
			// 왼쪽 = 오른쪽의 반대 (오른쪽 = (-cos, 0, -sin))
			double lx = Math.cos(camRad);
			double lz = Math.sin(camRad);
			double dx = fx * mv.y + lx * mv.x;
			double dz = fz * mv.y + lz * mv.x;
			double len = Math.sqrt(dx * dx + dz * dz);
			desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
			if (!rotationLocked) {
				bodyYaw = approach(bodyYaw, (float) desiredYaw, (float) (cfg.turnRate * FixedTick.DT));
			}
			double diff = Math.abs(Mth.wrapDegrees(desiredYaw - bodyYaw));
			double factor = Math.max(0.0, Math.cos(Math.toRadians(diff)));
			if (cfg.turnSlowdownExponent > 0.0) {
				factor = Math.pow(factor, cfg.turnSlowdownExponent);
			} else {
				factor = 1.0;
			}
			speed = HunterRules.moveSpeed(state) * Math.min(1.0, len) * factor * walkFactor;
			if (p.isInWater()) {
				speed *= cfg.waterSpeedMultiplier;
			}
		}
		double bodyRad = Math.toRadians(bodyYaw);
		double targetX = -Math.sin(bodyRad) * speed / FixedTick.HZ;
		double targetZ = Math.cos(bodyRad) * speed / FixedTick.HZ;
		double currentSpeed = Math.sqrt(velX * velX + velZ * velZ) * FixedTick.HZ;
		double tau = speed > currentSpeed ? cfg.accelTime : cfg.decelTime;
		double alpha = tau <= 0.0 ? 1.0 : 1.0 - Math.exp(-FixedTick.DT / tau);
		double control = p.onGround() || p.isInWater() ? 1.0 : cfg.airControl;
		velX += (targetX - velX) * alpha * control;
		velZ += (targetZ - velZ) * alpha * control;

		face(p, bodyYaw);
		applyVelocity(p);
		return Vec3.ZERO;
	}

	/** 락온 이동: 몸은 lockYaw 쪽으로 turnRate 한계 안에서 돎, 속도는 입력 방향 (옆 lockStrafeSpeed · 뒤 lockBackSpeed 배율) */
	private static Vec3 lockedTravel(LocalPlayer p, Vec2 mv, float lockYaw, double walkFactor, CommonConfig.Movement cfg) {
		float bodyYaw = rotationLocked ? p.getYRot() : approach(p.getYRot(), lockYaw, (float) (cfg.turnRate * FixedTick.DT));
		double targetX = 0.0;
		double targetZ = 0.0;
		moving = mv.lengthSquared() > 1.0E-4F;
		if (moving) {
			double camRad = Math.toRadians(ShoulderCamera.yaw());
			double dx = -Math.sin(camRad) * mv.y + Math.cos(camRad) * mv.x;
			double dz = Math.cos(camRad) * mv.y + Math.sin(camRad) * mv.x;
			double len = Math.sqrt(dx * dx + dz * dz);
			desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
			// 몸 기준 입력 방향: 앞 = 1, 옆 = lockStrafeSpeed, 뒤 = lockBackSpeed (사이는 섞음)
			double rel = Math.toRadians(Mth.wrapDegrees(desiredYaw - bodyYaw));
			double fwd = Math.cos(rel);
			double side = Math.abs(Math.sin(rel));
			double scale = fwd >= 0.0 ? fwd * fwd + side * side * cfg.lockStrafeSpeed : fwd * fwd * cfg.lockBackSpeed + side * side * cfg.lockStrafeSpeed;
			double speed = HunterRules.moveSpeed(state) * Math.min(1.0, len) * scale * walkFactor;
			if (p.isInWater()) {
				speed *= cfg.waterSpeedMultiplier;
			}
			targetX = dx / len * speed / FixedTick.HZ;
			targetZ = dz / len * speed / FixedTick.HZ;
		}
		double currentSpeed = Math.sqrt(velX * velX + velZ * velZ) * FixedTick.HZ;
		double wantSpeed = Math.sqrt(targetX * targetX + targetZ * targetZ) * FixedTick.HZ;
		double tau = wantSpeed > currentSpeed ? cfg.accelTime : cfg.decelTime;
		double alpha = tau <= 0.0 ? 1.0 : 1.0 - Math.exp(-FixedTick.DT / tau);
		double control = p.onGround() || p.isInWater() ? 1.0 : cfg.airControl;
		velX += (targetX - velX) * alpha * control;
		velZ += (targetZ - velZ) * alpha * control;
		face(p, bodyYaw);
		applyVelocity(p);
		return Vec3.ZERO;
	}

	/** 이번 틱 수평 속도 + 몬스터 밀어내기 */
	private static void applyVelocity(LocalPlayer p) {
		Vec3 push = MonsterPush.push(p).add(PushForce.step(p));
		Vec3 d = p.getDeltaMovement();
		p.setDeltaMovement(velX + push.x, d.y, velZ + push.z);
	}

	/**
	 * 모션 중: 몸은 모션이 시작할 때 정한 방향 고정 (회전 없음), 이동은 모션 데이터의 루트모션만.
	 * 히트스톱 동안은 멈춤. 공중이면 관성 유지.
	 */
	private static Vec3 motionTravel(LocalPlayer p, HunterCombat combat, MoveSet.Motion motion) {
		float facing = combat.facing();
		double dx = 0.0;
		double dz = 0.0;
		if (combat.hitstop() == 0 && combat.prevFrame() >= 0) {
			double[] a = motion.rootOffset(combat.prevFrame());
			double[] b = motion.rootOffset(combat.frame());
			// 구르기 이동량은 장비 무게 배율 (가벼움 멀리 · 무거움 짧게)
			double scale = motion.evade() && !motion.backstep() ? CommonConfig.get().rollClass().distanceScale : 1.0;
			double forward = (b[0] - a[0]) * scale;
			double right = (b[1] - a[1]) * scale;
			double rad = Math.toRadians(facing + combat.rootYaw());
			// 앞 = (-sin, cos), 오른쪽 = (-cos, -sin)
			dx = -Math.sin(rad) * forward - Math.cos(rad) * right;
			dz = Math.cos(rad) * forward - Math.sin(rad) * right;
		}
		if (p.onGround() || p.isInWater()) {
			velX = dx;
			velZ = dz;
		}
		// 점프 공격 내려찍기: 공중이면 그 프레임에 아래로
		MoveSet.Extra x = motion.x();
		if (x.plungeFrame() >= 0 && !p.onGround() && combat.prevFrame() < x.plungeFrame() && combat.frame() >= x.plungeFrame()) {
			Vec3 d = p.getDeltaMovement();
			p.setDeltaMovement(d.x, -x.plungeSpeed() / FixedTick.HZ, d.z);
		}
		face(p, facing);
		applyVelocity(p);
		return Vec3.ZERO;
	}

	private static void face(LocalPlayer p, float yaw) {
		p.setYRot(yaw);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
		p.setXRot(0.0F);
		p.setSprinting(false);
	}

	/** 지금 이동 입력의 월드 방향 [yaw] (입력 없으면 null) — 공격 시작 선회 · 회피 방향 */
	public static float[] inputDirection(LocalPlayer p) {
		Vec2 mv = p.input.getMoveVector();
		if (mv.lengthSquared() <= 1.0E-4F) {
			return null;
		}
		double camRad = Math.toRadians(ShoulderCamera.yaw());
		double dx = -Math.sin(camRad) * mv.y + Math.cos(camRad) * mv.x;
		double dz = Math.cos(camRad) * mv.y + Math.sin(camRad) * mv.x;
		return new float[] {(float) Math.toDegrees(Math.atan2(-dx, dz))};
	}

	private static void endTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}
		boolean sprintHeld = kr.huntcraft.client.input.ComboResolver.sprintHeld() && mc.gui.screen() == null;
		boolean movingNow = controls(p) && p.input.getMoveVector().lengthSquared() > 1.0E-4F && ClientHunters.local().current() == null;
		HunterRules.tick(state, sprintHeld, movingNow);
		if (!intentSent || sprintHeld != lastSentSprint || movingNow != lastSentMoving) {
			if (ClientPlayNetworking.canSend(IntentPayload.TYPE)) {
				ClientPlayNetworking.send(new IntentPayload(sprintHeld, movingNow));
				intentSent = true;
				lastSentSprint = sprintHeld;
				lastSentMoving = movingNow;
			}
		}
	}


	/** 발도 상태는 모션 상태 머신이 정하고 모션 동기화(CombatSyncPayload)로 맞춤 — 여기서는 스태미나만 */
	private static void onSync(HunterSyncPayload msg) {
		state.exhausted = msg.exhausted();
		state.health = msg.health();
		// 고른 아이템 번호는 클라이언트 것 그대로 (서버는 내가 보낸 값을 씀)
		state.itemCounts = msg.itemCounts();
		// 예측이 서버보다 조금 앞서 있는 건 정상 — 크게 어긋났을 때만 맞춤 (톱니 모양 흔들림 방지)
		if (Math.abs(state.stamina - msg.stamina()) > 2.0) {
			state.stamina = msg.stamina();
			state.regenDelayTicks = msg.regenDelayTicks();
		}
	}

	private static void reset() {
		state = new HunterState(CommonConfig.get().stamina.max);
		velX = velZ = 0.0;
		intentSent = false;
		rotationLocked = false;
		ShoulderCamera.reset();
	}

	private static float approach(float from, float to, float maxStep) {
		float delta = Mth.wrapDegrees(to - from);
		if (Math.abs(delta) <= maxStep) {
			return to;
		}
		return from + Math.signum(delta) * maxStep;
	}
}
