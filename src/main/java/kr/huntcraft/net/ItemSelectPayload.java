package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: 아이템 바에서 고른 아이템 번호 (휠). 사용 입력(USE_ITEM)보다 먼저 도착 — 같은 연결이라 순서가 보장됨. */
public record ItemSelectPayload(int index) implements CustomPacketPayload {
	public static final Type<ItemSelectPayload> TYPE = new Type<>(HuntCraft.id("item_select"));
	public static final StreamCodec<ByteBuf, ItemSelectPayload> CODEC = ByteBufCodecs.VAR_INT.map(ItemSelectPayload::new, ItemSelectPayload::index);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
