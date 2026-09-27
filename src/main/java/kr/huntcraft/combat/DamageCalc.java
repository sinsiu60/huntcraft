package kr.huntcraft.combat;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderOwner;
import kr.huntcraft.collision.HitSystem;
import kr.huntcraft.config.CommonConfig;

/**
 * 소울류 대미지 = (무기공격력 × 모션값/100) × (1 − 대상 흡수율) × 부위 배율 × 치명 배율
 *   강인도 대미지 · 자세 대미지는 기술 데이터 (hits 의 poise · stance). 차지 공격은 차지량만큼 셋 다 커짐 (weapon.charge*Bonus).
 *   약점 = 부위가 weak (배율 1.2~1.5 인 곳) → 대미지 숫자 주황. 치명 일격은 Phase 6 (지금은 1).
 */
public final class DamageCalc {
	private DamageCalc() {}

	/** 보스 → 헌터 명중 (가드 · 패링 · 슈퍼아머에 쓰는 필드 포함) */
	public record MonsterHit(double damage, String reaction, net.minecraft.world.phys.Vec3 from, boolean parryable, boolean unblockable,
							 double poise, double guardStamina, boolean grab, int attackerId, double push, double pushSeconds) {
		/** 판정 코어 밖 공격 (충격파 · 잔해) */
		public MonsterHit(double damage, String reaction, net.minecraft.world.phys.Vec3 from) {
			this(damage, reaction, from, false, false, 0.0, 0.0, false, -1, 0.0, 0.0);
		}

		/** 밀어내기 없는 공격 */
		public MonsterHit(double damage, String reaction, net.minecraft.world.phys.Vec3 from, boolean parryable, boolean unblockable, double poise,
						  double guardStamina, boolean grab, int attackerId) {
			this(damage, reaction, from, parryable, unblockable, poise, guardStamina, grab, attackerId, 0.0, 0.0);
		}
	}

	/** 헌터 → 대상 명중 결과 (명중 로그 · 대상 강인도 · 자세) */
	public record Result(double damage, double motionValue, double multiplier, double absorption, double poise, double stance, boolean crit,
						 boolean weak, double charge, MoveSet.Hit hit) {}

	public static void init() {
		HitSystem.setResolver(DamageCalc::resolve);
	}

	private static Object resolve(ColliderOwner attacker, ColliderOwner target, String part, String hitboxId, double time) {
		if (attacker instanceof kr.huntcraft.monster.MonsterEntity m) {
			// 근접 보조 판정이면 그 대미지 (본의 50~70%) · 강인도 · 반응
			kr.huntcraft.monster.MonsterDef.Hit h = m.hitAt(time, hitboxId);
			return h == null ? null : new MonsterHit(h.damage(), h.reaction(), m.position(), h.parryable(), h.unblockable(), h.poise(),
					h.guardStamina(), h.grab(), m.getId(), h.push(), h.pushSeconds());
		}
		if (!(attacker instanceof HunterBody hunter)) {
			return null;
		}
		MoveSet.Hit hit = hunter.combat().hitAt(time);
		ColliderDef def = target.colliderDef();
		MoveSet.Motion motion = hunter.combat().current();
		if (hit == null || def == null || motion == null) {
			return null;
		}
		return compute(hit, motion.x().charged() ? hunter.combat().chargeLevel() : 0.0, def.part(part), def.absorption);
	}

	/** 한 번의 명중 계산 (시험에서도 씀) */
	public static Result compute(MoveSet.Hit hit, double charge, ColliderDef.Part part, double absorption) {
		CommonConfig cfg = CommonConfig.get();
		CommonConfig.Weapon w = cfg.weapon;
		double mv = hit.motionValue() * (1.0 + w.chargeDamageBonus * charge);
		double poise = hit.poise() * (1.0 + w.chargePoiseBonus * charge);
		double stance = hit.stance() * (1.0 + w.chargeStanceBonus * charge);
		double damage = w.attack * (mv / cfg.damage.motionValueDivisor) * (1.0 - absorption) * part.multiplier();
		return new Result(damage, mv, part.multiplier(), absorption, poise, stance, false, part.weak(), charge, hit);
	}
}
