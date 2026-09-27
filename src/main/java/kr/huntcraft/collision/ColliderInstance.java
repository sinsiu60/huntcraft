package kr.huntcraft.collision;

import kr.huntcraft.config.CommonConfig;

/**
 * 한 엔티티의 판정 상태 (서버). 매 스텝 본 월드 변환을 계산해 직전 값과 함께 들고 있어서
 * 스윕이 두 스텝 사이를 보간할 수 있습니다. 지난 스텝들의 본 변환은 되감기용으로 링 버퍼에 남깁니다.
 */
public final class ColliderInstance {
	private final ColliderOwner owner;
	private ColliderDef def;
	private Pose pose;
	private Transform[] prev;
	private Transform[] cur;
	private long curTick = Long.MIN_VALUE;

	private Transform[][] history = new Transform[0][];
	private long[] historyTick = new long[0];

	public ColliderInstance(ColliderOwner owner) {
		this.owner = owner;
	}

	public ColliderDef def() {
		return def;
	}

	/** 직전 · 현재 스텝이 모두 있음 (스윕 가능) */
	public boolean ready() {
		return prev != null && cur != null;
	}

	public Transform[] prev() {
		return prev;
	}

	public Transform[] cur() {
		return cur;
	}

	public long tick() {
		return curTick;
	}

	/** 서버 틱 끝에 한 번 (HitSystem 이 부름) */
	public void step(long serverTick) {
		ColliderDef d = owner.colliderDef();
		if (d == null) {
			return;
		}
		if (d != def) {
			// 정의가 바뀜 (/huntcraft reload) — 보간할 이전 값이 없으므로 처음부터
			def = d;
			pose = new Pose(d.skeleton.size());
			prev = null;
			cur = null;
		}
		if (serverTick == curTick) {
			return;
		}
		Transform[] next = solve(owner, 0.0F, pose);
		prev = cur;
		cur = next;
		curTick = serverTick;
		remember(serverTick, next);
	}

	/** 그 틱의 본 변환 (되감기). 없으면 null */
	public Transform[] bonesAt(long tick) {
		for (int i = 0; i < historyTick.length; i++) {
			if (historyTick[i] == tick && history[i] != null) {
				return history[i];
			}
		}
		return null;
	}

	private void remember(long tick, Transform[] bones) {
		int size = Math.max(2, CommonConfig.get().collision.historyTicks);
		if (history.length != size) {
			history = new Transform[size][];
			historyTick = new long[size];
			java.util.Arrays.fill(historyTick, Long.MIN_VALUE);
		}
		int slot = (int) Math.floorMod(tick, (long) size);
		history[slot] = bones;
		historyTick[slot] = tick;
	}

	/** 지금(또는 렌더 보간 시점)의 본 월드 변환 — 서버 스텝과 클라이언트 렌더가 같이 씀 */
	public static Transform[] solve(ColliderOwner owner, float partial, Pose scratch) {
		ColliderDef d = owner.colliderDef();
		if (d == null) {
			return null;
		}
		Pose p = scratch != null ? scratch : new Pose(d.skeleton.size());
		p.clear();
		owner.samplePose(owner.animTime(partial), p);
		Transform[] out = new Transform[d.skeleton.size()];
		d.skeleton.solve(p, owner.root(partial), out);
		return out;
	}
}
