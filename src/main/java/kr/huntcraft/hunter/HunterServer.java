package kr.huntcraft.hunter;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import kr.huntcraft.collision.HitSystem;
import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.combat.HunterCombat;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.net.CombatInputPayload;
import kr.huntcraft.net.CombatSyncPayload;
import kr.huntcraft.net.ConfigSyncPayload;
import kr.huntcraft.net.HunterSyncPayload;
import kr.huntcraft.net.IntentPayload;
import kr.huntcraft.fx.Effects;
import net.minecraft.server.level.ServerLevel;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 서버 쪽 헌터 — 플레이어별 권한 상태 (스태미나 · 발도 · 모션).
 *
 *   틱 시작: 모션 상태 머신 한 스텝 (받아 둔 입력 처리 → 이번 틱 자세 확정 → 틱 끝에 HitSystem 이 판정)
 *   틱 끝  : 스태미나 규칙, 동기화 (상태가 바뀌면 즉시, 스태미나 6틱 · 모션 10틱마다)
 *   명중   : 공격한 헌터에 히트스톱
 */
public final class HunterServer {
	private static final int SYNC_INTERVAL = 6;
	private static final int COMBAT_SYNC_INTERVAL = 10;
	private static final Map<UUID, Entry> STATES = new HashMap<>();

	private static final class Entry {
		final HunterState state = new HunterState(CommonConfig.get().stamina.max);
		final HunterCombat combat = new HunterCombat();
		final HunterBody body;
		boolean sprintHeld;
		boolean moving;
		boolean lastDrawn;
		boolean lastExhausted;
		/** 보스 손에 잡혀 있음 (다른 공격은 안 맞음 · 움직일 수 없음) */
		boolean held;
		/** 놓인 뒤 남은 낙하 무시 틱 (들려 있던 높이로 바닐라 낙사하지 않게) */
		int heldGrace;
		/** 치명 일격 중 붙잡은 보스 (없으면 -1) */
		int critTarget = -1;
		/** 쓰러짐 (쓰러진 뒤 지난 틱 — progress.deathSeconds 가 되면 체크포인트에서 부활) */
		boolean dead;
		int deadTicks;
		int sinceSync;
		String lastMotion;
		long lastSerial;
		int sinceCombatSync;

		Entry(ServerPlayer p) {
			body = new HunterBody(p, combat, state);
		}
	}

	private HunterServer() {}

	public static void init() {
		DamageCalc.init();
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer p = handler.getPlayer();
			Entry e = new Entry(p);
			STATES.put(p.getUUID(), e);
			HitSystem.add(e.body);
			sendConfig(p, CommonConfig.toJson());
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Entry e = STATES.remove(handler.getPlayer().getUUID());
			if (e != null) {
				HitSystem.remove(e.body);
			}
		});
		ServerTickEvents.START_SERVER_TICK.register(HunterServer::combatTick);
		ServerTickEvents.END_SERVER_TICK.register(HunterServer::tick);
		// 패링 성공: 헌터는 쳐내기 (짧은 경직 뒤 치명 일격 가능), 보스는 크게 휘청 (치명 구간)
		HitSystem.listenParry(ev -> {
			if (ev.target() instanceof HunterBody hb && hb.player() instanceof ServerPlayer sp && ev.attacker() instanceof kr.huntcraft.monster.MonsterEntity m) {
				Entry e = STATES.get(sp.getUUID());
				if (e == null) {
					return;
				}
				e.combat.force("parry_success", e.combat.facing());
				m.onParried();
				parries++;
				Effects.play(sp.level(), "parry", ev.point());
				kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT, "  패링 성공: %s ← %s (%s) · 자세 %.0f",
						sp.getName().getString(), m.def() == null ? "몬스터" : m.def().displayName, m.actionId(), m.stance()));
			}
		});
		HitSystem.listen(ev -> {
			if (ev.attacker() instanceof HunterBody hb && ev.damage() instanceof DamageCalc.Result r) {
				hb.combat().onHit(r.hit());
				if (hb.player() instanceof ServerPlayer owner) {
					Effects.play(owner.level(), r.weak() ? "hit_weak" : "hit", ev.point());
				}
				if (ev.target() instanceof kr.huntcraft.monster.MonsterEntity m) {
					float before = m.poise();
					boolean broke = m.onHunterHit(ev.part(), r.damage(), r.poise(), r.stance());
					kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT, "  %s 강인도 %.1f → %.1f%s · 자세 %.1f",
							m.def() == null ? "몬스터" : m.def().displayName, before, m.poise(), broke ? " (경직)" : "", m.stance()));
				}
			}
			if (ev.target() instanceof HunterBody hb && ev.damage() instanceof DamageCalc.MonsterHit mh
					&& hb.player() instanceof ServerPlayer sp) {
				onMonsterHit(sp, mh);
				// 보스 공격이 닿은 자리에서 쾅 (휘두르기처럼 땅에 닿지 않는 공격의 impact 레이어)
				if (!mh.grab()) {
					Effects.play(sp.level(), "boss_hit", ev.point());
				}
			}
		});
		net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			Entry old = STATES.remove(oldPlayer.getUUID());
			if (old != null) {
				HitSystem.remove(old.body);
			}
			Entry e = new Entry(newPlayer);
			STATES.put(newPlayer.getUUID(), e);
			HitSystem.add(e.body);
		});
	}

	public static HunterState get(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		return e == null ? null : e.state;
	}

	public static HunterCombat combat(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		return e == null ? null : e.combat;
	}

	public static HunterBody body(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		return e == null ? null : e.body;
	}

	public static void onIntent(ServerPlayer p, IntentPayload msg) {
		Entry e = STATES.get(p.getUUID());
		if (e != null) {
			e.sprintHeld = msg.sprintHeld();
			e.moving = msg.moving();
		}
	}

	public static void onCombatInput(ServerPlayer p, CombatInputPayload msg) {
		Entry e = STATES.get(p.getUUID());
		if (e != null && !e.dead) {
			e.combat.input(msg.input(), msg.dirYaw(), msg.hasDir(), msg.locked());
		}
	}

	/**
	 * 몬스터 공격 명중 — 체력을 깎고 반응 모션을 강제 (몬스터 쪽을 보게 해서 뒤로 밀려남).
	 * 반응: flinch (움찔) · knockback (밀려남) · launch (날아감 → 넘어짐 → 기상, 끝나면 기상 무적)
	 * 판정 우선순위: 무적 (판정 코어가 이미 걸러 냄) > 패링 (Phase 6) > 가드 (정면 · 가드 불가 아님) > 일반 피격.
	 * 슈퍼아머: 공격의 강인도 대미지가 지금 모션의 강인도 보너스 이하면 대미지만 받고 경직 없음.
	 */
	private static void onMonsterHit(ServerPlayer p, DamageCalc.MonsterHit hit) {
		Entry e = STATES.get(p.getUUID());
		if (e == null || e.dead || (e.held && !hit.grab() && hit.attackerId() >= 0)) {
			return;
		}
		// 잡기: 대미지 대신 보스 손에 잡힘 (보스가 쥐는 행동으로 넘어가 나중에 던짐)
		if (hit.grab()) {
			if (!e.held && p.level().getEntity(hit.attackerId()) instanceof kr.huntcraft.monster.MonsterEntity m) {
				m.onGrabbed(p);
			}
			return;
		}
		CommonConfig.Player pc = CommonConfig.get().player;
		double frame = e.combat.frame();
		float toAttacker = yawTo(p.position(), hit.from());
		// 가드: 정면 guardAngle 안 · 가드 불가가 아님 → 대미지 경감 + 스태미나 소모 + 막기 경직
		if (!hit.unblockable() && e.combat.guardActive(frame)
				&& Math.abs(net.minecraft.util.Mth.wrapDegrees(toAttacker - e.combat.facing())) <= pc.guardAngle / 2.0) {
			double cost = (hit.guardStamina() > 0.0 ? hit.guardStamina() * pc.guardStaminaScale : hit.damage() * pc.guardStaminaPerDamage);
			double taken = hit.damage() * (1.0 - pc.guardAbsorption);
			boolean broke = e.state.stamina < cost;
			e.state.stamina = Math.max(0.0, e.state.stamina - cost);
			e.state.regenDelayTicks = kr.huntcraft.core.FixedTick.ticks(CommonConfig.get().stamina.regenDelaySeconds);
			HunterVitals.damaged(e.state, taken);
			e.sinceSync = SYNC_INTERVAL;
			kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT, "  가드: %s 대미지 %.1f → %.1f · 스태미나 -%.1f (남음 %.1f)%s",
					p.getName().getString(), hit.damage(), taken, cost, e.state.stamina, broke ? " · 스태미나 바닥 (가드 붕괴는 Phase 6)" : ""));
			if (e.state.health <= 0.0) {
				die(p, e);
				return;
			}
			// 스태미나가 바닥나면 가드 붕괴 (크게 휘청 — 빈틈)
			e.combat.force(broke ? "guard_break" : "guard_hit", e.combat.facing());
			pushAway(p, hit, broke ? 1.0 : pc.guardPushScale);
			// 가드 성공 · 가드 붕괴는 소리가 서로 달라야 함 (막았다 / 뚫렸다)
			Effects.play((ServerLevel) p.level(), broke ? "guard_break" : "guard_hit", p.position().add(0.0, 1.2, 0.0));
			if (broke) {
				guardBreaks++;
			}
			guarded++;
			return;
		}
		HunterVitals.damaged(e.state, hit.damage());
		e.sinceSync = SYNC_INTERVAL;
		hurtSound(p, hit.damage());
		if (e.state.health <= 0.0) {
			die(p, e);
			return;
		}
		pushAway(p, hit, 1.0);
		// 끝까지 가는 모션 (회복 물약 마시기): 맞아도 대미지만 받고 계속
		kr.huntcraft.combat.MoveSet.Motion cur = e.combat.current();
		if (cur != null && cur.x().commit()) {
			kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT, "  %s: %s 맞았지만 끝까지 (대미지 %.1f)",
					cur.name() == null ? cur.id() : cur.name(), p.getName().getString(), hit.damage()));
			return;
		}
		// 슈퍼아머: 강인도 대미지를 버팀 → 경직 없이 모션 계속
		double bonus = e.combat.hyperArmorBonus(frame);
		if (bonus >= 0.0 && hit.poise() <= bonus) {
			kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT, "  슈퍼아머: %s 강인도 대미지 %.1f ≤ %.1f · 경직 없음 (대미지 %.1f)",
					p.getName().getString(), hit.poise(), bonus, hit.damage()));
			armored++;
			return;
		}
		String motion = switch (hit.reaction() == null ? "flinch" : hit.reaction()) {
			case "knockback" -> "hit_knockback";
			case "launch" -> "hit_launch";
			default -> "hit_flinch";
		};
		e.combat.force(motion, yawTo(p.position(), hit.from()));
	}

	/** 시험: 가드로 막은 수 · 슈퍼아머로 버틴 수 · 패링 · 가드 붕괴 · 치명 일격 */
	private static int guarded;
	private static int armored;
	private static int parries;
	private static int guardBreaks;
	private static int criticals;
	private static double lastCritical;

	public static int parries() {
		return parries;
	}

	public static int guardBreaks() {
		return guardBreaks;
	}

	public static int criticals() {
		return criticals;
	}

	public static double lastCritical() {
		return lastCritical;
	}

	public static int guarded() {
		return guarded;
	}

	public static int armored() {
		return armored;
	}

	/**
	 * 치명 일격: 모션이 시작되면 앞의 치명 구간 보스를 붙잡고 (보스는 riposted — 칼이 박힐 때까지 굳음),
	 * critical.frame 에 대미지 = 공격력 × 모션값/100 × (1 − 흡수율) × damage.critMultiplier.
	 */
	private static void critical(ServerPlayer p, Entry e) {
		kr.huntcraft.combat.MoveSet.Motion m = e.combat.current();
		int critFrame = m == null ? -1 : m.x().critFrame();
		if (critFrame < 0) {
			e.critTarget = -1;
			return;
		}
		ServerLevel level = (ServerLevel) p.level();
		if (e.critTarget < 0) {
			kr.huntcraft.monster.MonsterEntity t = kr.huntcraft.combat.CriticalRules.target(p);
			if (t == null) {
				return;
			}
			e.critTarget = t.getId();
			t.onRiposteStart();
		}
		if (e.combat.prevFrame() < critFrame && e.combat.frame() >= critFrame
				&& level.getEntity(e.critTarget) instanceof kr.huntcraft.monster.MonsterEntity t) {
			CommonConfig cfg = CommonConfig.get();
			double absorption = t.colliderDef() == null ? 0.0 : t.colliderDef().absorption;
			double damage = cfg.weapon.attack * (m.x().critMotionValue() / cfg.damage.motionValueDivisor) * (1.0 - absorption)
					* cfg.damage.critMultiplier;
			float before = t.hp();
			t.onRiposteImpact(damage);
			criticals++;
			lastCritical = damage;
			Effects.play(level, "critical", t.position().add(0.0, t.getBbHeight() * 0.5, 0.0));
			kr.huntcraft.collision.HitLog.note(String.format(java.util.Locale.ROOT,
					"  치명 일격: %s → %s 대미지 %.1f (모션값 %.0f × (1-흡수 %.2f) × 치명 %.1f) · 체력 %.0f → %.0f", p.getName().getString(),
					t.def() == null ? "몬스터" : t.def().displayName, damage, m.x().critMotionValue(), absorption, cfg.damage.critMultiplier, before,
					t.hp()));
		}
	}

	/** 모션 강제 (안개 문 헤치기 · 휴식 등) */
	public static void forceMotion(ServerPlayer p, String motion, float yaw) {
		Entry e = STATES.get(p.getUUID());
		if (e != null) {
			e.combat.force(motion, yaw);
		}
	}

	public static boolean dead(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		return e != null && e.dead;
	}

	/** 시험: 쓰러진 횟수 · 부활 횟수 */
	private static int deaths;
	private static int respawns;

	public static int deaths() {
		return deaths;
	}

	public static int respawns() {
		return respawns;
	}

	/**
	 * 쓰러짐 — 바닐라 죽음 대신 (죽음 화면 · 아이템 떨굼 없음): 쓰러지는 모션 (무적) + "쓰러졌다" 연출,
	 * progress.deathSeconds 뒤 체크포인트에서 부활. 보스전에서 빠짐 (남은 헌터가 없으면 보스 초기화).
	 */
	private static void die(ServerPlayer p, Entry e) {
		if (e.dead) {
			return;
		}
		e.dead = true;
		e.deadTicks = 0;
		deaths++;
		releaseHeld(p, e);
		e.combat.force("death", e.combat.facing());
		e.sinceSync = SYNC_INTERVAL;
		if (ServerPlayNetworking.canSend(p, kr.huntcraft.net.BannerPayload.TYPE)) {
			ServerPlayNetworking.send(p, new kr.huntcraft.net.BannerPayload(kr.huntcraft.net.BannerPayload.DEATH));
		}
		kr.huntcraft.progress.Encounter.onHunterDeath(p);
	}

	private static void releaseHeld(ServerPlayer p, Entry e) {
		if (e.held) {
			e.held = false;
			e.heldGrace = 20;
		}
	}

	/** 체크포인트에서 부활: 체력 · 회복 물약 · 스태미나 가득 */
	private static void respawn(ServerPlayer p, Entry e) {
		double[] at = kr.huntcraft.progress.Checkpoints.respawn(p);
		p.teleportTo((ServerLevel) p.level(), at[0], at[1], at[2], java.util.Set.of(), (float) at[3], 0.0F, true);
		p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
		p.resetFallDistance();
		refill(e.state);
		e.combat.clear();
		e.dead = false;
		e.deadTicks = 0;
		e.sinceSync = SYNC_INTERVAL;
		respawns++;
	}

	/** 체력 · 회복 물약 · 스태미나를 가득 (휴식 · 부활) */
	private static void refill(HunterState st) {
		CommonConfig cfg = CommonConfig.get();
		st.health = cfg.player.maxHealth;
		st.healPending = 0.0;
		st.stamina = cfg.stamina.max;
		st.exhausted = false;
		for (int i = 0; i < st.itemCounts.length && i < cfg.items.size(); i++) {
			st.itemCounts[i] = cfg.items.get(i).count;
		}
	}

	/** 상호작용 키 (E): 안개 문 밖이면 들어감, 체크포인트 가까이면 휴식 */
	public static void onInteract(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		if (e == null || e.dead || e.combat.current() != null) {
			return;
		}
		if (kr.huntcraft.progress.Encounter.canEnter(p)) {
			kr.huntcraft.progress.Encounter.enter(p);
			return;
		}
		net.minecraft.core.BlockPos fire = kr.huntcraft.progress.Checkpoints.near(p);
		if (fire != null && !kr.huntcraft.progress.Encounter.fighting(p)) {
			float yaw = (float) Math.toDegrees(Math.atan2(-(fire.getX() + 0.5 - p.getX()), fire.getZ() + 0.5 - p.getZ()));
			e.combat.force("rest", yaw);
			refill(e.state);
			e.sinceSync = SYNC_INTERVAL;
			kr.huntcraft.progress.Checkpoints.rest(p, fire);
			Effects.play((ServerLevel) p.level(), "rest", net.minecraft.world.phys.Vec3.atCenterOf(fire));
			if (ServerPlayNetworking.canSend(p, kr.huntcraft.net.BannerPayload.TYPE)) {
				ServerPlayNetworking.send(p, new kr.huntcraft.net.BannerPayload(kr.huntcraft.net.BannerPayload.REST));
			}
		}
	}

	/** 보스 처치 — 주변 플레이어에게 제목 · 효과 */
	public static void slain(ServerLevel level, net.minecraft.world.entity.Entity monster, net.minecraft.network.chat.Component name) {
		// 화면 연출 ("강적 격파") 은 보스전 흐름 (Encounter) 이 전투에 들어온 헌터에게 보냄 — 여기서는 효과만
		Effects.play(level, "slay", monster.position());
	}

	/** 보스 손에 잡힘 / 풀림 — 잡혀 있는 동안 "held" 모션 (움직일 수 없음 · lookAt = 잡은 보스 쪽을 봄) */
	public static void setHeld(ServerPlayer p, boolean held, net.minecraft.world.phys.Vec3 lookAt) {
		Entry e = STATES.get(p.getUUID());
		if (e == null) {
			return;
		}
		e.held = held;
		if (!held) {
			e.heldGrace = 20;
		}
		if (held) {
			e.combat.force("held", lookAt == null ? p.getYRot() : yawTo(p.position(), lookAt));
		}
		e.sinceSync = SYNC_INTERVAL;
	}

	public static boolean held(ServerPlayer p) {
		Entry e = STATES.get(p.getUUID());
		return e != null && e.held;
	}

	/** 판정 코어 밖에서 온 보스 공격 (충격파 등) — 무적 · 거리 조건은 부른 쪽이 이미 확인 */
	public static void applyBossHit(ServerPlayer p, DamageCalc.MonsterHit hit) {
		onMonsterHit(p, hit);
	}

	/**
	 * 밀어내기: 보스 중심 (hit.from) 에서 헌터 쪽으로 수평 외력 — 헌터 이동은 클라이언트가 계산하므로 클라이언트에 알림 (PushPayload).
	 * 속도를 덮어쓰지 않고 감속 곡선으로 더함 · 벽에 막히면 그 자리에서 멈춤 (추가 경직 없음)
	 */
	private static void pushAway(ServerPlayer p, DamageCalc.MonsterHit hit, double scale) {
		if (hit.push() <= 0.0 || scale <= 0.0) {
			return;
		}
		double dx = p.getX() - hit.from().x;
		double dz = p.getZ() - hit.from().z;
		double len = Math.hypot(dx, dz);
		if (len < 1.0E-3) {
			float yaw = p.getYRot();
			dx = Math.sin(Math.toRadians(yaw));
			dz = -Math.cos(Math.toRadians(yaw));
			len = 1.0;
		}
		pushes++;
		if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(p, kr.huntcraft.net.PushPayload.TYPE)) {
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(p,
					new kr.huntcraft.net.PushPayload((float) (dx / len), (float) (dz / len), (float) (hit.push() * scale), (float) hit.pushSeconds()));
		}
	}

	private static int pushes;

	/** 시험: 밀어내기 외력을 보낸 수 */
	public static int pushes() {
		return pushes;
	}

	/** 맞은 소리 2단계 — 대미지가 player.heavyHitDamage 이상이면 hunter_hurt_heavy */
	private static void hurtSound(ServerPlayer p, double damage) {
		Effects.play((ServerLevel) p.level(), damage >= CommonConfig.get().player.heavyHitDamage ? "hunter_hurt_heavy" : "hunter_hurt",
				p.position().add(0.0, 1.0, 0.0));
	}

	/** 아이템 바에서 고른 아이템 (클라이언트가 알림) */
	public static void onItemSelect(ServerPlayer p, int index) {
		Entry e = STATES.get(p.getUUID());
		if (e != null && index >= 0 && index < CommonConfig.get().items.size()) {
			e.state.itemIndex = index;
		}
	}

	/** 포효 — 범위 안 헌터 귀막기 (회피 무적 중이면 무시) */
	public static void roarReaction(ServerPlayer p, net.minecraft.world.entity.Entity monster) {
		Entry e = STATES.get(p.getUUID());
		if (e == null || e.combat.invulnerable(e.body.animTime(0.0F))) {
			return;
		}
		e.combat.force("ear_cover", yawTo(p.position(), monster.position()));
	}

	private static float yawTo(net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
		return (float) Math.toDegrees(Math.atan2(-(to.x - from.x), to.z - from.z));
	}

	/** 설정을 다시 읽었을 때 접속 중인 모두에게 */
	public static void broadcastConfig(MinecraftServer server) {
		String json = CommonConfig.toJson();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			sendConfig(p, json);
		}
	}

	/** 설정 + 판정 데이터 */
	private static void sendConfig(ServerPlayer p, String json) {
		if (ServerPlayNetworking.canSend(p, ConfigSyncPayload.TYPE)) {
			ServerPlayNetworking.send(p, new ConfigSyncPayload(json));
		}
		if (ServerPlayNetworking.canSend(p, kr.huntcraft.net.DefsSyncPayload.TYPE)) {
			ServerPlayNetworking.send(p, new kr.huntcraft.net.DefsSyncPayload(kr.huntcraft.collision.Defs.sources()));
		}
	}

	private static void combatTick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Entry e = STATES.get(p.getUUID());
			if (e != null) {
				e.combat.setAirborne(!p.onGround() && !p.isInWater());
				e.combat.setCritReady(kr.huntcraft.combat.CriticalRules.target(p) != null);
				e.combat.tick(e.state, p.getYRot());
				critical(p, e);
				int item = e.combat.takeItemEvent();
				if (item >= 0 && HunterVitals.useItem(e.state, item)) {
					e.sinceSync = SYNC_INTERVAL;
				}
				// 휘두르는 소리: 공격 구간이 시작되는 프레임
				kr.huntcraft.combat.MoveSet.Motion m = e.combat.current();
				if (m != null && e.combat.hitstop() == 0) {
					for (kr.huntcraft.combat.MoveSet.Hit h : m.hits()) {
						if (e.combat.prevFrame() < h.from() && e.combat.frame() >= h.from()) {
							Effects.play(p.level(), "swing", p.position().add(0.0, 1.2, 0.0));
						}
					}
				}
				HunterVitals.tick(e.state);
			}
		}
	}

	private static void tick(MinecraftServer server) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			Entry e = STATES.get(p.getUUID());
			if (e == null) {
				continue;
			}
			// 쓰러짐 → 부활
			if (e.dead && ++e.deadTicks >= kr.huntcraft.core.FixedTick.ticks(CommonConfig.get().progress.deathSeconds)) {
				respawn(p, e);
			}
			// 보스 손에 들려 있거나 막 놓임: 바닐라 낙하 거리 지움 (서버가 손 자리로 계속 옮기는 동안 클라이언트 이동으로 쌓임)
			if (e.held || e.heldGrace > 0) {
				p.resetFallDistance();
				e.heldGrace = Math.max(0, e.heldGrace - 1);
			}
			// 모션 중에는 달리지 않음
			HunterRules.tick(e.state, e.sprintHeld, e.moving && e.combat.current() == null);
			boolean changed = e.state.drawn != e.lastDrawn || e.state.exhausted != e.lastExhausted;
			if (changed || ++e.sinceSync >= SYNC_INTERVAL) {
				e.sinceSync = 0;
				e.lastDrawn = e.state.drawn;
				e.lastExhausted = e.state.exhausted;
				if (ServerPlayNetworking.canSend(p, HunterSyncPayload.TYPE)) {
					ServerPlayNetworking.send(p, HunterSyncPayload.of(e.state));
				}
			}
			boolean motionChanged = !Objects.equals(e.combat.motionId(), e.lastMotion) || e.combat.serial() != e.lastSerial;
			if (motionChanged || changed || ++e.sinceCombatSync >= COMBAT_SYNC_INTERVAL) {
				e.sinceCombatSync = 0;
				e.lastMotion = e.combat.motionId();
				e.lastSerial = e.combat.serial();
				CombatSyncPayload msg = CombatSyncPayload.of(p.getId(), e.combat, e.state);
				for (ServerPlayer viewer : PlayerLookup.tracking(p)) {
					if (ServerPlayNetworking.canSend(viewer, CombatSyncPayload.TYPE)) {
						ServerPlayNetworking.send(viewer, msg);
					}
				}
				if (ServerPlayNetworking.canSend(p, CombatSyncPayload.TYPE)) {
					ServerPlayNetworking.send(p, msg);
				}
			}
		}
	}
}
