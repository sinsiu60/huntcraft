package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 전장 안개 문 자리 · 걷혔는지 (상호작용 안내 표시용). has = 전장이 있음 */
public record ArenaSyncPayload(boolean has, BlockPos gate, BlockPos center, boolean defeated) implements CustomPacketPayload {
	public static final Type<ArenaSyncPayload> TYPE = new Type<>(HuntCraft.id("arena_sync"));
	public static final StreamCodec<ByteBuf, ArenaSyncPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, ArenaSyncPayload::has,
			BlockPos.STREAM_CODEC, ArenaSyncPayload::gate,
			BlockPos.STREAM_CODEC, ArenaSyncPayload::center,
			ByteBufCodecs.BOOL, ArenaSyncPayload::defeated,
			ArenaSyncPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
