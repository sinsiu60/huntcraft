package kr.huntcraft.core;

import kr.huntcraft.HuntCraft;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.ServerTickRateManager;

/**
 * 전투 로직 60Hz 고정 스텝 — 서버 틱레이트 자체를 60 으로 올립니다 (서브스텝 방식이 아님).
 *
 * 서버 틱레이트는 바닐라 패킷(ClientboundTickingStatePacket)으로 클라이언트에 전달되고,
 * 클라이언트는 MinecraftTickRateMixin 으로 같은 속도로 돌아 입력 · 이동 · 위치 전송도 1/60초 단위가 됩니다.
 * 60 은 튜닝 값이 아니라 판정 구조의 전제라서 설정 파일에 두지 않습니다.
 *
 * 바닐라 시스템(낮밤 · 이동 물리 · 날씨 · 스폰 등)은 VanillaPace 가 1초 20틱 속도로 되돌립니다.
 */
public final class FixedTick {
	public static final int HZ = 60;
	public static final double DT = 1.0 / HZ;

	private FixedTick() {}

	/** 초 → 로직 스텝 수 (반올림, 최소 0) */
	public static int ticks(double seconds) {
		return Math.max(0, (int) Math.round(seconds * HZ));
	}

	public static void init() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> server.tickRateManager().setTickRate(HZ));
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ServerTickRateManager m = server.tickRateManager();
			// /tick sprint · freeze · step 은 디버그용으로 허용
			if (Math.round(m.tickrate()) != HZ && !m.isSprinting() && !m.isFrozen()) {
				HuntCraft.LOGGER.warn("틱레이트가 {} 로 바뀌어 {} 로 되돌립니다", m.tickrate(), HZ);
				m.setTickRate(HZ);
			}
		});
	}
}
