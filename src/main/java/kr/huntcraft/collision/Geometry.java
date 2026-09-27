package kr.huntcraft.collision;

import net.minecraft.world.phys.Vec3;

/**
 * 도형 겹침 판정 — 캡슐 · OBB 조합 전부.
 *   캡슐-캡슐: 선분-선분 최근접 거리 ≤ 반지름 합 (Ericson, Real-Time Collision Detection 5.1.9)
 *   캡슐-OBB : 선분-박스 거리 ≤ 반지름. 박스까지 거리는 선분 위에서 볼록 함수라 황금분할 탐색
 *   OBB-OBB  : 분리축 15개 (SAT)
 * 겹치면 {@link Contact} (접촉점 · 침투 깊이), 아니면 null.
 */
public final class Geometry {
	private static final double EPS = 1.0E-9;
	private static final double INV_PHI = (Math.sqrt(5.0) - 1.0) / 2.0;

	private Geometry() {}

	/** 접촉점과 침투 깊이(≥ 0) */
	public record Contact(Vec3 point, double depth) {}

	public static Contact test(Shape a, Shape b) {
		if (!a.bounds().intersects(b.bounds())) {
			return null;
		}
		if (a instanceof Shape.Capsule ca && b instanceof Shape.Capsule cb) {
			return capsuleCapsule(ca, cb);
		}
		if (a instanceof Shape.Capsule ca && b instanceof Shape.Obb ob) {
			return capsuleObb(ca, ob);
		}
		if (a instanceof Shape.Obb oa && b instanceof Shape.Capsule cb) {
			return capsuleObb(cb, oa);
		}
		return obbObb((Shape.Obb) a, (Shape.Obb) b);
	}

	public static Contact capsuleCapsule(Shape.Capsule a, Shape.Capsule b) {
		Vec3[] p = closestSegmentSegment(a.a(), a.b(), b.a(), b.b());
		double d = p[0].distanceTo(p[1]);
		double depth = a.radius() + b.radius() - d;
		if (depth < 0.0) {
			return null;
		}
		// 두 표면 사이 가운데
		Vec3 dir = d > EPS ? p[1].subtract(p[0]).scale(1.0 / d) : Vec3.ZERO;
		Vec3 onA = p[0].add(dir.scale(a.radius()));
		Vec3 onB = p[1].subtract(dir.scale(b.radius()));
		return new Contact(onA.add(onB).scale(0.5), depth);
	}

	public static Contact capsuleObb(Shape.Capsule c, Shape.Obb box) {
		// 선분 위 t 에서 박스까지 거리 — 볼록이라 황금분할로 최소
		double lo = 0.0;
		double hi = 1.0;
		double x1 = hi - INV_PHI * (hi - lo);
		double x2 = lo + INV_PHI * (hi - lo);
		double f1 = distanceToObb(lerp(c.a(), c.b(), x1), box);
		double f2 = distanceToObb(lerp(c.a(), c.b(), x2), box);
		for (int i = 0; i < 40; i++) {
			if (f1 < f2) {
				hi = x2;
				x2 = x1;
				f2 = f1;
				x1 = hi - INV_PHI * (hi - lo);
				f1 = distanceToObb(lerp(c.a(), c.b(), x1), box);
			} else {
				lo = x1;
				x1 = x2;
				f1 = f2;
				x2 = lo + INV_PHI * (hi - lo);
				f2 = distanceToObb(lerp(c.a(), c.b(), x2), box);
			}
		}
		double t = (lo + hi) * 0.5;
		// 끝점도 확인 (최솟값이 양 끝에 있는 경우)
		double best = distanceToObb(lerp(c.a(), c.b(), t), box);
		double da = distanceToObb(c.a(), box);
		double db = distanceToObb(c.b(), box);
		if (da < best) {
			best = da;
			t = 0.0;
		}
		if (db < best) {
			best = db;
			t = 1.0;
		}
		double depth = c.radius() - best;
		if (depth < 0.0) {
			return null;
		}
		Vec3 onSeg = lerp(c.a(), c.b(), t);
		Vec3 onBox = closestOnObb(onSeg, box);
		return new Contact(onSeg.add(onBox).scale(0.5), depth);
	}

	public static Contact obbObb(Shape.Obb a, Shape.Obb b) {
		double minOverlap = Double.MAX_VALUE;
		Vec3 d = b.center().subtract(a.center());
		for (int k = 0; k < 15; k++) {
			Vec3 axis;
			if (k < 3) {
				axis = a.axis(k);
			} else if (k < 6) {
				axis = b.axis(k - 3);
			} else {
				axis = a.axis((k - 6) / 3).cross(b.axis((k - 6) % 3));
				double len = axis.length();
				if (len < 1.0E-6) {
					continue; // 평행한 축
				}
				axis = axis.scale(1.0 / len);
			}
			double ra = 0.0;
			double rb = 0.0;
			for (int i = 0; i < 3; i++) {
				ra += a.half(i) * Math.abs(a.axis(i).dot(axis));
				rb += b.half(i) * Math.abs(b.axis(i).dot(axis));
			}
			double overlap = ra + rb - Math.abs(d.dot(axis));
			if (overlap < 0.0) {
				return null;
			}
			minOverlap = Math.min(minOverlap, overlap);
		}
		// 접촉점 근사: 서로의 중심에 가장 가까운 두 점의 가운데
		Vec3 pa = closestOnObb(b.center(), a);
		Vec3 pb = closestOnObb(a.center(), b);
		return new Contact(pa.add(pb).scale(0.5), minOverlap);
	}

	public static Vec3 closestOnObb(Vec3 p, Shape.Obb box) {
		Vec3 d = p.subtract(box.center());
		Vec3 q = box.center();
		for (int i = 0; i < 3; i++) {
			double dist = d.dot(box.axis(i));
			double h = box.half(i);
			dist = Math.max(-h, Math.min(h, dist));
			q = q.add(box.axis(i).scale(dist));
		}
		return q;
	}

	public static double distanceToObb(Vec3 p, Shape.Obb box) {
		return p.distanceTo(closestOnObb(p, box));
	}

	/** 두 선분의 최근접점 [선분1 위, 선분2 위] */
	public static Vec3[] closestSegmentSegment(Vec3 p1, Vec3 q1, Vec3 p2, Vec3 q2) {
		Vec3 d1 = q1.subtract(p1);
		Vec3 d2 = q2.subtract(p2);
		Vec3 r = p1.subtract(p2);
		double a = d1.dot(d1);
		double e = d2.dot(d2);
		double f = d2.dot(r);
		double s;
		double t;
		if (a <= EPS && e <= EPS) {
			return new Vec3[] {p1, p2};
		}
		if (a <= EPS) {
			s = 0.0;
			t = clamp01(f / e);
		} else {
			double c = d1.dot(r);
			if (e <= EPS) {
				t = 0.0;
				s = clamp01(-c / a);
			} else {
				double b = d1.dot(d2);
				double denom = a * e - b * b;
				s = denom > EPS ? clamp01((b * f - c * e) / denom) : 0.0;
				t = (b * s + f) / e;
				if (t < 0.0) {
					t = 0.0;
					s = clamp01(-c / a);
				} else if (t > 1.0) {
					t = 1.0;
					s = clamp01((b - c) / a);
				}
			}
		}
		return new Vec3[] {p1.add(d1.scale(s)), p2.add(d2.scale(t))};
	}

	private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
		return a.add(b.subtract(a).scale(t));
	}

	private static double clamp01(double v) {
		return v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
	}
}
