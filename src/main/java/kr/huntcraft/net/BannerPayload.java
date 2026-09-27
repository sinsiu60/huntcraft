package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 화면 가운데 큰 글씨 연출 — kind: 0 사망 · 1 보스 처치 · 2 휴식 */
public record BannerPayload(int kind) implements CustomPacketPayload {
	public static final int DEATH = 0;
	public static final int VICTORY = 1;
	public static final int REST = 2;
	public static final Type<BannerPayload> TYPE = new Type<>(HuntCraft.id("banner"));
	public static final StreamCodec<ByteBuf, BannerPayload> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, BannerPayload::kind, BannerPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
