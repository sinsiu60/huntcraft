package kr.huntcraft.entity;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Skeleton;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

/**
 * 훈련용 더미 — 피격 판정(hurtbox)만 있음. 본이 천천히 움직여서 도형이 본을 따라가는 걸 눈으로 확인할 수 있게 함
 * (몸통 좌우 비틀기 · 고개 끄덕임 · 팔 흔들기). animate=false 면 기본 자세로 멈춤.
 */
public class TrainingDummyEntity extends ColliderTestEntity {
	private static final EntityDataAccessor<Boolean> ANIMATE = SynchedEntityData.defineId(TrainingDummyEntity.class, EntityDataSerializers.BOOLEAN);

	public TrainingDummyEntity(EntityType<? extends TrainingDummyEntity> type, Level level) {
		super(type, level, "training_dummy");
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(ANIMATE, true);
	}

	public void setAnimate(boolean animate) {
		this.entityData.set(ANIMATE, animate);
	}

	@Override
	public void samplePose(double time, Pose out) {
		ColliderDef def = colliderDef();
		if (def == null || !this.entityData.get(ANIMATE)) {
			return;
		}
		Skeleton sk = def.skeleton;
		double s = time / 60.0;
		double twist = 35.0 * Math.sin(2.0 * Math.PI * 0.25 * s);
		out.rotate(sk.indexOf("body"), 8.0 * Math.sin(2.0 * Math.PI * 0.5 * s), twist, 0.0);
		out.rotate(sk.indexOf("head"), 20.0 * Math.sin(2.0 * Math.PI * 0.6 * s), 0.0, 0.0);
		double swing = 40.0 * Math.sin(2.0 * Math.PI * 0.8 * s);
		out.rotate(sk.indexOf("arm_left"), swing, 0.0, -15.0);
		out.rotate(sk.indexOf("arm_right"), -swing, 0.0, 15.0);
	}
}
