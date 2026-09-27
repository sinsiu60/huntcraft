package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: 이동 의도 (바뀔 때만 보냄). 서버는 이걸로 스태미나 규칙을 돌립니다. */
public record IntentPayload(boolean sprintHeld, boolean moving) implements CustomPacketPayload {
	public static final Type<IntentPayload> TYPE = new Type<>(HuntCraft.id("intent"));
	public static final StreamCodec<ByteBuf, IntentPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, IntentPayload::sprintHeld,
			ByteBufCodecs.BOOL, IntentPayload::moving,
			IntentPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
