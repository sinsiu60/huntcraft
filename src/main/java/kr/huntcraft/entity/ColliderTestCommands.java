package kr.huntcraft.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import kr.huntcraft.collision.HitSystem;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * 판정 시험 명령 (관리자)
 *   /huntcraft test setup [초당 각도]   앞쪽에 더미 + 막대 두 쌍: 왼쪽 = 스윕 켬, 오른쪽 = 스윕 끔 (비교)
 *   /huntcraft test stats              막대별 바퀴 수 · 명중한 바퀴 수 · 명중 수
 *   /huntcraft test clear              시험 엔티티 제거
 */
public final class ColliderTestCommands {
	/** 막대 id → 명중한 공격 인스턴스들 · 명중 수 */
	private static final Map<Integer, Set<Long>> HIT_INSTANCES = new HashMap<>();
	private static final Map<Integer, Integer> HIT_COUNT = new HashMap<>();

	private ColliderTestCommands() {}

	public static void init() {
		HitSystem.listen(e -> {
			int id = e.attacker().self().getId();
			HIT_INSTANCES.computeIfAbsent(id, k -> new HashSet<>()).add(e.instance());
			HIT_COUNT.merge(id, 1, Integer::sum);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("huntcraft").then(Commands.literal("test")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("setup")
								.executes(c -> setup(c, 2160.0F))
								.then(Commands.argument("degreesPerSecond", FloatArgumentType.floatArg(1.0F, 10000.0F))
										.executes(c -> setup(c, FloatArgumentType.getFloat(c, "degreesPerSecond")))))
						.then(Commands.literal("stats").executes(ColliderTestCommands::stats))
						.then(Commands.literal("clear").executes(c -> {
							int n = clear(c.getSource().getLevel());
							c.getSource().sendSuccess(() -> Component.literal("[HuntCraft] 시험 엔티티 " + n + "개 제거"), false);
							return n;
						})))));
	}

	/** 시험에서도 부름: 기준점 · 앞 방향(yaw) 으로 두 쌍 생성 */
	public static SwingRodEntity[] spawnPairs(ServerLevel level, Vec3 origin, float yaw, float speed) {
		clear(level);
		double rad = Math.toRadians(yaw);
		Vec3 forward = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
		Vec3 right = new Vec3(-Math.cos(rad), 0.0, -Math.sin(rad));
		Vec3 center = origin.add(forward.scale(6.0));
		SwingRodEntity on = spawnPair(level, center.subtract(right.scale(4.0)), yaw, speed, true);
		SwingRodEntity off = spawnPair(level, center.add(right.scale(4.0)), yaw, speed, false);
		return new SwingRodEntity[] {on, off};
	}

	private static SwingRodEntity spawnPair(ServerLevel level, Vec3 dummyPos, float playerYaw, float speed, boolean sweep) {
		TrainingDummyEntity dummy = HuntEntities.TRAINING_DUMMY.create(level, EntitySpawnReason.COMMAND);
		// 플레이어를 바라보게
		dummy.snapTo(dummyPos.x, dummyPos.y, dummyPos.z, playerYaw + 180.0F, 0.0F);
		dummy.startClock();
		level.addFreshEntity(dummy);
		SwingRodEntity rod = HuntEntities.SWING_ROD.create(level, EntitySpawnReason.COMMAND);
		rod.snapTo(dummyPos.x + 2.0, dummyPos.y, dummyPos.z, 0.0F, 0.0F);
		rod.configure(speed, sweep);
		rod.startClock();
		level.addFreshEntity(rod);
		HIT_INSTANCES.remove(rod.getId());
		HIT_COUNT.remove(rod.getId());
		return rod;
	}

	public static int clear(ServerLevel level) {
		int n = 0;
		for (Entity e : level.getAllEntities()) {
			if (e instanceof ColliderTestEntity) {
				e.discard();
				n++;
			}
		}
		return n;
	}

	/** 막대 한 개 결과: {바퀴 수, 명중한 바퀴 수, 명중 수} */
	public static int[] result(SwingRodEntity rod) {
		// 다 돈 바퀴만 셈 (인스턴스 번호 k = k 번째 바퀴)
		int turns = (int) Math.floor(rod.angle(rod.animTime(0.0F)) / 360.0);
		int hitTurns = (int) HIT_INSTANCES.getOrDefault(rod.getId(), Set.of()).stream().filter(i -> i <= turns).count();
		return new int[] {turns, hitTurns, HIT_COUNT.getOrDefault(rod.getId(), 0)};
	}

	private static int setup(CommandContext<CommandSourceStack> c, float speed) {
		CommandSourceStack src = c.getSource();
		spawnPairs(src.getLevel(), src.getPosition(), src.getRotation().y, speed);
		src.sendSuccess(() -> Component.literal(String.format(java.util.Locale.ROOT,
				"[HuntCraft] 시험 막대 초당 %.0f° (한 스텝 %.1f°) — 왼쪽: 스윕 켬 · 오른쪽: 스윕 끔. F7 로 판정 도형, /huntcraft test stats",
				speed, speed / 60.0)), false);
		return 1;
	}

	private static int stats(CommandContext<CommandSourceStack> c) {
		int n = 0;
		for (Entity e : c.getSource().getLevel().getAllEntities()) {
			if (e instanceof SwingRodEntity rod) {
				int[] r = result(rod);
				String line = String.format(java.util.Locale.ROOT, "[HuntCraft] 막대#%d (스윕 %s): %d바퀴 중 %d바퀴 명중 (%.0f%%) · 명중 %d회",
						rod.getId(), rod.sweepEnabled() ? "켬" : "끔", r[0], r[1], r[0] == 0 ? 0.0 : 100.0 * r[1] / r[0], r[2]);
				c.getSource().sendSuccess(() -> Component.literal(line), false);
				n++;
			}
		}
		return n;
	}
}
