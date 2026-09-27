package kr.huntcraft.collision;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 월드 공간 판정 도형. 구(sphere)는 양 끝이 같은 캡슐. */
public sealed interface Shape permits Shape.Capsule, Shape.Obb {
	AABB bounds();

	/** 도형의 가장 얇은 두께 (스윕 서브스텝 간격 계산용) */
	double thickness();

	/** 스텝 사이 이동량 추정에 쓰는 대표점들 */
	Vec3[] keyPoints();

	record Capsule(Vec3 a, Vec3 b, double radius) implements Shape {
		@Override
		public AABB bounds() {
			return new AABB(Math.min(a.x, b.x) - radius, Math.min(a.y, b.y) - radius, Math.min(a.z, b.z) - radius,
					Math.max(a.x, b.x) + radius, Math.max(a.y, b.y) + radius, Math.max(a.z, b.z) + radius);
		}

		@Override
		public double thickness() {
			return radius * 2.0;
		}

		@Override
		public Vec3[] keyPoints() {
			return new Vec3[] {a, b};
		}
	}

	/** 회전 박스: 중심 · 세 축(단위벡터) · 축별 반길이 */
	record Obb(Vec3 center, Vec3 axisX, Vec3 axisY, Vec3 axisZ, Vec3 half) implements Shape {
		public Vec3 axis(int i) {
			return i == 0 ? axisX : i == 1 ? axisY : axisZ;
		}

		public double half(int i) {
			return i == 0 ? half.x : i == 1 ? half.y : half.z;
		}

		public Vec3[] corners() {
			Vec3[] c = new Vec3[8];
			int n = 0;
			for (int sx = -1; sx <= 1; sx += 2) {
				for (int sy = -1; sy <= 1; sy += 2) {
					for (int sz = -1; sz <= 1; sz += 2) {
						c[n++] = center.add(axisX.scale(sx * half.x)).add(axisY.scale(sy * half.y)).add(axisZ.scale(sz * half.z));
					}
				}
			}
			return c;
		}

		@Override
		public AABB bounds() {
			double ex = Math.abs(axisX.x) * half.x + Math.abs(axisY.x) * half.y + Math.abs(axisZ.x) * half.z;
			double ey = Math.abs(axisX.y) * half.x + Math.abs(axisY.y) * half.y + Math.abs(axisZ.y) * half.z;
			double ez = Math.abs(axisX.z) * half.x + Math.abs(axisY.z) * half.y + Math.abs(axisZ.z) * half.z;
			return new AABB(center.x - ex, center.y - ey, center.z - ez, center.x + ex, center.y + ey, center.z + ez);
		}

		@Override
		public double thickness() {
			return 2.0 * Math.min(half.x, Math.min(half.y, half.z));
		}

		@Override
		public Vec3[] keyPoints() {
			return corners();
		}
	}
}
