package kr.huntcraft.net;

import kr.huntcraft.hunter.HunterServer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** 패킷 등록 (공용). 클라이언트 쪽 수신 처리는 client/net/ClientNetwork. */
public final class HuntNetwork {
	private HuntNetwork() {}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(IntentPayload.TYPE, IntentPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(CombatInputPayload.TYPE, CombatInputPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ItemSelectPayload.TYPE, ItemSelectPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(InteractPayload.TYPE, InteractPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BannerPayload.TYPE, BannerPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(BossBarPayload.TYPE, BossBarPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ArenaSyncPayload.TYPE, ArenaSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CombatSyncPayload.TYPE, CombatSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HunterSyncPayload.TYPE, HunterSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ConfigSyncPayload.TYPE, ConfigSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DefsSyncPayload.TYPE, DefsSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(HitDebugPayload.TYPE, HitDebugPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ShockwavePayload.TYPE, ShockwavePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CameraShakePayload.TYPE, CameraShakePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(CoveragePayload.TYPE, CoveragePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PushPayload.TYPE, PushPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(IntentPayload.TYPE,
				(msg, ctx) -> ctx.server().execute(() -> HunterServer.onIntent(ctx.player(), msg)));
		ServerPlayNetworking.registerGlobalReceiver(CombatInputPayload.TYPE,
				(msg, ctx) -> ctx.server().execute(() -> HunterServer.onCombatInput(ctx.player(), msg)));
		ServerPlayNetworking.registerGlobalReceiver(InteractPayload.TYPE,
				(msg, ctx) -> ctx.server().execute(() -> HunterServer.onInteract(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(ItemSelectPayload.TYPE,
				(msg, ctx) -> ctx.server().execute(() -> HunterServer.onItemSelect(ctx.player(), msg.index())));
	}
}
