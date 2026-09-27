package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.hunter.HunterState;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 권한 상태 (클라이언트 예측을 이 값으로 맞춤, HUD 가 그대로 그림).
 * 체력 · 아이템 개수 (고른 아이템 번호는 클라이언트가 정해서 보내므로 빠짐)
 */
public record HunterSyncPayload(boolean drawn, float stamina, boolean exhausted, int regenDelayTicks, float health, int[] itemCounts) implements CustomPacketPayload {
	public static final Type<HunterSyncPayload> TYPE = new Type<>(HuntCraft.id("hunter_sync"));
	public static final StreamCodec<ByteBuf, HunterSyncPayload> CODEC = StreamCodec.of(HunterSyncPayload::write, HunterSyncPayload::read);

	private static void write(ByteBuf buf, HunterSyncPayload p) {
		buf.writeBoolean(p.drawn);
		buf.writeFloat(p.stamina);
		buf.writeBoolean(p.exhausted);
		ByteBufCodecs.VAR_INT.encode(buf, p.regenDelayTicks);
		buf.writeFloat(p.health);
		ByteBufCodecs.VAR_INT.encode(buf, p.itemCounts.length);
		for (int c : p.itemCounts) {
			buf.writeInt(c);
		}
	}

	private static HunterSyncPayload read(ByteBuf buf) {
		boolean drawn = buf.readBoolean();
		float stamina = buf.readFloat();
		boolean exhausted = buf.readBoolean();
		int regen = ByteBufCodecs.VAR_INT.decode(buf);
		float health = buf.readFloat();
		int n = ByteBufCodecs.VAR_INT.decode(buf);
		if (n < 0 || n > 256) {
			throw new IllegalStateException("아이템 개수 목록 크기가 이상함: " + n);
		}
		int[] counts = new int[n];
		for (int i = 0; i < n; i++) {
			counts[i] = buf.readInt();
		}
		return new HunterSyncPayload(drawn, stamina, exhausted, regen, health, counts);
	}

	public static HunterSyncPayload of(HunterState s) {
		return new HunterSyncPayload(s.drawn, (float) s.stamina, s.exhausted, s.regenDelayTicks, (float) s.health, s.itemCounts.clone());
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
