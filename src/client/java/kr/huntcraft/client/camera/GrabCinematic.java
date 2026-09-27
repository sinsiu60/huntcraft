package kr.huntcraft.client.camera;

import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.monster.ActionFx;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 보스에게 잡혔을 때 카메라 연출 — 내 헌터가 "held" 모션인 동안, 헌터 어깨 너머로 나를 쥔 보스의 얼굴 (행동 hold 의 stare) 을 봄.
 * 천천히 다가가며 (dolly) 들어가고 나올 때는 숄더뷰와 부드럽게 섞음. 값은 ClientConfig.camera.grab*.
 * 보스는 가까이에서 지금 행동에 stare 가 있는 몬스터 (잡힌 헌터 쪽에서는 누가 쥐었는지 따로 받지 않음).
 * 보스 연출 (깨어남 · 페이즈 전환 · 처치): 가까운 보스의 행동에 cinematic 이 있으면 until 프레임까지 같은 구도로 그 자리를 봄 (camera.bossCinematic).
 */
public final class GrabCinematic {
	/** 연출 카메라 한 장면 + 섞는 비율 (0~1) */
	public record Shot(Vec3 position, float yaw, float pitch, double weight) {}

	private static double weight;
	private static double heldSeconds;
	private static Shot last;

	private GrabCinematic() {}

	/** 시험 · 디버그: 지금 섞는 비율 */
	public static double weight() {
		return weight;
	}

	/** 렌더 프레임마다 (ShoulderCamera.update) — 섞을 게 없으면 null */
	public static Shot update(Minecraft mc, float partial, double dt) {
		ClientConfig.Camera cfg = ClientConfig.get().camera;
		LocalPlayer player = mc.player;
		boolean held = cfg.grabCinematic && "held".equals(ClientHunters.local().motionId());
		Shot shot = null;
		if (held) {
			heldSeconds += dt;
			shot = compose(mc, player, partial, cfg);
		} else {
			// 보스 연출
			Vec3 face = cfg.bossCinematic ? bossFace(mc, player, partial) : null;
			if (face != null) {
				heldSeconds += dt;
				shot = frame(mc, player, partial, cfg, face);
			} else {
				heldSeconds = 0.0;
			}
		}
		if (shot != null) {
			last = shot;
			weight = Math.min(1.0, weight + dt / Math.max(0.01, cfg.grabBlendIn));
		} else {
			weight = Math.max(0.0, weight - dt / Math.max(0.01, cfg.grabBlendOut));
		}
		if (weight <= 0.0 || last == null) {
			return null;
		}
		// 들어갈 때 · 나올 때 모두 부드럽게 (smoothstep)
		double w = weight * weight * (3.0 - 2.0 * weight);
		return new Shot(last.position(), last.yaw(), last.pitch(), w);
	}

	private static Shot compose(Minecraft mc, LocalPlayer player, float partial, ClientConfig.Camera cfg) {
		Vec3 me = player.getPosition(partial).add(0.0, player.getBbHeight() * 0.55, 0.0);
		Vec3 face = null;
		double best = 14.0 * 14.0;
		for (MonsterEntity m : mc.level.getEntitiesOfClass(MonsterEntity.class, new AABB(me, me).inflate(14.0))) {
			MonsterDef.Action a = m.action();
			ActionFx.Hold hold = a == null ? null : a.flow().hold();
			if (hold == null || hold.stareBone() == null || m.frame(partial) >= hold.stareUntil()) {
				continue;
			}
			Vec3 f = m.bonePoint(hold.stareBone(), hold.stareAt(), partial);
			double d = f.distanceToSqr(me);
			if (d < best) {
				best = d;
				face = f;
			}
		}
		if (face == null) {
			return null;
		}
		return frame(mc, player, partial, cfg, face);
	}

	/** 가까운 보스 중 지금 행동에 카메라 연출이 있는 것의 볼 자리 (없으면 null) */
	private static Vec3 bossFace(Minecraft mc, LocalPlayer player, float partial) {
		Vec3 me = player.getPosition(partial);
		for (MonsterEntity m : mc.level.getEntitiesOfClass(MonsterEntity.class, new AABB(me, me).inflate(40.0))) {
			MonsterDef.Action a = m.action();
			ActionFx.Cinematic c = a == null ? null : a.flow().cinematic();
			if (c != null && m.frame(partial) < c.until()) {
				return m.bonePoint(c.bone(), c.at(), partial);
			}
		}
		return null;
	}

	/** 헌터 어깨 너머로 face 를 보는 구도 */
	private static Shot frame(Minecraft mc, LocalPlayer player, float partial, ClientConfig.Camera cfg, Vec3 face) {
		Vec3 me = player.getPosition(partial).add(0.0, player.getBbHeight() * 0.55, 0.0);
		// 얼굴 → 헌터 방향 (수평), 그 반대쪽 뒤 · 옆 · 위에 카메라
		Vec3 away = new Vec3(me.x - face.x, 0.0, me.z - face.z);
		if (away.lengthSqr() < 1.0E-4) {
			away = new Vec3(0.0, 0.0, 1.0);
		}
		away = away.normalize();
		Vec3 side = new Vec3(-away.z, 0.0, away.x);
		double u = Math.min(1.0, heldSeconds / Math.max(0.01, cfg.grabDolly));
		u = u * u * (3.0 - 2.0 * u);
		double back = cfg.grabBack + (cfg.grabBackEnd - cfg.grabBack) * u;
		Vec3 wanted = me.add(away.scale(back)).add(side.scale(cfg.grabSide)).add(0.0, cfg.grabUp, 0.0);
		// 벽 · 땅에 막히면 헌터 쪽으로 당김
		HitResult hit = mc.level.clip(new ClipContext(me, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
		Vec3 pos = wanted;
		if (hit.getType() != HitResult.Type.MISS) {
			Vec3 dir = wanted.subtract(me);
			double len = dir.length();
			double allowed = Math.max(0.3, hit.getLocation().distanceTo(me) - cfg.collisionPadding);
			pos = me.add(dir.scale(allowed / len));
		}
		Vec3 look = me.lerp(face, cfg.grabLookBias).subtract(pos);
		float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z));
		float pitch = (float) -Math.toDegrees(Math.atan2(look.y, Math.hypot(look.x, look.z)));
		return new Shot(pos, yaw, pitch, 1.0);
	}
}
