package kr.huntcraft.entity;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Geometry;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.fx.Effects;
import kr.huntcraft.hunter.HunterServer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 보스가 던지는 잔해 덩어리 — 포물선 (중력 gravity 블록/초²), 한 스텝 이동 구간을 캡슐로 쓸어 판정 (빠르게 날아와도 관통 없음).
 *   헌터: 피격 도형(HunterBody)과 겹치면 맞음 · 구르기 무적이면 그대로 지나감
 *   블록: 닿으면 부서짐 (먼지 · 소리)
 * 저장하지 않음 (던질 때만 존재).
 */
public class RubbleEntity extends Entity {
	private static final EntityDataAccessor<BlockState> BLOCK = SynchedEntityData.defineId(RubbleEntity.class, EntityDataSerializers.BLOCK_STATE);
	private static final EntityDataAccessor<Float> SCALE = SynchedEntityData.defineId(RubbleEntity.class, EntityDataSerializers.FLOAT);
	private static final int MAX_AGE = 60 * 8;

	/** 블록/틱 */
	private Vec3 velocity = Vec3.ZERO;
	private double gravityPerTick2;
	private double radius = 0.8;
	private double damage = 30.0;
	private String reaction = "knockback";
	private int ownerId = -1;
	private int age;
	/** 아직 손에 들려 있음 (던지기 전 — 움직임 · 판정 없음, 보스가 자리를 옮겨 줌) */
	private boolean gripped;
	/** 시험: 맞힌 헌터 수 · 피한 수 */
	private static int hits;
	private static int passed;

	public RubbleEntity(EntityType<? extends RubbleEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	public static int hits() {
		return hits;
	}

	public static int passed() {
		return passed;
	}

	/** 손에 든 채로 만들기 — launch 로 던질 때까지 판정 없음 */
	public void grip(Vec3 at, BlockState block, double scale) {
		setPos(at);
		this.gripped = true;
		this.entityData.set(BLOCK, block);
		this.entityData.set(SCALE, (float) scale);
	}

	/** 손에 든 자리 옮기기 (보스가 매 틱) */
	public void follow(Vec3 at) {
		setPos(at);
	}

	/** 초당 속도 (블록/초) · 중력 (블록/초²) */
	public void launch(Vec3 from, Vec3 velocityPerSec, double gravity, BlockState block, double scale, double radius, double damage, String reaction,
					   int ownerId) {
		setPos(from);
		this.velocity = velocityPerSec.scale(FixedTick.DT);
		this.gravityPerTick2 = gravity * FixedTick.DT * FixedTick.DT;
		this.entityData.set(BLOCK, block);
		this.entityData.set(SCALE, (float) scale);
		this.radius = radius;
		this.damage = damage;
		this.reaction = reaction;
		this.ownerId = ownerId;
		this.gripped = false;
	}

	public BlockState block() {
		return this.entityData.get(BLOCK);
	}

	public float scale() {
		return this.entityData.get(SCALE);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(BLOCK, Blocks.COBBLESTONE.defaultBlockState());
		builder.define(SCALE, 1.5F);
	}

	@Override
	public void tick() {
		super.tick();
		if (!(level() instanceof ServerLevel level)) {
			return;
		}
		if (++age > MAX_AGE) {
			discard();
			return;
		}
		if (gripped) {
			return;
		}
		Vec3 from = position();
		velocity = velocity.add(0.0, -gravityPerTick2, 0.0);
		Vec3 to = from.add(velocity);
		// 헌터: 이번 스텝 이동 구간 캡슐 vs 헌터 피격 도형
		Shape.Capsule sweep = new Shape.Capsule(from, to, radius);
		for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, new AABB(from, to).inflate(radius + 2.0))) {
			HunterBody body = HunterServer.body(p);
			if (body == null || !body.colliders().ready() || !touches(sweep, body)) {
				continue;
			}
			if (!body.hurtboxesActive(body.animTime(0.0F))) {
				passed++;
				continue; // 구르기 무적 — 지나감
			}
			hits++;
			HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(damage, reaction, from));
			burst(level, to);
			return;
		}
		// 블록
		HitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
		if (hit.getType() != HitResult.Type.MISS) {
			burst(level, hit.getLocation());
			return;
		}
		setPos(to);
	}

	private static boolean touches(Shape.Capsule sweep, HunterBody body) {
		ColliderDef def = body.colliders().def();
		Transform[] bones = body.colliders().cur();
		for (ColliderDef.Box u : def.hurtboxes) {
			if (Geometry.test(sweep, u.shape().toWorld(bones[u.bone()])) != null) {
				return true;
			}
		}
		return false;
	}

	private void burst(ServerLevel level, Vec3 at) {
		Effects.play(level, "rubble_break", at);
		discard();
	}

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
	}
}
