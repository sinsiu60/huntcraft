package kr.huntcraft.entity;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.ColliderOwner;
import kr.huntcraft.collision.Defs;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * 판정 시험용 엔티티 공통 — 움직이지 않고 저장되지 않음. 바닐라 피해는 받지 않음 (판정은 HitSystem 만).
 * 애니메이션 시간 = 월드 게임 시간 - 생성 시각 (생성 시각을 동기화해서 클라이언트도 같은 시간을 계산).
 */
public abstract class ColliderTestEntity extends Entity implements ColliderOwner {
	private static final EntityDataAccessor<Long> SPAWN_TIME = SynchedEntityData.defineId(ColliderTestEntity.class, EntityDataSerializers.LONG);

	private final ColliderInstance colliders = new ColliderInstance(this);
	private final String colliderId;

	protected ColliderTestEntity(EntityType<?> type, Level level, String colliderId) {
		super(type, level);
		this.colliderId = colliderId;
		this.noPhysics = true;
		this.setNoGravity(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(SPAWN_TIME, 0L);
	}

	public void startClock() {
		this.entityData.set(SPAWN_TIME, level().getGameTime());
	}

	@Override
	public Entity self() {
		return this;
	}

	@Override
	public ColliderDef colliderDef() {
		return Defs.collider(colliderId);
	}

	@Override
	public ColliderInstance colliders() {
		return colliders;
	}

	@Override
	public double animTime(float partial) {
		return level().getGameTime() - this.entityData.get(SPAWN_TIME) + partial;
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean isPickable() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
