package kr.huntcraft.combat;

import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.monster.MonsterEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 치명 일격 조건 (서버 · 클라이언트 똑같이) — 치명 구간 (패링당함 · 자세 붕괴) 인 보스 앞 가까이에 서 있어야 함.
 *   거리: 보스 몸 가장자리에서 player.critRange 안 · 방향: 보스 정면 ± player.critAngle 안 (등 뒤 치명은 대형 보스라 없음)
 */
public final class CriticalRules {
	private CriticalRules() {}

	/** 치명 일격을 넣을 수 있는 보스 (없으면 null) */
	public static MonsterEntity target(Player p) {
		CommonConfig.Player cfg = CommonConfig.get().player;
		MonsterEntity best = null;
		double bestGap = Double.MAX_VALUE;
		for (MonsterEntity m : p.level().getEntitiesOfClass(MonsterEntity.class, new AABB(p.blockPosition()).inflate(16.0))) {
			if (!m.critOpen()) {
				continue;
			}
			double dx = p.getX() - m.getX();
			double dz = p.getZ() - m.getZ();
			double gap = Math.hypot(dx, dz) - m.getBbWidth() * 0.5;
			float toPlayer = (float) Math.toDegrees(Math.atan2(-dx, dz));
			if (gap > cfg.critRange || Math.abs(Mth.wrapDegrees(toPlayer - m.getYRot())) > cfg.critAngle) {
				continue;
			}
			if (gap < bestGap) {
				bestGap = gap;
				best = m;
			}
		}
		return best;
	}

	/** 헌터가 보스를 볼 yaw */
	public static float yawTo(Player p, MonsterEntity m) {
		return (float) Math.toDegrees(Math.atan2(-(m.getX() - p.getX()), m.getZ() - p.getZ()));
	}
}
