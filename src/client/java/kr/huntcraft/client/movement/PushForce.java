package kr.huntcraft.client.movement;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import kr.huntcraft.core.FixedTick;
import kr.huntcraft.net.PushPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 밀어내기 외력 (내 헌터) — 보스 공격의 push. 속도를 덮어쓰지 않고 매 틱 이동량에 더함 (모션 · 걷기와 겹침).
 * 감속 곡선: 속도 ∝ e^(-t/τ), τ = 길이/3 → 처음에 세게 밀리고 점점 멎음. 길이 동안 거리의 95% 를 감.
 * 벽에 막히면 (수평 충돌) 그 외력은 거기서 끝 — 추가 경직 없음.
 */
public final class PushForce {
	private static final class Impulse {
		final double dx;
		final double dz;
		final double distance;
		final double tau;
		final double seconds;
		double t;

		Impulse(PushPayload p) {
			this.dx = p.dirX();
			this.dz = p.dirZ();
			this.distance = p.distance();
			this.seconds = Math.max(0.05, p.seconds());
			this.tau = this.seconds / 3.0;
		}
	}

	private static final List<Impulse> ACTIVE = new ArrayList<>();
	private static double traveled;

	private PushForce() {}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(PushPayload.TYPE, (msg, ctx) -> ACTIVE.add(new Impulse(msg)));
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ACTIVE.clear());
	}

	/** 시험: 지금까지 외력으로 민 거리 합 */
	public static double traveled() {
		return traveled;
	}

	public static boolean active() {
		return !ACTIVE.isEmpty();
	}

	/** 이번 틱에 더할 수평 이동 (블록) */
	public static Vec3 step(LocalPlayer p) {
		if (ACTIVE.isEmpty()) {
			return Vec3.ZERO;
		}
		double x = 0.0;
		double z = 0.0;
		boolean blocked = p.horizontalCollision;
		Iterator<Impulse> it = ACTIVE.iterator();
		while (it.hasNext()) {
			Impulse i = it.next();
			double t0 = i.t;
			double t1 = t0 + FixedTick.DT;
			double d = i.distance * (Math.exp(-t0 / i.tau) - Math.exp(-t1 / i.tau));
			i.t = t1;
			// 벽에 막힘: 처음 몇 틱 (밀리기 시작) 은 봐줌 — 서 있던 자리가 벽에 붙어 있어도 옆으로 미끄러질 수 있게
			if (t1 >= i.seconds || (blocked && t0 > 0.1)) {
				it.remove();
				continue;
			}
			x += i.dx * d;
			z += i.dz * d;
			traveled += d;
		}
		return new Vec3(x, 0.0, z);
	}
}
