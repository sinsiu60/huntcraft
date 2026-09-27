package kr.huntcraft.monster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import kr.huntcraft.anim.Animation;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.ColliderOwner;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.hunter.HunterServer;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 대형 몬스터 — 행동 · 판정 · 이동을 전부 몬스터 정의(defs/monsters)로. 바닐라 AI · 경로 탐색은 쓰지 않음.
 *
 *   행동: 한 번에 하나 (애니메이션 · 판정 구간 · 루트모션). 끝나면 거리 · 각도 조건과 가중치로 다음 행동을 고름
 *   이동: 루트모션(행동 데이터의 누적 이동량) · 추격 속도로 매 틱 속도를 정하고, 지형 충돌 · 중력은 바닐라
 *   회전: 행동의 track 구간에만 목표 쪽으로 (예비 동작), spin 구간은 정해진 만큼
 *   동기화: 행동 id · 시작 시각 · 재생 속도 → 클라이언트가 같은 시간으로 자세를 계산 (렌더 · 디버그 판정)
 *   피해: 헌터 칼날 명중 → 체력 · 부위 누적 → 임계치를 넘으면 경직 / 넘어짐. 체력 비율이 rage.healthBelow 이하면 분노 (포효 + 빨라짐)
 */
public class MonsterEntity extends Mob implements ColliderOwner, GeoEntity {
	private static final EntityDataAccessor<String> DEF = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<String> ACTION = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.STRING);
	private static final EntityDataAccessor<Long> START = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.LONG);
	private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Boolean> ENRAGED = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Boolean> DEAD = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.BOOLEAN);
	/** 몬스터 체력 — 바닐라 최대 체력 속성은 1024 까지라 따로 둠 (UI 에 쓰려고 동기화) */
	private static final EntityDataAccessor<Float> HP = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> MAX_HP = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	/** 전원 (꺼지면 눈 불이 꺼진 텍스처) · 소멸 진행 (0 → 1, 처치 연출 뒤 먼지가 되어 사라짐) */
	private static final EntityDataAccessor<Boolean> POWERED = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.BOOLEAN);
	private static final EntityDataAccessor<Float> DISSOLVE = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	/** 강인도 · 자세 (디버그 화면 표시용 동기화) */
	private static final EntityDataAccessor<Float> POISE = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	private static final EntityDataAccessor<Float> STANCE = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	/** 근접 압박 (디버그 표시용으로 동기화) */
	private static final EntityDataAccessor<Float> PRESSURE = SynchedEntityData.defineId(MonsterEntity.class, EntityDataSerializers.FLOAT);
	private long pushCooldownUntil;
	private long lastPushTick = Long.MIN_VALUE;
	/** 죽은 뒤 사체가 남는 시간 (틱) */
	private static final int CARCASS_TICKS = 60 * 60;

	private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
	private final ColliderInstance colliders = new ColliderInstance(this);

	// ── 서버 전용 상태 ──
	private long serial;
	private double prevFrame = -1.0;
	private float facing;
	private UUID target;
	private boolean aware;
	private boolean lastWasAttack;
	/** 이번 행동에서 잡기가 닿았는지 · 손에 쥔 헌터 · root 앞 이동 배율 (목표 거리 맞춤) · 최근 공격 (같은 공격 3번 연속 금지) */
	private boolean grabbedThis;
	private UUID heldVictim;
	private double rootScale = 1.0;
	private final java.util.ArrayDeque<String> recentAttacks = new java.util.ArrayDeque<>();
	/** 손에 들고 있는 잔해 (던지기 전) */
	private kr.huntcraft.entity.RubbleEntity heldRubble;
	private ActionFx.Projectile heldRubbleSpec;
	/** 이번 숨 고르기 길이 (프레임) — 매번 무작위 */
	private double pauseFrames;
	private int deadTicks;
	private final Map<String, Long> cooldownUntil = new HashMap<>();
	/** 마지막으로 강인도 · 자세 대미지를 받은 게임 틱 (회복 대기) · 강인도가 깨진 횟수 (시험) */
	private long lastPoiseHit = Long.MIN_VALUE / 2;
	private long lastStanceHit = Long.MIN_VALUE / 2;
	private int poiseBreaks;
	private final Set<UUID> roared = new HashSet<>();
	/** 행동 선택 켜기/끄기 (튜닝 · 시험 — 끄면 강제한 행동만 하고 대기로 돌아감) */
	private boolean ai = true;

	/** 미리보기 (클라이언트 시험 · 몬스터 갤러리) — 행동 · 프레임 고정 */
	private String previewAction;
	private double previewFrame;

	public MonsterEntity(EntityType<? extends MonsterEntity> type, Level level) {
		super(type, level);
		this.setPersistenceRequired();
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(DEF, "");
		builder.define(ACTION, "");
		builder.define(START, 0L);
		builder.define(SPEED, 1.0F);
		builder.define(ENRAGED, false);
		builder.define(DEAD, false);
		builder.define(HP, 1.0F);
		builder.define(MAX_HP, 1.0F);
		builder.define(POISE, -1.0F);
		builder.define(POWERED, true);
		builder.define(DISSOLVE, 0.0F);
		builder.define(STANCE, 0.0F);
		builder.define(PRESSURE, 0.0F);
	}

	// ── 정의 · 상태 ──────────────────────────────────────────

	public MonsterDef def() {
		return Defs.monster(this.entityData.get(DEF));
	}

	public void setDef(String id) {
		this.entityData.set(DEF, id);
		refreshDimensions();
		MonsterDef d = def();
		if (d != null) {
			this.entityData.set(MAX_HP, (float) d.health);
			this.entityData.set(HP, (float) d.health);
		}
	}

	public float hp() {
		return this.entityData.get(HP);
	}

	public float maxHp() {
		return this.entityData.get(MAX_HP);
	}

	/** 체력 지정 (명령 · 시험) */
	public void setHp(float value) {
		this.entityData.set(HP, Math.max(0.0F, Math.min(maxHp(), value)));
	}

	public MonsterDef.Action action() {
		MonsterDef d = def();
		if (d == null) {
			return null;
		}
		return d.actions.get(previewAction != null ? previewAction : this.entityData.get(ACTION));
	}

	/** 미리보기 지정 (null = 해제) */
	public void setPreview(String action, double frame) {
		this.previewAction = action;
		this.previewFrame = frame;
	}

	public String actionId() {
		return this.entityData.get(ACTION);
	}

	public boolean enraged() {
		return this.entityData.get(ENRAGED);
	}

	public boolean dead() {
		return this.entityData.get(DEAD);
	}

	public float speedScale() {
		return this.entityData.get(SPEED);
	}

	/**
	 * 보스전 초기화 (헌터가 죽었을 때 · 안개 문으로 들어올 때) — 체력 · 자세 · 강인도 가득, 대기 행동, 자리 · 방향, 쥔 것 놓기.
	 * ai = 행동 선택 (들어오기 전에는 꺼 둠 — 안개 너머에서 공격하지 않게)
	 */
	public void resetForFight(net.minecraft.world.phys.Vec3 at, float yaw, boolean ai) {
		MonsterDef d = def();
		if (heldVictim != null) {
			releaseVictim(null);
		}
		dropRubble();
		this.entityData.set(DEAD, false);
		this.entityData.set(HP, maxHp());
		this.entityData.set(POWERED, true);
		this.entityData.set(DISSOLVE, 0.0F);
		this.entityData.set(ENRAGED, false);
		this.entityData.set(SPEED, 1.0F);
		this.entityData.set(STANCE, 0.0F);
		this.entityData.set(POISE, d == null ? -1.0F : (float) d.poiseMax);
		deadTicks = 0;
		target = null;
		aware = false;
		recentAttacks.clear();
		cooldownUntil.clear();
		teleportTo(at.x, at.y, at.z);
		setYRot(yaw);
		setYHeadRot(yaw);
		setYBodyRot(yaw);
		facing = yaw;
		setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		setAi(ai);
		if (d != null) {
			startAction(d.idleAction);
		}
	}

	public boolean powered() {
		return this.entityData.get(POWERED);
	}

	public void setPowered(boolean on) {
		this.entityData.set(POWERED, on);
	}

	/** 소멸 진행 0 ~ 1 (렌더 투명도) */
	public float dissolve() {
		return this.entityData.get(DISSOLVE);
	}

	/** 지금 치명 일격을 받을 수 있는지 (행동의 critOpen 구간 — 서버 · 클라이언트 똑같이 계산) */
	public boolean critOpen() {
		MonsterDef.Action a = action();
		int[] w = a == null ? null : a.flow().critOpen();
		double f = frame(0.0F);
		return w != null && !dead() && f >= w[0] && f <= w[1];
	}

	@Override
	public boolean attackParryable(double time) {
		MonsterDef.Hit h = hitAt(time);
		return h != null && h.parryable();
	}

	/** 패링당함 — 크게 휘청 (치명 구간) + 자세 대미지 */
	public void onParried() {
		MonsterDef d = def();
		if (d == null || dead()) {
			return;
		}
		lastStanceHit = level().getGameTime();
		this.entityData.set(STANCE, (float) Math.min(d.stanceMax, stance() + d.parryStance));
		if (d.parried != null) {
			startAction(d.parried);
		}
	}

	/** 치명 일격을 맞음 (시작할 때 불러서 riposted 행동의 기다림 부분이 칼이 박히는 프레임까지 이어짐) · damage 는 impact 때 */
	public void onRiposteStart() {
		MonsterDef d = def();
		MonsterDef.Action cur = action();
		String id = cur != null && cur.flow().riposted() != null && d != null && d.actions.containsKey(cur.flow().riposted())
				? cur.flow().riposted() : d == null ? null : d.riposted;
		if (id != null && !dead()) {
			startAction(id);
			this.entityData.set(STANCE, 0.0F);
		}
	}

	public void onRiposteImpact(double damage) {
		MonsterDef d = def();
		if (d == null || dead()) {
			return;
		}
		float hp = (float) (hp() - damage);
		if (hp <= 0.0F) {
			this.entityData.set(HP, 0.0F);
			this.entityData.set(DEAD, true);
			if (d.death != null) {
				startAction(d.death);
			}
			if (level() instanceof ServerLevel sl) {
				kr.huntcraft.hunter.HunterServer.slain(sl, this, net.minecraft.network.chat.Component.literal(d.displayName));
			}
			return;
		}
		this.entityData.set(HP, hp);
	}

	/** 남은 강인도 (-1 = 아직 정의를 못 읽음) · 쌓인 자세 대미지 */
	public float poise() {
		return this.entityData.get(POISE);
	}

	/** 근접 압박 (0 ~ pressure.max) */
	public float pressure() {
		return this.entityData.get(PRESSURE);
	}

	/** 시험 · 명령: 근접 압박 값 */
	public void setPressure(float v) {
		this.entityData.set(PRESSURE, v);
	}

	/** 마지막으로 밀어내기 패턴을 쓴 틱 (없으면 Long.MIN_VALUE) — 재배치 조건 */
	public long lastPushTick() {
		return lastPushTick;
	}

	public float stance() {
		return this.entityData.get(STANCE);
	}

	public int poiseBreaks() {
		return poiseBreaks;
	}

	public boolean ai() {
		return ai;
	}

	public void setAi(boolean on) {
		ai = on;
	}

	public Entity targetEntity() {
		return target == null || !(level() instanceof ServerLevel sl) ? null : sl.getEntity(target);
	}

	/** 행동 시작부터 지난 프레임 (재생 속도 반영). partial = 렌더 보간 */
	public double frame(float partial) {
		if (previewAction != null) {
			return previewFrame;
		}
		return (level().getGameTime() - this.entityData.get(START) + partial) * speedScale();
	}

	// ── 판정 주인 ────────────────────────────────────────────

	@Override
	public Entity self() {
		return this;
	}

	@Override
	public ColliderDef colliderDef() {
		MonsterDef d = def();
		return d == null ? null : Defs.collider(d.collider);
	}

	@Override
	public ColliderInstance colliders() {
		return colliders;
	}

	@Override
	public double animTime(float partial) {
		return frame(partial);
	}

	@Override
	public double animTimePrev() {
		return prevFrame >= 0.0 ? prevFrame : frame(0.0F) - speedScale();
	}

	@Override
	public void samplePose(double time, Pose out) {
		MonsterDef.Action a = action();
		ColliderDef def = colliderDef();
		if (a == null || def == null) {
			return;
		}
		Animation anim = Defs.animation(a.animation());
		if (anim == null) {
			return;
		}
		double t = time;
		if (a.loop() && a.length() > 0) {
			t = t % a.length();
		} else if (dead()) {
			t = Math.min(t, a.length() - 1);
		}
		anim.sample(t / 60.0, def.skeleton, out);
	}

	@Override
	public boolean hitboxActive(String hitboxId, double time) {
		MonsterDef.Action a = action();
		if (a == null || dead()) {
			return false;
		}
		int idx = a.hitIndex(time);
		return idx >= 0 && a.hits().get(idx).boxActive(hitboxId, time);
	}

	@Override
	public long attackInstance(double time) {
		MonsterDef.Action a = action();
		if (a == null || dead()) {
			return 0L;
		}
		int idx = a.hitIndex(time);
		return idx < 0 ? 0L : serial * 16L + idx + 1L;
	}

	/** time 에 hitboxId 로 닿은 공격 (보조 판정이면 그 대미지 · 반응으로) */
	public MonsterDef.Hit hitAt(double time, String hitboxId) {
		MonsterDef.Hit h = hitAt(time);
		return h == null ? null : h.resolved(hitboxId, time);
	}

	/** time 의 공격 구간 (대미지 계산용) */
	public MonsterDef.Hit hitAt(double time) {
		MonsterDef.Action a = action();
		if (a == null) {
			return null;
		}
		int idx = a.hitIndex(time);
		return idx < 0 ? null : a.hits().get(idx);
	}

	@Override
	public boolean hurtboxesActive(double time) {
		MonsterDef.Action a = action();
		return !dead() && (a == null || !a.flow().invuln());
	}

	/** 소멸 중 먼지: 몸 부피 안에서 재 · 흰 재 · 연기가 흩날림 (진행될수록 몸이 흐려지고 먼지는 늘었다 줄어듦) */
	private void dust(ServerLevel level, float t, kr.huntcraft.config.CommonConfig.Progress pc) {
		int n = (int) Math.ceil(pc.dustPerTick * Math.sin(Math.PI * Math.min(1.0, t * 1.2 + 0.1)));
		double w = getBbWidth() * 0.6;
		double h = getBbHeight() * 0.35;
		for (int i = 0; i < n; i++) {
			double x = getX() + (getRandom().nextDouble() - 0.5) * w * 2.0;
			double y = getY() + getRandom().nextDouble() * h;
			double z = getZ() + (getRandom().nextDouble() - 0.5) * w * 2.0;
			level.sendParticles(i % 3 == 0 ? net.minecraft.core.particles.ParticleTypes.WHITE_ASH : net.minecraft.core.particles.ParticleTypes.ASH,
					x, y, z, 2, 0.1, 0.1, 0.1, 0.01);
			if (i % 4 == 0) {
				level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, x, y, z, 1, 0.1, 0.2, 0.1, 0.01);
			}
		}
	}

	@Override
	public boolean canHit(ColliderOwner target) {
		return target instanceof HunterBody;
	}

	// ── 바닐라 동작 끄기 ─────────────────────────────────────

	@Override
	public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
		// 바닐라 피해 없음 — 판정은 HitSystem, /kill 만 통과
		if (source.is(net.minecraft.world.damagesource.DamageTypes.GENERIC_KILL)) {
			return super.hurtServer(level, source, amount);
		}
		return false;
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	protected void pushEntities() {
	}

	@Override
	public boolean removeWhenFarAway(double distance) {
		return false;
	}

	@Override
	protected void tickHeadTurn(float yBodyRotTarget) {
		// 몸 방향은 판정 루트와 같아야 함 — 바닐라 몸 늦게 따라오기 없음
		this.yBodyRot = this.getYRot();
	}

	/** 지형 충돌 상자 = 정의의 size (블록) */
	@Override
	protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
		MonsterDef d = def();
		return d == null ? super.getDefaultDimensions(pose) : net.minecraft.world.entity.EntityDimensions.scalable((float) d.width, (float) d.height);
	}

	@Override
	public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
		super.onSyncedDataUpdated(key);
		if (DEF.equals(key)) {
			refreshDimensions(); // 클라이언트도 정의를 받으면 크기 맞춤
		}
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		super.readAdditionalSaveData(input);
		setDef(input.getStringOr("HuntDef", kr.huntcraft.config.CommonConfig.get().boss.defaultId));
		this.entityData.set(HP, input.getFloatOr("HuntHp", maxHp()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		super.addAdditionalSaveData(output);
		output.putString("HuntDef", this.entityData.get(DEF));
		output.putFloat("HuntHp", hp());
	}

	// ── GeckoLib (애니메이션 컨트롤러 없음: 자세는 판정과 같은 계산을 렌더러가 직접 넣음) ──

	@Override
	public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
	}

	@Override
	public AnimatableInstanceCache getAnimatableInstanceCache() {
		return geoCache;
	}

	// ── 서버 두뇌 ────────────────────────────────────────────

	@Override
	public void tick() {
		if (!level().isClientSide() && level() instanceof ServerLevel sl) {
			brain(sl);
			regenPoise();
		}
		super.tick();
	}

	/** 한동안 안 맞으면 강인도 회복 · 자세 대미지 줄어듦 */
	private void regenPoise() {
		MonsterDef d = def();
		if (d == null) {
			return;
		}
		long now = level().getGameTime();
		float p = poise();
		if (p < 0.0F) {
			this.entityData.set(POISE, (float) d.poiseMax);
		} else if (p < d.poiseMax && now - lastPoiseHit > kr.huntcraft.core.FixedTick.ticks(d.poiseRegenDelay)) {
			this.entityData.set(POISE, (float) Math.min(d.poiseMax, p + d.poiseRegenPerSec * kr.huntcraft.core.FixedTick.DT));
		}
		float st = stance();
		if (st > 0.0F && now - lastStanceHit > kr.huntcraft.core.FixedTick.ticks(d.stanceRegenDelay)) {
			this.entityData.set(STANCE, (float) Math.max(0.0, st - d.stanceRegenPerSec * kr.huntcraft.core.FixedTick.DT));
		}
	}

	/** 행동 시작 (강제 — 경직 · 분노 · 명령) */
	public void startAction(String id) {
		MonsterDef d = def();
		if (d == null || !d.actions.containsKey(id)) {
			return;
		}
		serial++;
		this.entityData.set(ACTION, id);
		this.entityData.set(START, level().getGameTime());
		prevFrame = -1.0;
		roared.clear();
		MonsterDef.Action a = d.actions.get(id);
		if (a.cooldown() > 0.0) {
			cooldownUntil.put(id, level().getGameTime() + Math.round(a.cooldown() * 60.0));
		}
		lastWasAttack = !a.hits().isEmpty();
		// 밀어내기: 압박 초기화 + 쿨다운
		if (a.flow().push()) {
			this.entityData.set(PRESSURE, 0.0F);
			pushCooldownUntil = level().getGameTime() + Math.round(d.pressure.cooldown() * 60.0);
			lastPushTick = level().getGameTime();
		}
		grabbedThis = false;
		if (lastWasAttack) {
			recentAttacks.addLast(id);
			while (recentAttacks.size() > 2) {
				recentAttacks.removeFirst();
			}
		}
		// 들고 있던 잔해: 던지기 전에 행동이 끊기면 그 자리에서 부서짐
		dropRubble();
		// 쥐고 있던 헌터: 새 행동이 쥐는 행동이 아니면 놓음 (경직 등으로 끊김 — 대미지 없이)
		if (heldVictim != null && a.flow().hold() == null) {
			releaseVictim(null);
		}
		// 목표 거리에 맞춘 이동 배율 (뛰어올라 내려찍기)
		rootScale = 1.0;
		double[] fit = a.flow().rootToTarget();
		Entity tgt = targetEntity();
		if (fit != null && tgt != null && a.root().length > 0) {
			double total = a.rootOffset(a.length())[0] * scaleOf();
			if (total > 1.0E-3) {
				rootScale = Mth.clamp((horizontalDistance(tgt) - fit[2]) / total, fit[0], fit[1]);
			}
		}
	}

	private double scaleOf() {
		ColliderDef col = colliderDef();
		return col == null ? 1.0 : col.scale;
	}

	/** 잡기 판정이 헌터에게 닿음 (HunterServer 가 부름) — 쥐는 행동으로 넘어가고 그 헌터를 손에 붙임 */
	public void onGrabbed(ServerPlayer p) {
		MonsterDef.Action a = action();
		if (a == null || a.flow().onGrab() == null || heldVictim != null) {
			return;
		}
		startAction(a.flow().onGrab());
		grabbedThis = true;
		heldVictim = p.getUUID();
		HunterServer.setHeld(p, true, position());
		if (level() instanceof ServerLevel sl) {
			kr.huntcraft.fx.Effects.play(sl, "golem_grab", p.position());
		}
	}

	/** 쥐고 있는 헌터 (없으면 null) */
	public UUID heldVictim() {
		return heldVictim;
	}

	/** 놓기 — hold 가 있으면 그 대미지 · 반응 (던지기), 없으면 그냥 놓음 */
	private void releaseVictim(ActionFx.Hold hold) {
		if (heldVictim != null && level() instanceof ServerLevel sl && sl.getPlayerByUUID(heldVictim) instanceof ServerPlayer p) {
			HunterServer.setHeld(p, false, null);
			p.resetFallDistance();
			if (hold != null) {
				HunterServer.applyBossHit(p, new kr.huntcraft.combat.DamageCalc.MonsterHit(hold.damage(), hold.reaction(), position()));
			}
		}
		heldVictim = null;
	}

	private void brain(ServerLevel level) {
		MonsterDef d = def();
		if (d == null) {
			return;
		}
		if (action() == null) {
			facing = getYRot();
			startAction(d.idleAction);
		}
		if (dead()) {
			// 처치 연출 (death 행동 — 연기 · 불꽃은 그대로 나옴) → 잠깐 뒤 천천히 먼지가 되어 사라짐
			MonsterDef.Action da = action();
			double df = frame(0.0F);
			if (da != null) {
				fx(level, da, prevFrame, df);
			}
			prevFrame = df;
			deadTicks++;
			kr.huntcraft.config.CommonConfig.Progress pc = kr.huntcraft.config.CommonConfig.get().progress;
			int deathLen = da != null && d.death != null && d.death.equals(da.id()) ? da.length() : 0;
			int start = deathLen + kr.huntcraft.core.FixedTick.ticks(pc.dissolveDelaySeconds);
			int dur = Math.max(1, kr.huntcraft.core.FixedTick.ticks(pc.dissolveSeconds));
			if (deadTicks > start) {
				float t = Math.min(1.0F, (deadTicks - start) / (float) dur);
				this.entityData.set(DISSOLVE, t);
				dust(level, t, pc);
				if (t >= 1.0F) {
					discard();
				}
			} else if (deadTicks > CARCASS_TICKS) {
				discard();
			}
			setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
			return;
		}
		updateTarget(level, d);
		Entity tgt = targetEntity();
		updatePressure(d, tgt);
		MonsterDef.Action a = action();
		double frame = frame(0.0F);
		boolean finished = !a.loop() && frame >= a.length();
		if (a.id().equals(d.chase)) {
			if (tgt == null || horizontalDistance(tgt) <= a.untilRange() || frame >= a.length()) {
				finished = true;
			}
		}
		if (a.id().equals(d.idleAction) && tgt != null && ai) {
			finished = true;
		}
		if (a.id().equals(d.pause) && frame >= pauseFrames) {
			finished = true;
		}
		if (finished) {
			startAction(nextAfter(level, d, a, tgt));
			a = action();
			frame = 0.0;
		}
		move(d, a, tgt, frame);
		roar(level, d, a, frame);
		fx(level, a, prevFrame, frame);
		hold(level, a, frame);
		// 돌진이 벽에 막힘 → 부딪혀 경직 (공격 기회)
		ActionFx.Flow flow = a.flow();
		if (flow.wallStop() != null && horizontalCollision && (flow.wallWindow() == null
				|| (frame >= flow.wallWindow()[0] && frame <= flow.wallWindow()[1]))) {
			kr.huntcraft.fx.Effects.play(level, "golem_wall", position().add(0.0, getBbHeight() * 0.5, 0.0));
			startAction(flow.wallStop());
			frame = 0.0;
		}
		prevFrame = frame;
	}

	/** 근접 압박: 밀착이면 빠르게 · 근접이면 천천히 오름, 그 밖 (또는 목표 없음) 이면 내려감 */
	private void updatePressure(MonsterDef d, Entity tgt) {
		MonsterDef.Pressure pr = d.pressure;
		double dist = tgt == null ? Double.MAX_VALUE : zoneDistance(tgt);
		double rate = dist <= d.zones.inner() ? pr.innerRate() : dist <= d.zones.close() ? pr.closeRate() : -pr.decay();
		float v = (float) Mth.clamp(pressure() + rate * kr.huntcraft.core.FixedTick.DT, 0.0, pr.max());
		if (v != pressure()) {
			this.entityData.set(PRESSURE, v);
		}
	}

	private void updateTarget(ServerLevel level, MonsterDef d) {
		Entity cur = targetEntity();
		if (cur != null && (!cur.isAlive() || cur.isSpectator() || horizontalDistance(cur) > d.loseRange)) {
			target = null;
			cur = null;
		}
		if (cur == null) {
			ServerPlayer best = null;
			double bestD = d.detectRange;
			for (ServerPlayer p : level.players()) {
				if (p.isSpectator() || !p.isAlive() || HunterServer.body(p) == null) {
					continue;
				}
				double dist = horizontalDistance(p);
				if (dist < bestD && canSee(p)) {
					best = p;
					bestD = dist;
				}
			}
			if (best != null) {
				target = best.getUUID();
			}
		}
	}

	private boolean canSee(Entity e) {
		Vec3 eye = position().add(0.0, getBbHeight() * 0.8, 0.0);
		HitResult hit = level().clip(new ClipContext(eye, e.getEyePosition(), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, this));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** 행동이 끝났을 때 다음: 헛잡음 → missFollow · 연결 공격 확률 → followup · 아니면 보통 고르기 */
	private String nextAfter(ServerLevel level, MonsterDef d, MonsterDef.Action done, Entity tgt) {
		ActionFx.Flow flow = done.flow();
		if (flow.grabs() && !grabbedThis && flow.missFollow() != null && d.actions.containsKey(flow.missFollow())) {
			return flow.missFollow();
		}
		if (tgt != null && ai && !dead()) {
			for (ActionFx.Follow f : flow.followups()) {
				double chance = enraged() ? f.rageChance() : f.chance();
				if (d.actions.containsKey(f.action()) && getRandom().nextDouble() < chance) {
					return f.action();
				}
			}
		}
		return chooseNext(level, d, tgt);
	}

	/** 쥔 헌터를 손 자리에 붙이고, release 프레임에 던짐 */
	private void hold(ServerLevel level, MonsterDef.Action a, double frame) {
		ActionFx.Hold hold = a.flow().hold();
		if (heldVictim == null || hold == null) {
			return;
		}
		if (frame >= hold.release()) {
			releaseVictim(hold);
			return;
		}
		if (level.getPlayerByUUID(heldVictim) instanceof ServerPlayer p) {
			Vec3 at = bonePoint(hold.bone(), hold.at()).subtract(0.0, 0.9, 0.0);
			// 내리꽂는 순간 주먹이 땅에 닿음 → 손 자리가 땅속이 되지 않게 골렘 발 높이 아래로는 안 내림
			if (at.y < getY()) {
				at = new Vec3(at.x, getY(), at.z);
			}
			p.teleportTo(at.x, at.y, at.z);
			p.setDeltaMovement(Vec3.ZERO);
			// 들려 있는 동안 쌓인 바닐라 낙하 거리 = 놓일 때 낙사 → 매 틱 지움
			p.resetFallDistance();
		} else {
			heldVictim = null;
		}
	}

	/** 잔해 던지기: 그 순간 목표 자리로 포물선 (비행 시간 = 거리 / speed, flight 범위) — 예측 조준 없음 (옆으로 움직이면 피함) */
	private void throwProjectile(ServerLevel level, ActionFx.Projectile pr) {
		kr.huntcraft.entity.RubbleEntity r = heldRubble != null && heldRubbleSpec == pr && heldRubble.isAlive() ? heldRubble
				: kr.huntcraft.entity.HuntEntities.RUBBLE.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		boolean fresh = r != heldRubble;
		heldRubble = null;
		heldRubbleSpec = null;
		Entity tgt = targetEntity();
		Vec3 from = bonePoint(pr.bone(), pr.at());
		Vec3 aim = tgt != null ? tgt.position().add(0.0, 0.9, 0.0)
				: from.add(new Vec3(-Math.sin(Math.toRadians(facing)), 0.0, Math.cos(Math.toRadians(facing))).scale(12.0));
		Vec3 delta = aim.subtract(from);
		double horiz = Math.hypot(delta.x, delta.z);
		double t = Mth.clamp(horiz / pr.speed(), pr.flight()[0], pr.flight()[1]);
		Vec3 v = new Vec3(delta.x / t, (delta.y + 0.5 * pr.gravity() * t * t) / t, delta.z / t);
		if (r == null) {
			return;
		}
		r.launch(from, v, pr.gravity(), blockOf(pr), pr.scale(), pr.radius(), pr.damage(), pr.reaction(), getId());
		if (fresh) {
			level.addFreshEntity(r);
		}
	}

	private static net.minecraft.world.level.block.state.BlockState blockOf(ActionFx.Projectile pr) {
		try {
			var b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(net.minecraft.resources.Identifier.parse(pr.block()));
			if (b != null) {
				return b.defaultBlockState();
			}
		} catch (RuntimeException ignored) {
			// 잘못된 블록 id 면 조약돌
		}
		return net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState();
	}

	/** 땅에서 잔해를 뜯어 손에 듦 (던질 때까지 손을 따라다님) */
	private void gripRubble(ServerLevel level, ActionFx.Projectile pr) {
		dropRubble();
		kr.huntcraft.entity.RubbleEntity r = kr.huntcraft.entity.HuntEntities.RUBBLE.create(level, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
		if (r == null) {
			return;
		}
		r.grip(bonePoint(pr.bone(), pr.at()), blockOf(pr), pr.scale());
		level.addFreshEntity(r);
		heldRubble = r;
		heldRubbleSpec = pr;
	}

	private void dropRubble() {
		if (heldRubble != null && heldRubble.isAlive()) {
			if (level() instanceof ServerLevel sl) {
				kr.huntcraft.fx.Effects.play(sl, "rubble_break", heldRubble.position());
			}
			heldRubble.discard();
		}
		heldRubble = null;
		heldRubbleSpec = null;
	}

	private String chooseNext(ServerLevel level, MonsterDef d, Entity tgt) {
		if (tgt == null || !ai) {
			return d.idleAction;
		}
		if (!aware) {
			aware = true;
			if (d.discover != null) {
				return d.discover;
			}
		}
		if (!enraged() && d.rageAction != null && hp() / maxHp() <= d.rageBelow) {
			this.entityData.set(ENRAGED, true);
			this.entityData.set(SPEED, (float) d.rageSpeed);
			return d.rageAction;
		}
		// 근접 압박이 높으면 숨 고르기 없이 바로 (붙어 있는 헌터를 떼어 냄)
		boolean pressed = pressure() >= d.pressure.threshold();
		if (lastWasAttack && d.pause != null && !pressed) {
			boolean rage = enraged();
			if (getRandom().nextDouble() >= (rage ? d.rageChainChance : d.chainChance)) {
				double[] sec = rage ? d.ragePauseSeconds : d.pauseSeconds;
				pauseFrames = (sec[0] + getRandom().nextDouble() * (sec[1] - sec[0])) * 60.0;
				return d.pause;
			}
		}
		double dist = horizontalDistance(tgt);
		double angle = relativeAngle(tgt);
		List<MonsterDef.Action> candidates = new ArrayList<>();
		double total = 0.0;
		long now = level.getGameTime();
		for (MonsterDef.Action c : d.actions.values()) {
			if (c.weight() <= 0.0 || (c.rageOnly() && !enraged()) || cooldownUntil.getOrDefault(c.id(), 0L) > now) {
				continue;
			}
			if (c.flow().push() && pushCooldownUntil > now) {
				continue;
			}
			// 같은 공격을 3번 연속으로 쓰지 않음 (직전 두 번이 이 공격이면 빼기)
			if (recentAttacks.size() == 2 && recentAttacks.stream().allMatch(c.id()::equals)) {
				continue;
			}
			if (c.range() != null && (dist < c.range()[0] || dist > c.range()[1])) {
				continue;
			}
			if (c.angle() != null && !inAngle(angle, c.angle())) {
				continue;
			}
			candidates.add(c);
			total += weightOf(d, c);
		}
		// 압박 최대: 밀어내기만 (고를 수 있는 게 있으면)
		if (d.pressure.forceAtMax() && pressure() >= d.pressure.max() && candidates.stream().anyMatch(c -> c.flow().push())) {
			candidates.removeIf(c -> !c.flow().push());
			total = 0.0;
			for (MonsterDef.Action c : candidates) {
				total += weightOf(d, c);
			}
		}
		if (!candidates.isEmpty()) {
			double r = getRandom().nextDouble() * total;
			for (MonsterDef.Action c : candidates) {
				r -= weightOf(d, c);
				if (r <= 0.0) {
					return c.id();
				}
			}
			return candidates.getLast().id();
		}
		if (Math.abs(angle) > 50.0) {
			return angle > 0.0 ? d.turnRight : d.turnLeft;
		}
		MonsterDef.Action chase = d.actions.get(d.chase);
		if (chase != null && dist > chase.untilRange()) {
			return d.chase;
		}
		pauseFrames = 30.0;
		return d.pause != null ? d.pause : d.idleAction;
	}

	/** 고르기 가중치 — 근접 압박이 임계치 이상이면 밀어내기 패턴 × weightBoost */
	private double weightOf(MonsterDef d, MonsterDef.Action c) {
		return c.weight() * (c.flow().push() && pressure() >= d.pressure.threshold() ? d.pressure.weightBoost() : 1.0);
	}

	private void move(MonsterDef d, MonsterDef.Action a, Entity tgt, double frame) {
		double prev = Math.max(0.0, prevFrame);
		double step = Math.max(0.0, frame - prev);
		// 추적 (예비 동작 동안 목표 쪽으로)
		if (tgt != null && a.tracking(frame)) {
			float want = yawTo(tgt) - (float) a.aimRight();
			// 밀착 · 근접이면 예비 동작 선회를 빠르게 (zones.closeTurnRate)
			double rate = a.trackRate() * (zoneDistance(tgt) <= d.zones.close() ? d.zones.closeTurnRate() : 1.0);
			float maxTurn = (float) (rate * step / 60.0);
			float delta = Mth.wrapDegrees(want - facing);
			facing += Mth.clamp(delta, -maxTurn, maxTurn);
		}
		facing += (float) (a.spinAt(frame) - a.spinAt(prev));
		double[] r0 = a.rootOffset(prev);
		double[] r1 = a.rootOffset(frame);
		double forward = (r1[0] - r0[0]) * rootScale + a.moveSpeed() * step / 60.0;
		double right = r1[1] - r0[1];
		double rad = Math.toRadians(facing);
		double dx = -Math.sin(rad) * forward - Math.cos(rad) * right;
		double dz = Math.cos(rad) * forward - Math.sin(rad) * right;
		ColliderDef col = colliderDef();
		double scale = col == null ? 1.0 : col.scale;
		// 위 이동 (뛰어오르기): 올라가 있는 동안은 중력 끄고 root 대로, 내려와 땅에 닿으면 다시 중력
		double vy = getDeltaMovement().y;
		boolean airborneRoot = a.hasUp() && r1[2] > 0.02;
		setNoGravity(airborneRoot);
		if (a.hasUp() && (airborneRoot || r0[2] > 0.02)) {
			vy = (r1[2] - r0[2]) * scale;
		}
		setDeltaMovement(dx * scale, vy, dz * scale);
		setYRot(facing);
		setYHeadRot(facing);
		this.yBodyRot = facing;
	}

	private void roar(ServerLevel level, MonsterDef d, MonsterDef.Action a, double frame) {
		if (!a.roaring(frame)) {
			return;
		}
		if (prevFrame < 0.0 || !a.roaring(prevFrame)) {
			kr.huntcraft.fx.Effects.play(level, "roar", position().add(0.0, getBbHeight(), 0.0));
		}
		for (ServerPlayer p : level.players()) {
			if (roared.contains(p.getUUID()) || p.isSpectator() || horizontalDistance(p) > d.roarRadius) {
				continue;
			}
			roared.add(p.getUUID());
			HunterServer.roarReaction(p, this);
		}
	}

	/** 행동 데이터의 연출 · 충격파 (events) · 파티클 (emitters) */
	private void fx(ServerLevel level, MonsterDef.Action a, double prev, double frame) {
		if (a.events().isEmpty() && a.emitters().isEmpty() && a.flow().projectiles().isEmpty()) {
			return;
		}
		for (ActionFx.Event e : a.events()) {
			if (!e.firesBetween(prev, frame)) {
				continue;
			}
			Vec3 at = bonePoint(e.bone(), e.at());
			if (e.effect() != null) {
				kr.huntcraft.fx.Effects.play(level, e.effect(), at);
			}
			if (e.shake() != null) {
				ActionFx.Shake sh = e.shake();
				for (ServerPlayer p : level.players()) {
					double d = p.position().distanceTo(at);
					if (d < sh.radius() && net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, kr.huntcraft.net.CameraShakePayload.TYPE)) {
						net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
								new kr.huntcraft.net.CameraShakePayload((float) (sh.strength() * (1.0 - d / sh.radius())), (float) sh.duration()));
					}
				}
			}
			if (e.power() != null) {
				setPowered(e.power());
			}
			if (e.shockwave() != null) {
				// 땅 높이 (발 높이)에서, 몸이 보는 방향 부채꼴
				kr.huntcraft.fx.Shockwaves.start(level, new Vec3(at.x, getY(), at.z), getYRot(), e.shockwave());
			}
		}
		for (ActionFx.Projectile pr : a.flow().projectiles()) {
			if (pr.grip() >= 0 && prev < pr.grip() && frame >= pr.grip()) {
				gripRubble(level, pr);
			}
			if (heldRubble != null && heldRubbleSpec == pr) {
				heldRubble.follow(bonePoint(pr.bone(), pr.at()));
			}
			if (prev < pr.frame() && frame >= pr.frame()) {
				throwProjectile(level, pr);
			}
		}
		int f = (int) Math.floor(frame);
		if ((int) Math.floor(prev) == f) {
			return; // 이번 스텝에 프레임이 넘어가지 않음 (느린 재생)
		}
		for (ActionFx.Emitter em : a.emitters()) {
			if (f >= em.from() && f <= em.to() && (f - em.from()) % Math.max(1, em.every()) == 0) {
				kr.huntcraft.fx.Effects.particle(level, em.particle(), bonePoint(em.bone(), em.at()), em.count(), em.spread(), em.speed());
			}
		}
	}

	/** 본에 붙은 점 (모델 좌표 Blockbench 픽셀)의 지금 월드 위치 — 본이 없으면 루트 기준 */
	public Vec3 bonePoint(String bone, double[] px) {
		return bonePoint(bone, px, 0.0F);
	}

	/** 본 위 한 점의 월드 자리 (partial = 렌더 보간 — 클라이언트 카메라 연출용) */
	public Vec3 bonePoint(String bone, double[] px, float partial) {
		ColliderDef def = colliderDef();
		Vec3 local = kr.huntcraft.collision.Skeleton.pixels(px[0], px[1], px[2]);
		if (def == null) {
			return position();
		}
		int b = bone == null ? -1 : def.skeleton.find(bone);
		kr.huntcraft.collision.Transform[] bones = ColliderInstance.solve(this, partial, null);
		if (b < 0 || bones == null) {
			return root(partial).apply(local);
		}
		return bones[b].apply(def.skeleton.rest(b).inverse().apply(local));
	}

	// ── 피해 ────────────────────────────────────────────────

	/** 헌터 칼날 명중 (서버) — 체력 · 강인도 (0 이면 경직) · 자세 대미지. 반환 = 이번 명중으로 강인도가 깨졌는지 */
	public boolean onHunterHit(String part, double damage, double poiseDamage, double stanceDamage) {
		MonsterDef d = def();
		if (d == null || dead()) {
			return false;
		}
		// 붙은 채로 맞으면 근접 압박이 더 오름
		Entity near = targetEntity();
		if (near != null && zoneDistance(near) <= d.zones.close()) {
			this.entityData.set(PRESSURE, (float) Math.min(d.pressure.max(), pressure() + d.pressure.hitAdd()));
		}
		float hp = (float) (hp() - damage);
		if (hp <= 0.0F) {
			this.entityData.set(HP, 0.0F);
			this.entityData.set(DEAD, true);
			if (d.death != null) {
				startAction(d.death);
			}
			if (level() instanceof ServerLevel sl) {
				kr.huntcraft.hunter.HunterServer.slain(sl, this, net.minecraft.network.chat.Component.literal(d.displayName));
			}
			return false;
		}
		this.entityData.set(HP, hp);
		long now = level().getGameTime();
		if (stanceDamage > 0.0) {
			lastStanceHit = now;
			float st = (float) Math.min(d.stanceMax, stance() + stanceDamage);
			this.entityData.set(STANCE, st);
			// 자세 붕괴: 무릎 꿇음 (끊을 수 없는 행동도 끊음) — 치명 구간
			if (st >= d.stanceMax && d.stanceBreak != null && !critOpen() && !d.stanceBreak.equals(actionId())
					&& !String.valueOf(d.riposted).equals(actionId())) {
				this.entityData.set(STANCE, 0.0F);
				startAction(d.stanceBreak);
				if (level() instanceof ServerLevel sl) {
					kr.huntcraft.fx.Effects.play(sl, "stance_break", position().add(0.0, getBbHeight() * 0.6, 0.0));
				}
				return true;
			}
		}
		if (poiseDamage <= 0.0) {
			return false;
		}
		lastPoiseHit = now;
		float poise = (float) ((poise() < 0.0F ? d.poiseMax : poise()) - poiseDamage);
		if (poise > 0.0F) {
			this.entityData.set(POISE, poise);
			return false;
		}
		// 강인도가 깨짐: 가득 채우고, 끊을 수 있는 행동이면 경직
		this.entityData.set(POISE, (float) d.poiseMax);
		poiseBreaks++;
		MonsterDef.Action cur = action();
		if (d.flinch != null && (cur == null || cur.interruptible())) {
			startAction(d.flinch);
		}
		return true;
	}

	// ── 도우미 ──────────────────────────────────────────────

	/** 목표가 몸 윤곽 (zones.bodyParts hurtbox 바닥 투영) 에서 떨어진 수평 거리 — 안이면 0 (근접 구역 판단) */
	public double zoneDistance(Entity e) {
		MonsterDef d = def();
		ColliderDef col = colliderDef();
		kr.huntcraft.collision.Transform[] bones = colliders().cur();
		if (d == null || col == null || bones == null) {
			return horizontalDistance(e);
		}
		double best = Double.MAX_VALUE;
		for (ColliderDef.Box b : col.hurtboxes) {
			if (d.zones.bodyParts().contains(b.part()) && b.shape().toWorld(bones[b.bone()]) instanceof kr.huntcraft.collision.Shape.Obb ob) {
				best = Math.min(best, kr.huntcraft.collision.Geometry.distanceToObb(new Vec3(e.getX(), ob.center().y, e.getZ()), ob));
			}
		}
		return best == Double.MAX_VALUE ? horizontalDistance(e) : best;
	}

	public double horizontalDistance(Entity e) {
		return Math.hypot(e.getX() - getX(), e.getZ() - getZ());
	}

	private float yawTo(Entity e) {
		double dx = e.getX() - getX();
		double dz = e.getZ() - getZ();
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/** 목표 방향 (몸 기준, + = 오른쪽) */
	public float relativeAngle(Entity e) {
		return Mth.wrapDegrees(yawTo(e) - facing);
	}

	private static boolean inAngle(double angle, double[] range) {
		if (range[0] <= range[1]) {
			return angle >= range[0] && angle <= range[1];
		}
		// 범위가 ±180 을 넘어감 (예: [120, -120] = 뒤쪽)
		return angle >= range[0] || angle <= range[1];
	}
}
