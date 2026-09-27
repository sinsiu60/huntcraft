package kr.huntcraft.hunter;

import java.util.List;

import kr.huntcraft.config.CommonConfig;

/**
 * 헌터(플레이어) 전투 상태 — 서버가 권한을 갖고, 클라이언트는 같은 규칙({@link HunterRules})으로 예측한 뒤 서버 값으로 맞춥니다.
 * 체력 · 아이템 개수는 서버만 바꾸고 동기화로 받음 (HUD 가 그대로 그림).
 */
public final class HunterState {
	/** 소울류: 태도는 늘 뽑혀 있음 (발도 · 납도 없음 — 몬헌 모션 데이터 호환용으로 남김) */
	public boolean drawn = true;
	/** 가드 중 (스태미나 회복 느림, Phase 3) */
	public boolean guarding;
	public double stamina;
	/** 스태미나가 바닥나서 회복 중 (달리기 불가) */
	public boolean exhausted;
	/** 이번 틱에 실제로 달렸는지 (규칙이 판정) */
	public boolean sprinting;
	/** 회복 대기 남은 틱 */
	public int regenDelayTicks;

	/** 체력 (0 이면 쓰러짐) */
	public double health;
	/** 아이템으로 나눠서 채울 남은 회복량 · 초당 양 */
	public double healPending;
	public double healPerSec;

	/** 고른 아이템 (아이템 바 — 클라이언트가 고르고 서버에 알림) · 아이템별 남은 개수 (-1 = 무한) */
	public int itemIndex;
	public int[] itemCounts;

	public HunterState(double maxStamina) {
		CommonConfig cfg = CommonConfig.get();
		this.stamina = maxStamina;
		this.health = cfg.player.maxHealth;
		List<CommonConfig.ItemDef> items = cfg.items;
		this.itemCounts = new int[items.size()];
		for (int i = 0; i < items.size(); i++) {
			itemCounts[i] = items.get(i).count;
		}
	}

	public CommonConfig.ItemDef selectedItem() {
		List<CommonConfig.ItemDef> items = CommonConfig.get().items;
		return itemIndex >= 0 && itemIndex < items.size() ? items.get(itemIndex) : null;
	}

	/** 남은 개수 (-1 = 무한) */
	public int count(int index) {
		return itemCounts != null && index >= 0 && index < itemCounts.length ? itemCounts[index] : 0;
	}
}
