package kr.huntcraft.core.vanilla;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.core.FixedTick;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 바닐라를 1초 20틱 속도로 되돌리는 단일 진입점. 전투 로직은 60Hz 그대로, 바닐라 시스템만 체감 속도를 맞춥니다.
 *
 * 두 가지 방식을 씁니다.
 *   1) 솎아내기 — 틱마다 도는 바닐라 처리를 3틱에 1번만 실행 (정확히 20Hz 로 돎, 보간이 필요 없는 서버 처리에만)
 *        날씨 주기 · 무작위 틱(작물 · 불 · 잎) · 강수/번개 · 자연 스폰 · 특수 스폰(팬텀 · 순찰대 · 상인)
 *        허기/자연 회복 (FoodData) · 상태 효과 지속시간/발동 (tickEffects)
 *   2) 비율 보정 — 매 틱 움직여야 부드러운 것은 틱당 양을 1/3 로
 *        낮밤 (월드 시계 배율 ÷3, 클라이언트 예측도 같은 배율) · 이동 물리 ({@link MovementScale}) · 걷기 팔다리 흔들림
 *
 * 게임룰은 건드리지 않습니다 (월드 파일에 흔적을 남기지 않음).
 */
public final class VanillaPace {
	/** 60Hz / 20Hz */
	public static final int RATIO = FixedTick.HZ / 20;

	private VanillaPace() {}

	/** 솎아내기: 이 틱에 바닐라 처리를 돌릴지 (counter 는 틱마다 1씩 느는 값) */
	public static boolean runs(long counter) {
		return Math.floorMod(counter, RATIO) == 0;
	}

	public static boolean runs(ServerLevel level) {
		return runs(level.getGameTime());
	}

	public static void init() {
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> apply(entity));
		ServerPlayerEvents.JOIN.register(VanillaPace::apply);
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> apply(newPlayer));
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity e : level.getAllEntities()) {
					apply(e);
				}
			}
			HuntCraft.LOGGER.info("바닐라 속도 보정 x1/{} (이동 {})", RATIO, MovementScale.factors());
		});
	}

	private static void apply(Entity e) {
		if (e instanceof LivingEntity le) {
			MovementScale.apply(le);
		}
	}
}
