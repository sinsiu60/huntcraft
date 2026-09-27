package kr.huntcraft.client;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.debug.DebugOverlay;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.client.debug.ShapeDebugRenderer;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.entity.HuntEntities;
import kr.huntcraft.net.DefsSyncPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.fabricmc.loader.api.FabricLoader;

/** 클라이언트 전용 진입점 (카메라 · 입력 · HUD · 렌더). */
public final class HuntCraftClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfig.load();
		HuntKeys.register();
		ShoulderCamera.register();
		HunterMovement.register();
		kr.huntcraft.client.combat.ClientHunters.register();
		kr.huntcraft.client.render.KatanaRenderer.register();
		kr.huntcraft.client.render.BladeTrail.register();
		kr.huntcraft.client.render.ShockwaveRenderer.register();
		kr.huntcraft.client.sound.SoundDuck.register();
		kr.huntcraft.client.sound.BossMusic.register();
		kr.huntcraft.client.debug.CoverageRenderer.register();
		kr.huntcraft.client.movement.PushForce.register();
		kr.huntcraft.client.camera.LockOn.register();
		kr.huntcraft.client.hud.HuntHud.register();
		kr.huntcraft.client.hud.ClientProgress.register();
		DebugOverlay.register();
		ShapeDebugRenderer.register();
		EntityRendererRegistry.register(HuntEntities.TRAINING_DUMMY, NoopRenderer::new);
		EntityRendererRegistry.register(HuntEntities.SWING_ROD, NoopRenderer::new);
		EntityRendererRegistry.register(HuntEntities.RUBBLE, kr.huntcraft.client.render.RubbleRenderer::new);
		EntityRendererRegistry.register(HuntEntities.BOSS, kr.huntcraft.client.render.MonsterRenderer::new);
		// 멀티 서버의 판정 데이터 (싱글플레이는 같은 프로세스라 서버가 읽은 것을 그대로 씀)
		ClientPlayNetworking.registerGlobalReceiver(DefsSyncPayload.TYPE, (msg, ctx) -> {
			if (!ctx.client().hasSingleplayerServer()) {
				Defs.apply(msg.files());
			}
		});
		HuntCraft.LOGGER.info("HuntCraft 클라이언트 로드 - GeckoLib {} · PlayerAnimationLib {}",
				version("geckolib"), version("player_animation_library"));
	}

	public static String version(String modId) {
		return FabricLoader.getInstance().getModContainer(modId)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("없음");
	}
}
