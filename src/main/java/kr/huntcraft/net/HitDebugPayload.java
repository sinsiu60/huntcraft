package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 명중 1건 — 디버그 렌더(부위 깜빡임 · 접촉점 · 명중 수), 대미지 표시, 공격자 본인의 히트스톱.
 * damage &lt; 0 = 대미지 없는 명중 (시험 막대 등).
 */
public record HitDebugPayload(int attackerId, int targetId, String hurtbox, String part, double x, double y, double z,
							  float toi, int substeps, float damage, boolean weak, boolean bounce, int hitstop) implements CustomPacketPayload {
	public static final Type<HitDebugPayload> TYPE = new Type<>(HuntCraft.id("hit_debug"));
	public static final StreamCodec<ByteBuf, HitDebugPayload> CODEC = StreamCodec.of(HitDebugPayload::write, HitDebugPayload::read);

	private static void write(ByteBuf buf, HitDebugPayload p) {
		ByteBufCodecs.VAR_INT.encode(buf, p.attackerId);
		ByteBufCodecs.VAR_INT.encode(buf, p.targetId);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.hurtbox);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.part);
		buf.writeDouble(p.x);
		buf.writeDouble(p.y);
		buf.writeDouble(p.z);
		buf.writeFloat(p.toi);
		ByteBufCodecs.VAR_INT.encode(buf, p.substeps);
		buf.writeFloat(p.damage);
		buf.writeBoolean(p.weak);
		buf.writeBoolean(p.bounce);
		ByteBufCodecs.VAR_INT.encode(buf, p.hitstop);
	}

	private static HitDebugPayload read(ByteBuf buf) {
		return new HitDebugPayload(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
				ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
				buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), ByteBufCodecs.VAR_INT.decode(buf),
				buf.readFloat(), buf.readBoolean(), buf.readBoolean(), ByteBufCodecs.VAR_INT.decode(buf));
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
