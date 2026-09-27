package kr.huntcraft.fx;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.config.CommonConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 사건 → 사운드 · 파티클 (서버에서 틀면 주변 모두에게). 무엇을 틀지는 common.json 의 effects (바닐라 id).
 * id 가 비었거나 없는 id 면 조용히 건너뜀 (처음 한 번 로그).
 */
public final class Effects {
	private static final java.util.Map<String, CommonConfig.Effect> DEFAULTS = CommonConfig.Effect.defaults();

	private Effects() {}

	public static void play(ServerLevel level, String event, Vec3 pos) {
		CommonConfig.Effect e = CommonConfig.get().effects.get(event);
		if (e == null) {
			e = DEFAULTS.get(event); // 옛 설정 파일에 없는 새 사건
		}
		if (e == null || level == null) {
			return;
		}
		if (e.sound != null && !e.sound.isBlank()) {
			Holder<SoundEvent> sound = sound(e.sound, range(event, e));
			if (sound != null) {
				// 변형 파일 고르기는 sounds.json (클라이언트) · 높낮이는 여기서 ±pitchJitter
				double jitter = e.pitchJitter > 0.0 ? (level.getRandom().nextDouble() * 2.0 - 1.0) * e.pitchJitter : 0.0;
				level.playSound(null, pos.x, pos.y, pos.z, sound, SoundSource.PLAYERS, (float) e.volume, (float) (e.pitch * (1.0 + jitter)));
			}
		}
		if (e.particle != null && !e.particle.isBlank() && e.count > 0) {
			ParticleType<?> type = particle(e.particle);
			if (type instanceof SimpleParticleType simple) {
				level.sendParticles(simple, pos.x, pos.y, pos.z, e.count, e.spread, e.spread, e.spread, e.speed);
			}
		}
	}

	/** 파티클 하나 (id 는 바닐라 단순 파티클) — 보스 행동의 emitters */
	public static void particle(ServerLevel level, String id, Vec3 pos, int count, double spread, double speed) {
		ParticleType<?> type = particle(id);
		if (type instanceof SimpleParticleType simple) {
			level.sendParticles(simple, pos.x, pos.y, pos.z, count, spread, spread, spread, speed);
		}
	}

	/** 들리는 거리: 설정값 → 이 사건의 기본값 → 0 (바닐라) */
	private static double range(String event, CommonConfig.Effect e) {
		if (e.range > 0.0) {
			return e.range;
		}
		CommonConfig.Effect d = DEFAULTS.get(event);
		return d == null ? 0.0 : d.range;
	}

	/**
	 * huntcraft: 사운드는 등록하지 않고 id 로 보냄 (클라이언트가 sounds.json 에서 찾음) — 파일만 바꿔 끼우면 됨.
	 * 바닐라 id 는 등록된 것만 (오타면 한 번 경고). 거리를 정했으면 그 거리로 보냄.
	 */
	private static Holder<SoundEvent> sound(String id, double range) {
		try {
			Identifier key = Identifier.parse(id);
			if (!HuntCraft.MOD_ID.equals(key.getNamespace()) && BuiltInRegistries.SOUND_EVENT.getValue(key) == null) {
				warn(id);
				return null;
			}
			return Holder.direct(range > 0.0 ? SoundEvent.createFixedRangeEvent(key, (float) range) : SoundEvent.createVariableRangeEvent(key));
		} catch (RuntimeException ex) {
			warn(id);
			return null;
		}
	}

	private static ParticleType<?> particle(String id) {
		try {
			ParticleType<?> p = BuiltInRegistries.PARTICLE_TYPE.getValue(Identifier.parse(id));
			if (p == null) {
				warn(id);
			}
			return p;
		} catch (RuntimeException ex) {
			warn(id);
			return null;
		}
	}

	private static final java.util.Set<String> WARNED = new java.util.HashSet<>();

	private static void warn(String id) {
		if (WARNED.add(id)) {
			HuntCraft.LOGGER.warn("효과 id '{}' 를 찾을 수 없음 (common.json effects)", id);
		}
	}
}
