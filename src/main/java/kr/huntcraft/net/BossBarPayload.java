package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 보스 체력바 — entityId (-1 = 숨김) · 이름 (체력 · 자세는 엔티티 동기화 값을 그대로 읽음) */
public record BossBarPayload(int entityId, String name) implements CustomPacketPayload {
	public static final Type<BossBarPayload> TYPE = new Type<>(HuntCraft.id("boss_bar"));
	public static final StreamCodec<ByteBuf, BossBarPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, BossBarPayload::entityId,
			ByteBufCodecs.STRING_UTF8, BossBarPayload::name,
			BossBarPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
