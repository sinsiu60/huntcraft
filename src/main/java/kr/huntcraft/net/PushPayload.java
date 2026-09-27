package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 밀어내기 외력 — 수평 방향 (단위벡터) · 총 거리 (블록) · 길이 (초). 헌터 이동은 클라이언트가 계산하므로 여기서 더함 */
public record PushPayload(float dirX, float dirZ, float distance, float seconds) implements CustomPacketPayload {
	public static final Type<PushPayload> TYPE = new Type<>(HuntCraft.id("push"));
	public static final StreamCodec<ByteBuf, PushPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, PushPayload::dirX,
			ByteBufCodecs.FLOAT, PushPayload::dirZ,
			ByteBufCodecs.FLOAT, PushPayload::distance,
			ByteBufCodecs.FLOAT, PushPayload::seconds,
			PushPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
