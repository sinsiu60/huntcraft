package kr.huntcraft.collision;

import net.minecraft.world.phys.Vec3;

/**
 * 한 순간의 애니메이션 자세 — 본마다 회전(Blockbench 도 단위)과 위치 오프셋(Blockbench 픽셀).
 * 값은 Blockbench 키프레임과 같은 표기로 넣고, 좌표계 변환은 {@link Skeleton} 이 합니다.
 */
public final class Pose {
	private final double[] rot;
	private final double[] pos;

	public Pose(int bones) {
		rot = new double[bones * 3];
		pos = new double[bones * 3];
	}

	public void clear() {
		java.util.Arrays.fill(rot, 0.0);
		java.util.Arrays.fill(pos, 0.0);
	}

	public Pose rotate(int bone, double x, double y, double z) {
		rot[bone * 3] = x;
		rot[bone * 3 + 1] = y;
		rot[bone * 3 + 2] = z;
		return this;
	}

	public Pose move(int bone, double x, double y, double z) {
		pos[bone * 3] = x;
		pos[bone * 3 + 1] = y;
		pos[bone * 3 + 2] = z;
		return this;
	}

	/** Blockbench 표기 회전 (도) [x, y, z] — 렌더러(GeckoLib 본 조작)용 */
	public double[] rotation(int bone) {
		return new double[] {rot[bone * 3], rot[bone * 3 + 1], rot[bone * 3 + 2]};
	}

	/** Blockbench 표기 위치 오프셋 (픽셀) [x, y, z] */
	public double[] offset(int bone) {
		return new double[] {pos[bone * 3], pos[bone * 3 + 1], pos[bone * 3 + 2]};
	}

	double rx(int bone) {
		return rot[bone * 3];
	}

	double ry(int bone) {
		return rot[bone * 3 + 1];
	}

	double rz(int bone) {
		return rot[bone * 3 + 2];
	}

	Vec3 position(int bone) {
		return Skeleton.pixels(pos[bone * 3], pos[bone * 3 + 1], pos[bone * 3 + 2]);
	}
}
