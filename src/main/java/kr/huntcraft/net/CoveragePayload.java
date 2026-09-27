package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 사각지대 커버리지 맵 (디버그). monsterId 의 발밑에 그 몬스터 기준 (처음 방향 +Z) 격자를 색으로 그림.
 * count[i] = 점 (x[i], z[i]) 을 맞히는 패턴 수. monsterId < 0 = 지움.
 */
public record CoveragePayload(int monsterId, float spacing, float[] x, float[] z, byte[] count) implements CustomPacketPayload {
	public static final Type<CoveragePayload> TYPE = new Type<>(HuntCraft.id("coverage"));
	public static final StreamCodec<ByteBuf, CoveragePayload> CODEC = StreamCodec.of((buf, p) -> {
		buf.writeInt(p.monsterId);
		buf.writeFloat(p.spacing);
		buf.writeInt(p.count.length);
		for (int i = 0; i < p.count.length; i++) {
			buf.writeFloat(p.x[i]);
			buf.writeFloat(p.z[i]);
			buf.writeByte(p.count[i]);
		}
	}, buf -> {
		int id = buf.readInt();
		float spacing = buf.readFloat();
		int n = Math.min(buf.readInt(), 100_000);
		float[] x = new float[n];
		float[] z = new float[n];
		byte[] c = new byte[n];
		for (int i = 0; i < n; i++) {
			x[i] = buf.readFloat();
			z[i] = buf.readFloat();
			c[i] = buf.readByte();
		}
		return new CoveragePayload(id, spacing, x, z, c);
	});

	public static CoveragePayload clear() {
		return new CoveragePayload(-1, 0.0F, new float[0], new float[0], new byte[0]);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
