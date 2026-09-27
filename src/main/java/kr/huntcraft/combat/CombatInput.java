package kr.huntcraft.combat;

/**
 * 입력 해석 결과 — 클라이언트 ComboResolver 가 만들고, combat=true 인 것은 모션 상태 머신(서버 · 클라이언트 예측)이 받습니다.
 * 캔슬 구간 · 스태미나 같은 조건은 상태 머신이 판단합니다.
 * *_RELEASE 는 키를 뗐다는 알림 — 선입력에 넣지 않고 "누르고 있음" 상태만 바꿈 (차지 · 가드).
 */
public enum CombatInput {
	LIGHT("약공격", "좌클릭", true),
	/** Shift + 좌클릭 (누르고 있으면 차지) */
	HEAVY("강공격", "Shift+좌클릭", true),
	HEAVY_RELEASE("강공격 뗌", "좌클릭 뗌", true),
	/** 우클릭 누르고 있는 동안 */
	GUARD("가드", "우클릭", true),
	GUARD_RELEASE("가드 뗌", "우클릭 뗌", true),
	/** F */
	PARRY("패링", "F", true),
	/** Space 짧게 (방향 없으면 백스텝) */
	EVADE("회피", "Space", true),
	/** 몬헌 데이터 호환 — 소울류에서는 키 없음 */
	SHEATHE_TOGGLE("납도/발도", "-", true),
	LOCK_ON_TOGGLE("락온", "Q", false),
	/** E — 안개 문 · 체크포인트 (서버가 가까운 대상을 고름) */
	INTERACT("상호작용", "E", false),
	USE_ITEM("아이템 사용", "R", true),
	ITEM_PREV("아이템 ◀", "↑", false),
	ITEM_NEXT("아이템 ▶", "↓", false);

	public final String label;
	public final String source;
	/** 모션 상태 머신이 받는 입력 */
	public final boolean combat;

	CombatInput(String label, String source, boolean combat) {
		this.label = label;
		this.source = source;
		this.combat = combat;
	}

	/** 키를 뗀 알림 (선입력에 안 넣음) */
	public boolean release() {
		return this == HEAVY_RELEASE || this == GUARD_RELEASE;
	}
}
