package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;

/** 서버 → 클라이언트: 충격파 시작 (블록이 솟았다 내려앉는 연출 — 서버 판정과 같은 앞머리 식) */
public record ShockwavePayload(double x, double y, double z, float yaw, float angle, float range, float speed, float width, float height)
		implements CustomPacketPayload {
	public static final Type<ShockwavePayload> TYPE = new Type<>(HuntCraft.id("shockwave"));
	public static final StreamCodec<ByteBuf, ShockwavePayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeDouble(p.x);
				buf.writeDouble(p.y);
				buf.writeDouble(p.z);
				buf.writeFloat(p.yaw);
				buf.writeFloat(p.angle);
				buf.writeFloat(p.range);
				buf.writeFloat(p.speed);
				buf.writeFloat(p.width);
				buf.writeFloat(p.height);
			},
			buf -> new ShockwavePayload(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readFloat(), buf.readFloat(), buf.readFloat()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
