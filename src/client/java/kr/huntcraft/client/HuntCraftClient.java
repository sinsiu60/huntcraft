package kr.huntcraft.client;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.debug.DebugOverlay;
import kr.huntcraft.client.input.HuntKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** 클라이언트 전용 진입점 (카메라 · 입력 · HUD · 렌더). */
public final class HuntCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		HuntKeys.register();
		DebugOverlay.register();
		HuntCraft.LOGGER.info("HuntCraft 클라이언트 로드 - GeckoLib {} · PlayerAnimationLib {}",
				version("geckolib"), version("player_animation_library"));
	}

	public static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("없음");
	}
}
