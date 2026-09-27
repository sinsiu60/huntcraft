package kr.huntcraft.arena;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 전장 명령 (관리자)
 *   /huntcraft arena build [seed]  내 발밑을 가운데로 부서진 마을 전장 생성 (값은 common.json 의 arena)
 *   /huntcraft arena enter          문 밖 체크포인트로 (전장 쪽을 봄)
 *   /huntcraft arena boss           보스 자리에 보스 (boss.defaultId, 문 쪽을 봄)
 *   /huntcraft arena info           저장된 자리
 *   /huntcraft arena reset          필드 보스 상태 초기화 (보스 다시 앉힘 · 안개 문 다시 닫음 · 처치 · 첫 등장 기록 지움)
 */
public final class ArenaCommands {
	private ArenaCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("huntcraft").then(Commands.literal("arena")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("build")
								.executes(c -> build(c, CommonConfig.get().arena.seed))
								.then(Commands.argument("seed", LongArgumentType.longArg())
										.executes(c -> build(c, LongArgumentType.getLong(c, "seed")))))
						.then(Commands.literal("enter").executes(ArenaCommands::enter))
						.then(Commands.literal("boss").executes(ArenaCommands::boss))
						.then(Commands.literal("info").executes(ArenaCommands::info))
						.then(Commands.literal("reset").executes(ArenaCommands::reset)))));
	}

	private static int build(CommandContext<CommandSourceStack> c, long seed) {
		CommandSourceStack src = c.getSource();
		BlockPos center = BlockPos.containing(src.getPosition());
		long t0 = System.nanoTime();
		ArenaBuilder.Layout l = ArenaBuilder.build(src.getLevel(), center, CommonConfig.get().arena, seed);
		ArenaStore.save(src.getServer(), l);
		kr.huntcraft.progress.Encounter.arenaBuilt(src.getServer());
		long ms = (System.nanoTime() - t0) / 1_000_000L;
		src.sendSuccess(() -> Component.literal(String.format("[HuntCraft] 전장 생성 (seed %d, %dms) — 문 %s · 체크포인트 %s · 보스 %s",
				seed, ms, l.gate().toShortString(), l.checkpoint().toShortString(), l.bossSpawn().toShortString())), true);
		return 1;
	}

	private static ArenaBuilder.Layout layout(CommandSourceStack src) {
		ArenaBuilder.Layout l = ArenaStore.load(src.getServer());
		if (l == null) {
			src.sendFailure(Component.literal("[HuntCraft] 이 월드에 전장이 없음 — /huntcraft arena build"));
		}
		return l;
	}

	private static int enter(CommandContext<CommandSourceStack> c) {
		CommandSourceStack src = c.getSource();
		ArenaBuilder.Layout l = layout(src);
		if (l == null || !(src.getEntity() instanceof ServerPlayer p)) {
			return 0;
		}
		BlockPos cp = l.checkpoint();
		// 체크포인트에서 전장 (북쪽, -Z) 을 봄
		p.teleportTo(src.getLevel(), cp.getX() + 0.5, cp.getY(), cp.getZ() + 0.5, java.util.Set.of(), 180.0F, 0.0F, true);
		return 1;
	}

	private static int boss(CommandContext<CommandSourceStack> c) {
		CommandSourceStack src = c.getSource();
		ArenaBuilder.Layout l = layout(src);
		if (l == null) {
			return 0;
		}
		BlockPos b = l.bossSpawn();
		MonsterEntity m = MonsterCommands.spawn(src.getLevel(), CommonConfig.get().boss.defaultId, new Vec3(b.getX() + 0.5, b.getY(), b.getZ() + 0.5),
				l.bossYaw(), 0.0);
		src.sendSuccess(() -> Component.literal("[HuntCraft] " + (m.def() == null ? "보스" : m.def().displayName) + " 등장"), true);
		return 1;
	}

	private static int reset(CommandContext<CommandSourceStack> c) {
		CommandSourceStack src = c.getSource();
		if (layout(src) == null) {
			return 0;
		}
		kr.huntcraft.progress.Encounter.resetArena(src.getServer());
		src.sendSuccess(() -> Component.literal("[HuntCraft] 필드 보스 상태 초기화 — 보스가 다시 전원이 꺼진 채 앉아 있고 안개 문이 닫힘"), true);
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> c) {
		CommandSourceStack src = c.getSource();
		ArenaBuilder.Layout l = layout(src);
		if (l == null) {
			return 0;
		}
		src.sendSuccess(() -> Component.literal(String.format("[HuntCraft] 전장 가운데 %s · 반지름 %d (싸움터 %d) · 문 %s · 체크포인트 %s · 보스 %s · seed %d",
				l.center().toShortString(), l.radius(), l.fightRadius(), l.gate().toShortString(), l.checkpoint().toShortString(),
				l.bossSpawn().toShortString(), l.seed())), false);
		return 1;
	}
}
