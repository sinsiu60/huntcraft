package kr.huntcraft.config;

import kr.huntcraft.core.FixedTick;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /huntcraft reload — common.json 다시 읽기 (게임을 끄지 않고 튜닝). /huntcraft info — 상태 확인. */
public final class ConfigCommand {
	private ConfigCommand() {}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("huntcraft")
						.then(Commands.literal("reload")
								.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
								.executes(c -> {
									CommonConfig.load();
									kr.huntcraft.collision.Defs.load();
									kr.huntcraft.hunter.HunterServer.broadcastConfig(c.getSource().getServer());
									c.getSource().sendSuccess(() -> Component.literal("[HuntCraft] common.json · 판정 데이터 다시 읽음"), true);
									return 1;
								}))
						.then(Commands.literal("info").executes(c -> {
							float rate = c.getSource().getServer().tickRateManager().tickrate();
							c.getSource().sendSuccess(() -> Component.literal("[HuntCraft] 로직 " + FixedTick.HZ
									+ "Hz · 서버 틱레이트 " + rate + " · 설정 " + ConfigIO.dir()), false);
							return 1;
						}))));
	}
}
