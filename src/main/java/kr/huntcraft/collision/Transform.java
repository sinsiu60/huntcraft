package kr.huntcraft.collision;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * 강체 변환 (이동 + 회전 + 균일 배율). 불변 — 연산마다 새 객체.
 * 본 월드 변환 · 엔티티 루트 변환을 모두 이걸로 표현해서 스텝 사이 보간(이동 lerp · 회전 slerp)이 정확합니다.
 */
public final class Transform {
	public static final Transform IDENTITY = new Transform(Vec3.ZERO, new Quaterniond(), 1.0);

	public final Vec3 pos;
	private final Quaterniond rot;
	public final double scale;

	public Transform(Vec3 pos, Quaterniond rot, double scale) {
		this.pos = pos;
		this.rot = new Quaterniond(rot);
		this.scale = scale;
	}

	public static Transform translation(Vec3 v) {
		return new Transform(v, new Quaterniond(), 1.0);
	}

	/** GeckoLib 과 같은 순서 — 벡터에 X → Y → Z 순으로 회전 (행렬로는 Rz·Ry·Rx). 각도는 도 */
	public static Quaterniond eulerZYX(double xDeg, double yDeg, double zDeg) {
		return new Quaterniond().rotateZ(Math.toRadians(zDeg)).rotateY(Math.toRadians(yDeg)).rotateX(Math.toRadians(xDeg));
	}

	public static Transform rotation(Quaterniond q) {
		return new Transform(Vec3.ZERO, q, 1.0);
	}

	public Quaterniond rot() {
		return new Quaterniond(rot);
	}

	/** 점 변환 */
	public Vec3 apply(Vec3 p) {
		Vector3d v = new Vector3d(p.x * scale, p.y * scale, p.z * scale);
		rot.transform(v);
		return new Vec3(v.x + pos.x, v.y + pos.y, v.z + pos.z);
	}

	/** 방향 변환 (이동 · 배율 없이) */
	public Vec3 rotate(Vec3 d) {
		Vector3d v = new Vector3d(d.x, d.y, d.z);
		rot.transform(v);
		return new Vec3(v.x, v.y, v.z);
	}

	/** this · child (child 를 먼저 적용) */
	public Transform then(Transform child) {
		return new Transform(apply(child.pos), new Quaterniond(rot).mul(child.rot), scale * child.scale);
	}

	public Transform inverse() {
		Quaterniond inv = new Quaterniond(rot).conjugate();
		Vector3d p = new Vector3d(-pos.x, -pos.y, -pos.z);
		inv.transform(p);
		double s = 1.0 / scale;
		return new Transform(new Vec3(p.x * s, p.y * s, p.z * s), inv, s);
	}

	public static Transform lerp(Transform a, Transform b, double t) {
		if (t <= 0.0) {
			return a;
		}
		if (t >= 1.0) {
			return b;
		}
		return new Transform(a.pos.lerp(b.pos, t), new Quaterniond(a.rot).slerp(b.rot, t), a.scale + (b.scale - a.scale) * t);
	}
}
