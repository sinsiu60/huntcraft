package kr.huntcraft.client.camera;

import java.util.ArrayList;
import java.util.List;

import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.input.ComboResolver;
import kr.huntcraft.combat.CombatInput;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 락온 (타겟 카메라) — 락온 키 (Q · 휠 클릭) 로 켜고 다시 누르면 끔.
 *   고르기: camera.lockOnRange 안의 살아 있는 몬스터 락온 지점 중 화면 가운데에 가장 가까운 것
 *   지점: 몬스터 정의의 lockOn [{bone, at}] (머리 · 몸통 …) — 없으면 몸 가운데 하나
 *   카메라: 좌우는 지점 쪽, 위아래는 헌터와 지점 사이 (둘 다 화면에 들어오게) — 회전 속도 한계 · 세로 각도 한계 (보스가 머리 위에 와도 뒤집히지 않음)
 *           보스가 가까우면 카메라가 조금 뒤 · 위로 빠짐
 *   전환: 락온 중 마우스를 한쪽으로 휙 움직이면 그쪽에 있는 다른 지점 · 대상으로 (camera.lockOnSwitchThreshold)
 *   풀림: 다시 누름 · 대상이 죽음/사라짐 · lockOnKeepRange 밖 · lockOnLoseSightSeconds 동안 안 보임
 */
public final class LockOn {
	private static int targetId = -1;
	private static int pointIndex;
	/** 전환용 마우스 누적 (도) · 전환 뒤 쉬는 틱 · 가려진 틱 */
	private static double accX;
	private static double accY;
	private static int switchCooldown;
	private static int hiddenTicks;
	/** 가까울 때 카메라가 빠지는 양 (부드럽게) */
	private static double extraDistance;
	private static double extraHeight;
	/** 시험: 전환 횟수 */
	private static int switches;

	private LockOn() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> release());
		ComboResolver.listen(in -> {
			if (in == CombatInput.LOCK_ON_TOGGLE) {
				toggle(Minecraft.getInstance());
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(LockOn::tick);
	}

	/** 지금 락온한 몬스터 (없으면 null) */
	public static MonsterEntity target() {
		Minecraft mc = Minecraft.getInstance();
		if (targetId < 0 || mc.level == null) {
			return null;
		}
		return mc.level.getEntity(targetId) instanceof MonsterEntity m ? m : null;
	}

	public static boolean locked() {
		return target() != null;
	}

	public static int pointIndex() {
		return pointIndex;
	}

	public static int switches() {
		return switches;
	}

	public static void release() {
		targetId = -1;
		pointIndex = 0;
		accX = accY = 0.0;
		hiddenTicks = 0;
	}

	/** 몬스터의 락온 지점 수 */
	public static int points(MonsterEntity m) {
		MonsterDef d = m.def();
		return d == null || d.lockOn.isEmpty() ? 1 : d.lockOn.size();
	}

	/** 락온 지점 월드 자리 (렌더 보간) */
	public static Vec3 point(MonsterEntity m, int index, float partial) {
		MonsterDef d = m.def();
		if (d == null || d.lockOn.isEmpty()) {
			return m.getPosition(partial).add(0.0, m.getBbHeight() * ClientConfig.get().hud.lockOnHeight, 0.0);
		}
		MonsterDef.LockPoint p = d.lockOn.get(Math.floorMod(index, d.lockOn.size()));
		return m.bonePoint(p.bone(), p.at(), partial);
	}

	/** 지금 락온 지점 (없으면 null) */
	public static Vec3 point(float partial) {
		MonsterEntity m = target();
		return m == null ? null : point(m, pointIndex, partial);
	}

	/** HUD 표시 */
	public static Vec3 center(MonsterEntity m) {
		return point(m, m.getId() == targetId ? pointIndex : 0, 1.0F);
	}

	public static void toggle(Minecraft mc) {
		if (targetId >= 0) {
			release();
			return;
		}
		if (mc.player == null || mc.level == null) {
			return;
		}
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		Candidate best = null;
		for (Candidate c : candidates(mc, cfg.lockOnRange)) {
			// 화면 가운데(카메라 각도)와 차이 + 거리 조금
			double score = Math.hypot(c.dyaw, c.dpitch) + c.distance * 0.5;
			if (best == null || score < best.score) {
				best = new Candidate(c.monster, c.index, c.dyaw, c.dpitch, c.distance, score);
			}
		}
		if (best != null) {
			targetId = best.monster.getId();
			pointIndex = best.index;
			accX = accY = 0.0;
			hiddenTicks = 0;
		}
	}

	/** 락온 후보 (몬스터 · 지점) — 카메라 각도 기준 좌우 · 위아래 차이 (도) */
	private record Candidate(MonsterEntity monster, int index, double dyaw, double dpitch, double distance, double score) {}

	private static List<Candidate> candidates(Minecraft mc, double range) {
		List<Candidate> out = new ArrayList<>();
		Vec3 eye = mc.player.getEyePosition();
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof MonsterEntity m) || m.dead() || m.distanceTo(mc.player) > range) {
				continue;
			}
			for (int i = 0; i < points(m); i++) {
				double[] a = angles(eye, point(m, i, 1.0F));
				out.add(new Candidate(m, i, Mth.wrapDegrees(a[0] - ShoulderCamera.yaw()), a[1] - ShoulderCamera.pitch(), m.distanceTo(mc.player), 0));
			}
		}
		return out;
	}

	/** from → to 의 [yaw, pitch] (도, pitch + = 아래) */
	private static double[] angles(Vec3 from, Vec3 to) {
		Vec3 d = to.subtract(from);
		double yaw = Math.toDegrees(Math.atan2(-d.x, d.z));
		double pitch = -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)));
		return new double[] {yaw, pitch};
	}

	/**
	 * 락온 중 마우스 (ShoulderCamera.turn 이 넘김, 도 단위) — 카메라는 돌리지 않고 모아서 한쪽으로 휙 움직이면 그쪽 후보로 전환.
	 * 천천히 흘린 움직임은 틱마다 줄어들어 전환되지 않음.
	 */
	public static void mouse(double dYaw, double dPitch) {
		accX += dYaw;
		accY += dPitch;
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		if (switchCooldown > 0 || Math.hypot(accX, accY) < cfg.lockOnSwitchThreshold) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		MonsterEntity cur = target();
		if (cur == null || mc.player == null) {
			return;
		}
		double[] curA = angles(mc.player.getEyePosition(), point(cur, pointIndex, 1.0F));
		double dirLen = Math.hypot(accX, accY);
		double dx = accX / dirLen;
		double dy = accY / dirLen;
		Candidate best = null;
		for (Candidate c : candidates(mc, cfg.lockOnRange)) {
			if (c.monster == cur && c.index == pointIndex) {
				continue;
			}
			// 지금 지점 기준 그 후보가 있는 쪽 (화면 좌우 · 위아래, 도)
			double ox = Mth.wrapDegrees(c.dyaw + ShoulderCamera.yaw() - curA[0]);
			double oy = c.dpitch + ShoulderCamera.pitch() - curA[1];
			double len = Math.hypot(ox, oy);
			if (len < 1.0E-3) {
				continue;
			}
			double cos = (ox * dx + oy * dy) / len;
			if (cos < Math.cos(Math.toRadians(cfg.lockOnSwitchCone))) {
				continue; // 마우스를 움직인 쪽이 아님
			}
			if (best == null || len < best.score) {
				best = new Candidate(c.monster, c.index, ox, oy, c.distance, len);
			}
		}
		accX = accY = 0.0;
		switchCooldown = FixedTick.ticks(cfg.lockOnSwitchCooldown);
		if (best != null) {
			targetId = best.monster.getId();
			pointIndex = best.index;
			hiddenTicks = 0;
			switches++;
		}
	}

	private static void tick(Minecraft mc) {
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		if (switchCooldown > 0) {
			switchCooldown--;
		}
		accX *= cfg.lockOnSwitchDecay;
		accY *= cfg.lockOnSwitchDecay;
		MonsterEntity m = target();
		LocalPlayer player = mc.player;
		if (targetId >= 0 && (m == null || m.dead() || player == null || m.distanceTo(player) > cfg.lockOnKeepRange)) {
			release();
			return;
		}
		if (m == null) {
			return;
		}
		Vec3 p = point(m, pointIndex, 1.0F);
		Vec3 eye = player.getEyePosition();
		// 가려짐: 한동안 안 보이면 풀림
		HitResult hit = mc.level.clip(new ClipContext(eye, p, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
		hiddenTicks = hit.getType() == HitResult.Type.MISS ? 0 : hiddenTicks + 1;
		if (hiddenTicks > FixedTick.ticks(cfg.lockOnLoseSightSeconds)) {
			release();
			return;
		}
		// 좌우: 지점 쪽 · 위아래: 헌터와 지점 사이 (둘 다 담기) — 회전 속도 한계 · 세로 한계
		double[] a = angles(eye, p);
		Vec3 frame = eye.lerp(p, cfg.lockOnFrameBias);
		double[] f = angles(eye.add(0.0, cfg.heightOffset, 0.0), frame);
		float wantPitch = (float) Mth.clamp(f[1] + cfg.lockOnPitchOffset, cfg.lockOnPitchMin, cfg.lockOnPitchMax);
		float yawStep = (float) (cfg.lockOnTurnSpeed * FixedTick.DT);
		float pitchStep = (float) (cfg.lockOnPitchSpeed * FixedTick.DT);
		float dy = Mth.wrapDegrees((float) a[0] - ShoulderCamera.yaw());
		float dp = wantPitch - ShoulderCamera.pitch();
		ShoulderCamera.setAngles(ShoulderCamera.yaw() + Mth.clamp(dy, -yawStep, yawStep), ShoulderCamera.pitch() + Mth.clamp(dp, -pitchStep, pitchStep));
	}

	/** 가까울수록 카메라가 뒤 · 위로 빠지는 양 [뒤, 위] (블록) — ShoulderCamera 가 매 렌더 프레임 부름 */
	public static double[] closeOffset(LocalPlayer player, double dt) {
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		MonsterEntity m = target();
		double want = 0.0;
		if (m != null && player != null) {
			double gap = Math.hypot(m.getX() - player.getX(), m.getZ() - player.getZ()) - m.getBbWidth() * 0.5;
			want = Mth.clamp(1.0 - gap / cfg.lockOnCloseRange, 0.0, 1.0);
		}
		double k = 1.0 - Math.exp(-dt * cfg.lockOnCloseSmooth);
		extraDistance += (want * cfg.lockOnCloseDistance - extraDistance) * k;
		extraHeight += (want * cfg.lockOnCloseHeight - extraHeight) * k;
		return new double[] {extraDistance, extraHeight};
	}

	/** 헌터 몸이 락온 대상을 볼 yaw (락온이 없으면 NaN) */
	public static float bodyYaw(LocalPlayer player) {
		Vec3 p = point(1.0F);
		if (p == null || player == null) {
			return Float.NaN;
		}
		return (float) Math.toDegrees(Math.atan2(-(p.x - player.getX()), p.z - player.getZ()));
	}
}
