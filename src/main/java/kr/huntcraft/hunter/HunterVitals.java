package kr.huntcraft.hunter;

import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;

/**
 * 체력 · 아이템 규칙 (서버 권한 — 클라이언트는 동기화 값을 HUD 에 그림).
 *   피격: 체력 -= 대미지
 *   아이템: heal 은 healSeconds 동안 나눠 채움 (최대 체력까지)
 */
public final class HunterVitals {
	private HunterVitals() {}

	public static void tick(HunterState s) {
		double max = CommonConfig.get().player.maxHealth;
		if (s.healPending > 0.0) {
			double step = Math.min(s.healPending, s.healPerSec * FixedTick.DT);
			s.healPending -= step;
			s.health = Math.min(max, s.health + step);
		}
	}

	/** 보스에게 맞음 */
	public static void damaged(HunterState s, double damage) {
		s.health = Math.max(0.0, s.health - damage);
		s.healPending = 0.0;
	}

	/** 아이템 효과 (사용 모션의 효과 프레임에서). 쓸 수 없으면 false */
	public static boolean useItem(HunterState s, int index) {
		java.util.List<CommonConfig.ItemDef> items = CommonConfig.get().items;
		if (index < 0 || index >= items.size() || s.count(index) == 0) {
			return false;
		}
		CommonConfig.ItemDef d = items.get(index);
		if (d.heal > 0.0) {
			s.healPending += d.heal;
			s.healPerSec = d.heal / Math.max(FixedTick.DT, d.healSeconds);
		}
		if (s.itemCounts[index] > 0) {
			s.itemCounts[index]--;
		}
		return true;
	}
}
