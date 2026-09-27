package kr.huntcraft.collision;

import java.util.List;

import kr.huntcraft.config.CommonConfig;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 스윕 판정 — 직전 스텝과 현재 스텝 사이를 서브스텝으로 나눠 보간 샘플링 (터널링 방지).
 *
 *   서브스텝 수 = ceil(한 스텝 최대 이동량 / 간격), 간격 = 가장 얇은 도형 두께 × spacingFraction (최소 minSpacing)
 *     → 연속한 두 샘플 도형이 서로 겹치므로, 칼날이 한 스텝에 표적을 통째로 지나가도 어느 샘플에선가 닿음
 *   보간: 본 변환을 이동 lerp · 회전 slerp (끝점 직선 보간이 아니라 회전 궤적을 따라감).
 *         slerp 는 짧은 쪽으로 돌기 때문에 한 스텝(1/60초) 회전이 180° 미만이어야 함 — 초당 10800° 까지 안전
 *   처음 닿은 서브스텝 구간 안에서 이분법으로 첫 접촉 시각(TOI)을 좁히고,
 *   여러 부위가 닿았으면 TOI 가 가장 이른 부위 1개를 기준 부위로 (같으면 더 깊이 들어간 쪽)
 */
public final class Sweep {
	private Sweep() {}

	public record Hit(ColliderDef.Box hitbox, ColliderDef.Box hurtbox, double toi, Geometry.Contact contact, int substeps) {}

	/**
	 * 스텝 안 시각(0 = 직전 스텝, 1 = 현재 스텝)마다의 판정 규칙 — 활성 프레임 구간과 공격 인스턴스는
	 * 스텝 끝이 아니라 서브스텝 시각으로 판단해야 스텝 도중 켜지고 꺼지는 공격을 놓치지 않습니다.
	 */
	public interface Rules {
		/** 이 hitbox 가 스텝 안 시각 frac 에 켜져 있는지 */
		boolean active(ColliderDef.Box hitbox, double frac);

		/** 이 부위가 frac 시각의 공격 인스턴스에서 이미 맞았는지 (맞았으면 후보에서 뺌) */
		boolean alreadyHit(String part, double frac);
	}

	/**
	 * @param atkPrev/atkCur 공격자 본 (직전 · 현재)
	 * @param tgtPrev/tgtCur 표적 본 (되감기 시 과거 값)
	 * @param rules          서브스텝 시각별 활성 · 중복 규칙
	 */
	public static Hit first(List<ColliderDef.Box> hitboxes, Transform[] atkPrev, Transform[] atkCur,
							ColliderDef target, Transform[] tgtPrev, Transform[] tgtCur, Rules rules, boolean sweep) {
		CommonConfig.Collision cfg = CommonConfig.get().collision;
		// 한 스텝 최대 이동량과 가장 얇은 두께
		double travel = 0.0;
		double thin = Double.MAX_VALUE;
		AABB atkBounds = null;
		for (ColliderDef.Box h : hitboxes) {
			Shape s0 = h.shape().toWorld(atkPrev[h.bone()]);
			Shape s1 = h.shape().toWorld(atkCur[h.bone()]);
			travel = Math.max(travel, maxTravel(s0, s1) * arcFactor(atkPrev[h.bone()], atkCur[h.bone()]));
			thin = Math.min(thin, s1.thickness());
			atkBounds = union(atkBounds, s0.bounds().minmax(s1.bounds()));
		}
		double tgtTravel = 0.0;
		AABB tgtBounds = null;
		for (ColliderDef.Box u : target.hurtboxes) {
			Shape s0 = u.shape().toWorld(tgtPrev[u.bone()]);
			Shape s1 = u.shape().toWorld(tgtCur[u.bone()]);
			tgtTravel = Math.max(tgtTravel, maxTravel(s0, s1) * arcFactor(tgtPrev[u.bone()], tgtCur[u.bone()]));
			thin = Math.min(thin, s1.thickness());
			tgtBounds = union(tgtBounds, s0.bounds().minmax(s1.bounds()));
		}
		// 광역 판정: 스텝 전체를 덮는 상자끼리 안 겹치면 끝. 회전 궤적은 두 자세의 상자 밖으로 부풀 수 있어 이동량의 절반만큼 키움
		if (atkBounds == null || tgtBounds == null || !atkBounds.inflate(travel * 0.5).intersects(tgtBounds.inflate(tgtTravel * 0.5))) {
			return null;
		}
		double spacing = Math.max(cfg.minSpacing, thin * cfg.spacingFraction);
		int n = sweep ? (int) Math.min(cfg.maxSubsteps, Math.max(1, Math.ceil((travel + tgtTravel) / spacing))) : 1;

		for (int i = 1; i <= n; i++) {
			double t = i / (double) n;
			Hit best = null;
			for (ColliderDef.Box h : hitboxes) {
				if (!rules.active(h, t)) {
					continue;
				}
				for (ColliderDef.Box u : target.hurtboxes) {
					if (rules.alreadyHit(u.part(), t) || at(h, u, atkPrev, atkCur, tgtPrev, tgtCur, t) == null) {
						continue;
					}
					// 이 쌍이 처음 닿은 시각을 (i-1)/n ~ i/n 안에서 좁힘
					double lo = (i - 1) / (double) n;
					double hi = t;
					Geometry.Contact c = at(h, u, atkPrev, atkCur, tgtPrev, tgtCur, hi);
					if (sweep) {
						for (int k = 0; k < cfg.toiRefineIterations; k++) {
							double mid = (lo + hi) * 0.5;
							Geometry.Contact m = rules.active(h, mid) && !rules.alreadyHit(u.part(), mid)
									? at(h, u, atkPrev, atkCur, tgtPrev, tgtCur, mid) : null;
							if (m != null) {
								hi = mid;
								c = m;
							} else {
								lo = mid;
							}
						}
					}
					Hit cand = new Hit(h, u, hi, c, n);
					if (best == null || cand.toi < best.toi - 1.0E-9
							|| (Math.abs(cand.toi - best.toi) <= 1.0E-9 && cand.contact.depth() > best.contact.depth())) {
						best = cand;
					}
				}
			}
			if (best != null) {
				return best;
			}
		}
		return null;
	}

	private static Geometry.Contact at(ColliderDef.Box h, ColliderDef.Box u, Transform[] ap, Transform[] ac,
									   Transform[] tp, Transform[] tc, double t) {
		Shape a = h.shape().toWorld(Transform.lerp(ap[h.bone()], ac[h.bone()], t));
		Shape b = u.shape().toWorld(Transform.lerp(tp[u.bone()], tc[u.bone()], t));
		return Geometry.test(a, b);
	}

	private static double maxTravel(Shape s0, Shape s1) {
		Vec3[] p0 = s0.keyPoints();
		Vec3[] p1 = s1.keyPoints();
		double m = 0.0;
		for (int i = 0; i < p0.length; i++) {
			m = Math.max(m, p0[i].distanceTo(p1[i]));
		}
		return m;
	}

	/** 회전하는 점은 현(끝점 거리)보다 호가 길다 — 회전각 θ 에서 호/현 = (θ/2) / sin(θ/2) */
	private static double arcFactor(Transform a, Transform b) {
		org.joml.Quaterniond d = a.rot().conjugate().mul(b.rot());
		double theta = 2.0 * Math.acos(Math.min(1.0, Math.abs(d.w)));
		if (theta < 1.0E-4) {
			return 1.0;
		}
		return (theta / 2.0) / Math.sin(theta / 2.0);
	}

	private static AABB union(AABB a, AABB b) {
		return a == null ? b : a.minmax(b);
	}
}
