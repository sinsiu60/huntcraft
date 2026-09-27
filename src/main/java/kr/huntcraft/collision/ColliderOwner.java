package kr.huntcraft.collision;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 판정 도형을 가진 엔티티 (몬스터 · 플레이어 · 시험 엔티티).
 *
 * 핵심 규칙: 자세는 "애니메이션 시간 → 자세" 순수 함수({@link #samplePose})입니다. 그래서
 *   서버 판정(60Hz 스텝) · 클라이언트 디버그 렌더(프레임 보간) · 되감기(lag compensation) 가 모두 같은 값을 얻습니다.
 */
public interface ColliderOwner {
	Entity self();

	/** 판정 정의 (defs 가 아직 없으면 null) */
	ColliderDef colliderDef();

	/** 서버 쪽 스텝 상태 */
	ColliderInstance colliders();

	/** 애니메이션 시간 (틱, 소수 가능). partial = 렌더 보간 비율 (서버는 0) */
	double animTime(float partial);

	/** 직전 스텝의 애니메이션 시간 — 판정 스윕의 시작 시각. 히트스톱처럼 시간이 멈추면 animTime 과 같음 */
	default double animTimePrev() {
		return animTime(0.0F) - 1.0;
	}

	/** 피격 판정이 켜져 있는지 (회피 무적이면 false) */
	default boolean hurtboxesActive(double time) {
		return true;
	}

	/** 패링 구간 — 이 동안 패링 가능한 공격이 닿으면 맞지 않고 패링 이벤트로 알림 (HitSystem.listenParry) */
	default boolean parryActive(double time) {
		return false;
	}

	/** 공격자로서: time 의 공격이 패링 가능한지 (보스 공격 데이터 parryable) */
	default boolean attackParryable(double time) {
		return false;
	}

	/** 표적으로서 한 공격에 한 번만 맞는지 (헌터 = 몬스터 한 번 공격에 한 번. 몬스터는 부위마다 맞을 수 있음) */
	default boolean hitOncePerAttack() {
		return false;
	}

	/** 이 표적을 때릴 수 있는지 (헌터끼리는 안 때림 등) */
	default boolean canHit(ColliderOwner target) {
		return true;
	}

	/** time 에서의 자세를 out 에 씀 (out 은 비워져 있음) */
	void samplePose(double time, Pose out);

	/** 이 hitbox 가 time 에 켜져 있는지 (활성 프레임 구간) */
	default boolean hitboxActive(String hitboxId, double time) {
		return false;
	}

	/** time 의 공격 인스턴스 번호 (0 = 공격 없음). 번호가 바뀌면 같은 부위도 다시 맞을 수 있음 */
	default long attackInstance(double time) {
		return 0L;
	}

	/** 스윕 사용 여부 — 시험 막대가 터널링 비교용으로만 끔 */
	default boolean sweepEnabled() {
		return kr.huntcraft.config.CommonConfig.get().collision.sweepEnabled;
	}

	/** 루트 변환 = 위치 · 몸 방향(GeckoLib 과 같이 180 - yaw) · 정의의 배율 */
	default Transform root(float partial) {
		Entity e = self();
		ColliderDef def = colliderDef();
		double scale = def == null ? 1.0 : def.scale;
		Vec3 pos = partial <= 0.0F ? e.position() : e.getPosition(partial);
		float yaw = partial <= 0.0F ? e.getYRot() : Mth.rotLerp(partial, e.yRotO, e.getYRot());
		return new Transform(pos, Transform.eulerZYX(0.0, 180.0 - yaw, 0.0), scale);
	}
}
