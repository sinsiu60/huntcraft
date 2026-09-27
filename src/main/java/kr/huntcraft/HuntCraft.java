package kr.huntcraft;

import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.config.ConfigCommand;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.core.vanilla.VanillaPace;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.collision.HitLog;
import kr.huntcraft.collision.HitSystem;
import kr.huntcraft.entity.ColliderTestCommands;
import kr.huntcraft.entity.HuntEntities;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.net.HuntNetwork;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 공용(서버 + 클라이언트) 진입점. 클라이언트 전용 코드는 src/client 의 HuntCraftClient. */
public final class HuntCraft implements ModInitializer {
	public static final String MOD_ID = "huntcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommonConfig.load();
		FixedTick.init();
		VanillaPace.init();
		Defs.load();
		HuntEntities.init();
		HuntNetwork.init();
		HunterServer.init();
		HitSystem.init();
		HitLog.init();
		kr.huntcraft.fx.Shockwaves.init();
		ColliderTestCommands.init();
		kr.huntcraft.monster.MonsterCommands.init();
		kr.huntcraft.monster.CoverageCommands.init();
		kr.huntcraft.arena.ArenaCommands.init();
		kr.huntcraft.progress.Encounter.init();
		ConfigCommand.init();
		LOGGER.info("HuntCraft 로드 - 전투 로직 {}Hz 고정", FixedTick.HZ);
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
