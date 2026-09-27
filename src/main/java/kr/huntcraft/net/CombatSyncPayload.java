package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.combat.HunterCombat;
import kr.huntcraft.hunter.HunterState;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 서버 → 클라이언트: 헌터 한 명의 모션 상태 (권한 값). 모션이 바뀔 때 즉시, 그 외 10틱마다.
 * 본인은 예측과 크게 어긋날 때만 맞추고, 다른 플레이어는 그대로 받아 렌더 · 디버그 판정 표시에 씀.
 */
public record CombatSyncPayload(int entityId, String motion, int frame, int hitstop, long serial, float facing, float rootYaw,
								boolean drawn) implements CustomPacketPayload {
	public static final Type<CombatSyncPayload> TYPE = new Type<>(HuntCraft.id("combat_sync"));
	public static final StreamCodec<ByteBuf, CombatSyncPayload> CODEC = StreamCodec.of(CombatSyncPayload::write, CombatSyncPayload::read);

	public static CombatSyncPayload of(int entityId, HunterCombat c, HunterState s) {
		return new CombatSyncPayload(entityId, c.motionId() == null ? "" : c.motionId(), c.frame(), c.hitstop(), c.serial(),
				c.facing(), c.rootYaw(), s.drawn);
	}

	private static void write(ByteBuf buf, CombatSyncPayload p) {
		ByteBufCodecs.VAR_INT.encode(buf, p.entityId);
		ByteBufCodecs.STRING_UTF8.encode(buf, p.motion);
		ByteBufCodecs.VAR_INT.encode(buf, p.frame);
		ByteBufCodecs.VAR_INT.encode(buf, p.hitstop);
		ByteBufCodecs.VAR_LONG.encode(buf, p.serial);
		buf.writeFloat(p.facing);
		buf.writeFloat(p.rootYaw);
		buf.writeBoolean(p.drawn);
	}

	private static CombatSyncPayload read(ByteBuf buf) {
		return new CombatSyncPayload(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf),
				ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_LONG.decode(buf),
				buf.readFloat(), buf.readFloat(), buf.readBoolean());
	}

	public String motionOrNull() {
		return motion.isEmpty() ? null : motion;
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
