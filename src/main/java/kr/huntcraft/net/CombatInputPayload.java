package kr.huntcraft.net;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.combat.CombatInput;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 클라이언트 → 서버: 전투 입력 1건 + 입력한 순간의 이동 입력 방향 (공격 시작 선회 · 회피 방향). */
public record CombatInputPayload(CombatInput input, float dirYaw, boolean hasDir, boolean locked) implements CustomPacketPayload {
	public static final Type<CombatInputPayload> TYPE = new Type<>(HuntCraft.id("combat_input"));
	public static final StreamCodec<ByteBuf, CombatInputPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT.map(i -> CombatInput.values()[Math.floorMod(i, CombatInput.values().length)], CombatInput::ordinal),
			CombatInputPayload::input,
			ByteBufCodecs.FLOAT, CombatInputPayload::dirYaw,
			ByteBufCodecs.BOOL, CombatInputPayload::hasDir,
			ByteBufCodecs.BOOL, CombatInputPayload::locked,
			CombatInputPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
