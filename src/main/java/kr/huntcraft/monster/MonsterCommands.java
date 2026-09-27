package kr.huntcraft.monster;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.entity.HuntEntities;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.phys.Vec3;

/**
 * 몬스터 명령 (관리자)
 *   /huntcraft monster spawn [보스] [거리]  앞쪽에 보스 (defs/monsters 의 id, 기본 boss.defaultId — 나를 등지고 섬)
 *   /huntcraft monster act <행동>        가까운 몬스터에 행동 강제 (시험 · 튜닝)
 *   /huntcraft monster hp <비율 0~1>     가까운 몬스터 체력 (분노 · 토벌 확인)
 *   /huntcraft monster ai <on|off>       가까운 몬스터 행동 선택 (끄면 act 로 강제한 행동만)
 *   /huntcraft monster clear             전부 제거
 */
public final class MonsterCommands {
	private MonsterCommands() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("huntcraft").then(Commands.literal("monster")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("spawn")
								.executes(c -> spawnCmd(c, kr.huntcraft.config.CommonConfig.get().boss.defaultId, 14.0))
								.then(Commands.argument("boss", StringArgumentType.word())
										.suggests((c, b) -> {
											Defs.monsterIds().forEach(b::suggest);
											return b.buildFuture();
										})
										.executes(c -> spawnCmd(c, StringArgumentType.getString(c, "boss"), 14.0))
										.then(Commands.argument("distance", DoubleArgumentType.doubleArg(4.0, 64.0))
												.executes(c -> spawnCmd(c, StringArgumentType.getString(c, "boss"),
														DoubleArgumentType.getDouble(c, "distance"))))))
						.then(Commands.literal("act").then(Commands.argument("action", StringArgumentType.word())
								.suggests((c, b) -> {
									MonsterEntity near = nearest(c.getSource());
									MonsterDef d = near == null ? null : near.def();
									if (d != null) {
										d.actions.keySet().forEach(b::suggest);
									}
									return b.buildFuture();
								})
								.executes(c -> {
									MonsterEntity m = nearest(c.getSource());
									if (m == null) {
										return 0;
									}
									m.startAction(StringArgumentType.getString(c, "action"));
									return 1;
								})))
						.then(Commands.literal("hp").then(Commands.argument("ratio", DoubleArgumentType.doubleArg(0.0, 1.0))
								.executes(c -> {
									MonsterEntity m = nearest(c.getSource());
									if (m == null) {
										return 0;
									}
									m.setHp((float) (m.maxHp() * DoubleArgumentType.getDouble(c, "ratio")));
									return 1;
								})))
						.then(Commands.literal("clear").executes(c -> {
							int n = 0;
							for (Entity e : c.getSource().getLevel().getAllEntities()) {
								if (e instanceof MonsterEntity) {
									e.discard();
									n++;
								}
							}
							int count = n;
							c.getSource().sendSuccess(() -> Component.literal("[HuntCraft] 몬스터 " + count + "마리 제거"), false);
							return n;
						})))));
	}

	/** origin 에서 yaw 방향 distance 앞에, 등을 보이게 (시험에서도 씀) */
	public static MonsterEntity spawn(ServerLevel level, String defId, Vec3 origin, float yaw, double distance) {
		double rad = Math.toRadians(yaw);
		Vec3 at = origin.add(-Math.sin(rad) * distance, 0.0, Math.cos(rad) * distance);
		MonsterEntity m = HuntEntities.BOSS.create(level, EntitySpawnReason.COMMAND);
		m.setDef(defId);
		m.snapTo(at.x, at.y, at.z, yaw, 0.0F);
		m.setYHeadRot(yaw);
		m.setYBodyRot(yaw);
		level.addFreshEntity(m);
		return m;
	}

	private static int spawnCmd(CommandContext<CommandSourceStack> c, String defId, double distance) {
		CommandSourceStack src = c.getSource();
		MonsterDef d = Defs.monster(defId);
		if (d == null) {
			src.sendFailure(Component.literal("[HuntCraft] 보스 정의가 없음: " + defId + " (있는 것: " + Defs.monsterIds() + ")"));
			return 0;
		}
		spawn(src.getLevel(), defId, src.getPosition(), src.getRotation().y, distance);
		src.sendSuccess(() -> Component.literal("[HuntCraft] " + d.displayName + " 등장 — F7 판정 도형 · F8 행동 정보"), false);
		return 1;
	}

	private static int setAi(CommandSourceStack src, boolean on) {
		MonsterEntity m = nearest(src);
		if (m == null) {
			return 0;
		}
		m.setAi(on);
		src.sendSuccess(() -> Component.literal("[HuntCraft] 몬스터 행동 선택 " + (on ? "켬" : "끔")), false);
		return 1;
	}

	/** 가까운 몬스터 (다른 명령도 씀) */
	public static MonsterEntity nearestMonster(CommandSourceStack src) {
		return nearest(src);
	}

	private static MonsterEntity nearest(CommandSourceStack src) {
		MonsterEntity best = null;
		double bestD = Double.MAX_VALUE;
		for (Entity e : src.getLevel().getAllEntities()) {
			if (e instanceof MonsterEntity m && m.distanceToSqr(src.getPosition()) < bestD) {
				bestD = m.distanceToSqr(src.getPosition());
				best = m;
			}
		}
		if (best == null) {
			src.sendFailure(Component.literal("[HuntCraft] 몬스터가 없음"));
		}
		return best;
	}
}
