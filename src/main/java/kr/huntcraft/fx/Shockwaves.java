package kr.huntcraft.fx;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import kr.huntcraft.combat.DamageCalc;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.ActionFx;
import kr.huntcraft.net.ShockwavePayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 땅을 타고 퍼지는 충격파 (서버 권한) — 부채꼴 앞머리가 초당 speed 블록으로 나아가며, 앞머리가 지나가는 헌터를 한 번씩 때림.
 *   맞는 조건: 부채꼴 각도 안 · 앞머리가 이번 스텝에 헌터 자리를 지남 · 발이 땅(파도 시작 높이)에서 jumpClear 이하 · 무적 아님
 *   피하는 법: 구르기 무적으로 앞머리를 흘려보내거나, 점프로 넘기 (소울류 — "읽을 수 있는 공격")
 * 블록이 솟았다 내려앉는 모습은 클라이언트가 같은 값(ShockwavePayload)으로 그림 — 판정과 연출이 같은 앞머리를 씀.
 */
public final class Shockwaves {
	private static final class Wave {
		final ServerLevel level;
		final Vec3 origin;
		final float yaw;
		final ActionFx.Shockwave spec;
		final long startTick;
		final Set<UUID> hit = new HashSet<>();
		double prevRadius;

		Wave(ServerLevel level, Vec3 origin, float yaw, ActionFx.Shockwave spec, long startTick) {
			this.level = level;
			this.origin = origin;
			this.yaw = yaw;
			this.spec = spec;
			this.startTick = startTick;
		}
	}

	private static final List<Wave> WAVES = new ArrayList<>();
	/** 시험: 지금까지의 명중 · 피함 (구르기 무적 · 점프) 수 */
	private static int hits;
	private static int evaded;
	/** 디버그 · 시험: 마지막 충격파 시작점 */
	private static Vec3 lastOrigin = Vec3.ZERO;

	private Shockwaves() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> tick());
	}

	public static int hits() {
		return hits;
	}

	public static int evaded() {
		return evaded;
	}

	public static Vec3 lastOrigin() {
		return lastOrigin;
	}

	/** 충격파 시작 — origin (땅 높이), yaw = 부채꼴 가운데 방향 */
	public static void start(ServerLevel level, Vec3 origin, float yaw, ActionFx.Shockwave spec) {
		WAVES.add(new Wave(level, origin, yaw, spec, level.getGameTime()));
		lastOrigin = origin;
		ShockwavePayload msg = new ShockwavePayload(origin.x, origin.y, origin.z, yaw, (float) spec.angle(), (float) spec.range(),
				(float) spec.speed(), (float) spec.width(), (float) spec.height());
		for (ServerPlayer p : level.players()) {
			if (p.distanceToSqr(origin) < (spec.range() + 48.0) * (spec.range() + 48.0) && ServerPlayNetworking.canSend(p, ShockwavePayload.TYPE)) {
				ServerPlayNetworking.send(p, msg);
			}
		}
	}

	private static void tick() {
		Iterator<Wave> it = WAVES.iterator();
		while (it.hasNext()) {
			Wave w = it.next();
			double t = (w.level.getGameTime() - w.startTick) * FixedTick.DT;
			double radius = w.spec.speed() * t;
			for (ServerPlayer p : w.level.players()) {
				if (p.isSpectator() || !p.isAlive() || w.hit.contains(p.getUUID())) {
					continue;
				}
				double dx = p.getX() - w.origin.x;
				double dz = p.getZ() - w.origin.z;
				double d = Math.hypot(dx, dz);
				// 앞머리가 이번 스텝에 몸(반지름 0.3)을 지났는지
				if (d + 0.3 < w.prevRadius || d - 0.3 > radius || d > w.spec.range()) {
					continue;
				}
				float dir = (float) Math.toDegrees(Math.atan2(-dx, dz));
				if (Math.abs(Mth.wrapDegrees(dir - w.yaw)) > w.spec.angle() / 2.0) {
					continue;
				}
				w.hit.add(p.getUUID());
				HunterBody body = HunterServer.body(p);
				boolean airborne = p.getY() - w.origin.y > w.spec.jumpClear();
				boolean invuln = body != null && !body.hurtboxesActive(body.animTime(0.0F));
				if (airborne || invuln) {
					evaded++;
					continue;
				}
				hits++;
				HunterServer.applyBossHit(p, new DamageCalc.MonsterHit(w.spec.damage(), w.spec.reaction(), w.origin));
			}
			w.prevRadius = radius;
			if (radius > w.spec.range() + w.spec.width()) {
				it.remove();
			}
		}
	}
}
