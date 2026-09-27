package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: 상호작용 키 (E) — 서버가 가까운 대상 (안개 문 · 체크포인트) 을 고름 */
public record InteractPayload() implements CustomPacketPayload {
	public static final InteractPayload INSTANCE = new InteractPayload();
	public static final Type<InteractPayload> TYPE = new Type<>(HuntCraft.id("interact"));
	public static final StreamCodec<ByteBuf, InteractPayload> CODEC = StreamCodec.unit(INSTANCE);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
