package kr.huntcraft.entity;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Pose;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 시험 막대 — 공격 판정(hitbox) 캡슐이 축을 중심으로 아주 빠르게 수평 회전 (기본 초당 2160° = 한 스텝 36°).
 * 칼끝이 한 스텝에 약 2블록을 가서, 스윕이 없으면 얇은 표적을 건너뛰는(터널링) 조건을 일부러 만듭니다.
 *
 *   활성 구간: 한 바퀴 중 0° ~ activeArc° (나머지는 비활성, 회색)
 *   공격 인스턴스: 바퀴마다 새 번호 → 같은 부위를 바퀴마다 다시 맞을 수 있음
 *   sweep=false: 스윕을 끈 비교용 (스텝 끝 자세만 검사)
 */
public class SwingRodEntity extends ColliderTestEntity {
	private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(SwingRodEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> SWEEP = SynchedEntityData.defineId(SwingRodEntity.class, EntityDataSerializers.BOOLEAN);
	private static final double ACTIVE_ARC = 300.0;

	public SwingRodEntity(EntityType<? extends SwingRodEntity> type, Level level) {
		super(type, level, "swing_rod");
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(SPEED, 2160.0F);
		builder.define(SWEEP, true);
	}

	public void configure(float degreesPerSecond, boolean sweep) {
		this.entityData.set(SPEED, degreesPerSecond);
		this.entityData.set(SWEEP, sweep);
	}

	public float speed() {
		return this.entityData.get(SPEED);
	}

	@Override
	public boolean sweepEnabled() {
		return this.entityData.get(SWEEP) && super.sweepEnabled();
	}

	/** 누적 회전각 (도) */
	public double angle(double time) {
		return speed() * time / 60.0;
	}

	@Override
	public void samplePose(double time, Pose out) {
		ColliderDef def = colliderDef();
		if (def == null) {
			return;
		}
		out.rotate(def.skeleton.indexOf("arm"), 0.0, angle(time) % 360.0, 0.0);
	}

	@Override
	public boolean hitboxActive(String hitboxId, double time) {
		return Math.floorMod((long) Math.floor(angle(time) * 1000.0), 360_000L) < ACTIVE_ARC * 1000.0;
	}

	@Override
	public long attackInstance(double time) {
		return hitboxActive("blade", time) ? (long) Math.floor(angle(time) / 360.0) + 1L : 0L;
	}
}
