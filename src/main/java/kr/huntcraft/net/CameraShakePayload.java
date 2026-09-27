package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 서버 → 클라이언트: 화면 흔들림 (보스 내려치기 · 발걸음) — 세기(블록) · 길이(초), 거리 감쇠는 서버가 이미 적용 */
public record CameraShakePayload(float strength, float duration) implements CustomPacketPayload {
	public static final Type<CameraShakePayload> TYPE = new Type<>(HuntCraft.id("camera_shake"));
	public static final StreamCodec<ByteBuf, CameraShakePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.FLOAT, CameraShakePayload::strength,
			ByteBufCodecs.FLOAT, CameraShakePayload::duration,
			CameraShakePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
