package kr.huntcraft.client.movement;

import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.Geometry;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.monster.MonsterEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 헌터 ↔ 몬스터 부드러운 밀어내기 (내 헌터, 클라이언트 — 플레이어 이동은 클라이언트가 계산).
 * 헌터 몸(세로 캡슐)이 몬스터 피격 판정 도형에 파고든 깊이만큼, 접촉점에서 헌터 쪽으로 수평으로 밀어냄.
 * 질량 비율: 몬스터가 훨씬 무거워 헌터만 밀림 (player.pushShare = 헌터가 가져가는 몫). 한 번에 다 밀지 않고 매 틱 일부씩 → 부드러움.
 */
public final class MonsterPush {
	private MonsterPush() {}

	/** 이번 틱에 더할 수평 이동 (블록) */
	public static Vec3 push(LocalPlayer p) {
		CommonConfig.Player cfg = CommonConfig.get().player;
		Vec3 feet = p.position();
		Shape.Capsule body = new Shape.Capsule(feet.add(0.0, 0.35, 0.0), feet.add(0.0, 1.45, 0.0), 0.35);
		double px = 0.0;
		double pz = 0.0;
		for (MonsterEntity m : p.level().getEntitiesOfClass(MonsterEntity.class, new AABB(feet, feet).inflate(16.0))) {
			ColliderDef def = m.colliderDef();
			Transform[] bones = def == null ? null : ColliderInstance.solve(m, 0.0F, null);
			if (bones == null) {
				continue;
			}
			for (ColliderDef.Box u : def.hurtboxes) {
				Geometry.Contact c = Geometry.test(body, u.shape().toWorld(bones[u.bone()]));
				if (c == null) {
					continue;
				}
				double dx = feet.x - c.point().x;
				double dz = feet.z - c.point().z;
				double len = Math.hypot(dx, dz);
				if (len < 1.0E-4) {
					// 정중앙이면 몬스터 중심에서 바깥으로
					dx = feet.x - m.getX();
					dz = feet.z - m.getZ();
					len = Math.max(1.0E-4, Math.hypot(dx, dz));
				}
				px += dx / len * c.depth();
				pz += dz / len * c.depth();
			}
		}
		double k = cfg.pushShare * cfg.pushStiffness;
		return new Vec3(px * k, 0.0, pz * k);
	}
}
