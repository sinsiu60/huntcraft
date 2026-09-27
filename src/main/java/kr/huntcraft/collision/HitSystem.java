package kr.huntcraft.collision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import kr.huntcraft.config.CommonConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 서버 판정 루프 — 매 서버 틱(60Hz) 끝에, 엔티티 이동이 다 끝난 뒤:
 *   1) 모든 판정 엔티티의 본 변환을 한 스텝 진행 (직전 값 보관)
 *   2) 공격 인스턴스가 켜진 엔티티마다, 활성 hitbox 를 다른 엔티티의 hurtbox 에 스윕 검사
 *   3) 표적마다 첫 접촉 부위 1개 → 같은 인스턴스 · 같은 표적 · 같은 부위는 1번만 → {@link HitEvent}
 *
 * 되감기(lag compensation): 공격자가 플레이어면 표적의 과거 본 변환(링 버퍼)으로 검사할 수 있게 해 둠 —
 * collision.rewindTicks (기본 0 = 싱글플레이) 틱 만큼, 플레이어 지연을 넘지 않게.
 */
public final class HitSystem {
	private static final Set<ColliderOwner> OWNERS = Collections.newSetFromMap(new WeakHashMap<>());
	private static final List<Consumer<HitEvent>> LISTENERS = new ArrayList<>();
	/** 공격자 → (인스턴스 번호, 이미 맞은 "표적:부위") */
	private static final Map<ColliderOwner, Dedupe> DEDUPE = new WeakHashMap<>();

	/** 공격자 하나의 중복 기록: (인스턴스, 표적) → 맞은 부위 ("*" = 무엇이든 맞음). 지난 인스턴스는 버림 */
	private static final class Dedupe {
		final Map<Long, Map<Integer, Set<String>>> byInstance = new HashMap<>();

		Set<String> parts(long instance, int targetId) {
			if (byInstance.size() > 4 && !byInstance.containsKey(instance)) {
				long keep = instance;
				byInstance.keySet().removeIf(k -> k < keep - 2);
			}
			return byInstance.computeIfAbsent(instance, k -> new HashMap<>()).computeIfAbsent(targetId, k -> new HashSet<>());
		}
	}

	/** 스텝 사이 어느 시점에라도 켜진 hitbox 가 있는지 (끝점 + 중간 몇 곳) */
	private static boolean anyActive(ColliderOwner atk, ColliderDef def, double t0, double t1) {
		for (int i = 0; i <= 8; i++) {
			double t = t0 + (t1 - t0) * i / 8.0;
			if (atk.attackInstance(t) == 0L) {
				continue;
			}
			for (ColliderDef.Box h : def.hitboxes) {
				if (atk.hitboxActive(h.id(), t)) {
					return true;
				}
			}
		}
		return false;
	}

	private HitSystem() {}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ColliderOwner o) {
				OWNERS.add(o);
			}
		});
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
			if (entity instanceof ColliderOwner o) {
				OWNERS.remove(o);
				DEDUPE.remove(o);
			}
		});
		ServerTickEvents.END_SERVER_TICK.register(HitSystem::tick);
	}

	/** 명중마다 대미지를 계산하는 쪽 (combat.DamageCalc) — 없으면 null */
	public interface Resolver {
		Object resolve(ColliderOwner attacker, ColliderOwner target, String part, String hitboxId, double attackerTime);
	}

	private static Resolver resolver = (a, t, p, box, time) -> null;

	public static void setResolver(Resolver r) {
		resolver = r;
	}

	/** 엔티티가 아닌 판정 주인(플레이어 몸 등)을 직접 등록 */
	public static void add(ColliderOwner owner) {
		OWNERS.add(owner);
	}

	public static void remove(ColliderOwner owner) {
		OWNERS.remove(owner);
		DEDUPE.remove(owner);
	}

	public static void listen(Consumer<HitEvent> listener) {
		LISTENERS.add(listener);
	}

	private static void tick(MinecraftServer server) {
		if (OWNERS.isEmpty()) {
			return;
		}
		OWNERS.removeIf(o -> o.self().isRemoved() && !(o.self() instanceof net.minecraft.world.entity.player.Player));
		long now = server.getTickCount();
		List<ColliderOwner> owners = new ArrayList<>(OWNERS);
		for (ColliderOwner o : owners) {
			if (!o.self().isRemoved()) {
				o.colliders().step(now);
			}
		}
		CommonConfig.Collision cfg = CommonConfig.get().collision;
		for (ColliderOwner atk : owners) {
			ColliderInstance ai = atk.colliders();
			if (atk.self().isRemoved() || !ai.ready()) {
				continue;
			}
			// 스텝 안 시각 frac → 애니메이션 시간 (한 스텝 = 1틱)
			double timeCur = atk.animTime(0.0F);
			double timePrev = atk.animTimePrev();
			if (ai.def().hitboxes.isEmpty() || !anyActive(atk, ai.def(), timePrev, timeCur)) {
				continue;
			}
			Dedupe dd = DEDUPE.computeIfAbsent(atk, k -> new Dedupe());
			int rewind = rewindTicks(atk.self(), cfg);
			for (ColliderOwner tgt : owners) {
				ColliderInstance ti = tgt.colliders();
				if (tgt == atk || tgt.self().isRemoved() || !ti.ready() || tgt.self().level() != atk.self().level()
						|| ti.def().hurtboxes.isEmpty() || !atk.canHit(tgt)) {
					continue;
				}
				Transform[] tCur = ti.cur();
				Transform[] tPrev = ti.prev();
				if (rewind > 0) {
					Transform[] c = ti.bonesAt(now - rewind);
					Transform[] p = ti.bonesAt(now - rewind - 1);
					if (c != null && p != null) {
						tCur = c;
						tPrev = p;
					}
				}
				int targetId = tgt.self().getId();
				double tgtCur = tgt.animTime(0.0F);
				double tgtPrev = tgt.animTimePrev();
				Sweep.Rules rules = new Sweep.Rules() {
					@Override
					public boolean active(ColliderDef.Box h, double frac) {
						double t = timePrev + (timeCur - timePrev) * frac;
						return atk.attackInstance(t) != 0L && atk.hitboxActive(h.id(), t);
					}

					@Override
					public boolean alreadyHit(String part, double frac) {
						// 표적이 그 순간 무적(회피)이면 맞지 않음
						if (!tgt.hurtboxesActive(tgtPrev + (tgtCur - tgtPrev) * frac)) {
							return true;
						}
						Set<String> done = dd.parts(atk.attackInstance(timePrev + (timeCur - timePrev) * frac), targetId);
						return done.contains(cfg.hitOncePerTarget || tgt.hitOncePerAttack() ? "*" : part);
					}
				};
				Sweep.Hit hit = Sweep.first(ai.def().hitboxes, ai.prev(), ai.cur(), ti.def(), tPrev, tCur, rules, atk.sweepEnabled());
				if (hit == null) {
					parryProbe(atk, tgt, ai, tPrev, tCur, timePrev, timeCur, tgtPrev, tgtCur, dd, targetId, cfg, now);
					continue;
				}
				double hitTime = timePrev + (timeCur - timePrev) * hit.toi();
				long instance = atk.attackInstance(hitTime);
				Set<String> done = dd.parts(instance, targetId);
				done.add(hit.hurtbox().part());
				done.add("*");
				// 우선순위: 무적 (위 alreadyHit 에서 걸러짐) > 패링 > 가드 · 피격 — 닿은 순간 패링 구간이고 패링 가능한 공격이면 패링
				if (tgt.parryActive(tgtPrev + (tgtCur - tgtPrev) * hit.toi()) && atk.attackParryable(hitTime)) {
					ParryEvent pe = new ParryEvent(atk, tgt, instance, hit.contact().point(), now);
					for (Consumer<ParryEvent> l : PARRY_LISTENERS) {
						l.accept(pe);
					}
					continue;
				}
				HitEvent ev = new HitEvent(atk, tgt, instance, hit.hitbox().id(), hit.hurtbox().id(), hit.hurtbox().part(),
						hit.contact().point(), hit.contact().depth(), hit.toi(), hit.substeps(), now, rewind,
						hitTime, resolver.resolve(atk, tgt, hit.hurtbox().part(), hit.hitbox().id(), hitTime));
				for (Consumer<HitEvent> l : LISTENERS) {
					l.accept(ev);
				}
			}
		}
	}

	/**
	 * 패링: 표적이 패링 구간이면 (보통 무적과 함께), 무적을 무시하고 한 번 더 스윕 — 닿았으면 대미지 없이 패링 이벤트.
	 * 그 공격 인스턴스는 이 표적에 "맞은 것"으로 기록해서 패링 구간이 끝난 뒤에도 다시 맞지 않음.
	 * 패링 가능한 (parryable) 공격만.
	 */
	private static void parryProbe(ColliderOwner atk, ColliderOwner tgt, ColliderInstance ai, Transform[] tPrev, Transform[] tCur,
									 double timePrev, double timeCur, double tgtPrev, double tgtCur, Dedupe dd, int targetId,
									 CommonConfig.Collision cfg, long now) {
		if (!tgt.parryActive(tgtPrev) && !tgt.parryActive(tgtCur)) {
			return;
		}
		Sweep.Rules probe = new Sweep.Rules() {
			@Override
			public boolean active(ColliderDef.Box h, double frac) {
				double t = timePrev + (timeCur - timePrev) * frac;
				return atk.attackInstance(t) != 0L && atk.hitboxActive(h.id(), t);
			}

			@Override
			public boolean alreadyHit(String part, double frac) {
				if (!tgt.parryActive(tgtPrev + (tgtCur - tgtPrev) * frac)) {
					return true;
				}
				Set<String> done = dd.parts(atk.attackInstance(timePrev + (timeCur - timePrev) * frac), targetId);
				return done.contains("*") || done.contains(part);
			}
		};
		Sweep.Hit hit = Sweep.first(ai.def().hitboxes, ai.prev(), ai.cur(), tgt.colliders().def(), tPrev, tCur, probe, atk.sweepEnabled());
		if (hit == null || !atk.attackParryable(timePrev + (timeCur - timePrev) * hit.toi())) {
			return;
		}
		long instance = atk.attackInstance(timePrev + (timeCur - timePrev) * hit.toi());
		Set<String> done = dd.parts(instance, targetId);
		done.add("*");
		done.add(hit.hurtbox().part());
		ParryEvent ev = new ParryEvent(atk, tgt, instance, hit.contact().point(), now);
		for (Consumer<ParryEvent> l : PARRY_LISTENERS) {
			l.accept(ev);
		}
	}

	/** 패링 1건 */
	public record ParryEvent(ColliderOwner attacker, ColliderOwner target, long instance, Vec3 point, long tick) {}

	private static final List<Consumer<ParryEvent>> PARRY_LISTENERS = new ArrayList<>();

	public static void listenParry(Consumer<ParryEvent> listener) {
		PARRY_LISTENERS.add(listener);
	}

	private static int rewindTicks(Entity attacker, CommonConfig.Collision cfg) {
		if (cfg.rewindTicks <= 0 || !(attacker instanceof ServerPlayer sp)) {
			return 0;
		}
		// 왕복 지연의 절반 ≈ 입력이 도착하기까지 (ms → 60Hz 틱)
		int latencyTicks = (int) Math.round(sp.connection.latency() / 2.0 / (1000.0 / 60.0));
		return Math.min(cfg.rewindTicks, latencyTicks);
	}

	/** 한 번의 명중 */
	public record HitEvent(ColliderOwner attacker, ColliderOwner target, long instance, String hitbox, String hurtbox,
						   String part, Vec3 point, double depth, double toi, int substeps, long tick, int rewindTicks,
						   double attackerTime, Object damage) {}
}
