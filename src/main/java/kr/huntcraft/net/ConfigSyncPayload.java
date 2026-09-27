package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 서버의 common.json (이동 속도 · 스태미나 같은 판정 수치를 서버 값으로 통일). */
public record ConfigSyncPayload(String json) implements CustomPacketPayload {
	public static final Type<ConfigSyncPayload> TYPE = new Type<>(HuntCraft.id("config_sync"));
	public static final StreamCodec<ByteBuf, ConfigSyncPayload> CODEC =
			ByteBufCodecs.stringUtf8(1 << 20).map(ConfigSyncPayload::new, ConfigSyncPayload::json);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
