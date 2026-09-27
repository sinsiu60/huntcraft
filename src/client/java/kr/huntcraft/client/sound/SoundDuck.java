package kr.huntcraft.client.sound;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import kr.huntcraft.client.config.ClientConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * 더킹: 큰 공격이 땅에 닿는 순간 (화면 흔들림 세기 ≥ sound.duck.minShake) 다른 소리 (음악 · 환경 · 블록 · 날씨 …) 를 잠깐 낮춤.
 * 보스 · 헌터 소리 (PLAYERS) 는 그대로 → 쾅이 묻히지 않음. 볼륨 곱은 SoundEngineDuckMixin 이 적용.
 * 흔들림 신호를 그대로 쓰므로 서버 · 패킷 변경 없음 (쾅 = 흔들림과 같은 프레임).
 */
public final class SoundDuck {
	private static long start = Long.MIN_VALUE;
	private static float lastFactor = 1.0F;
	private static final Set<SoundSource> SOURCES = EnumSet.noneOf(SoundSource.class);
	private static int triggered;

	private SoundDuck() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(SoundDuck::tick);
	}

	/** 화면 흔들림을 받음 — 세기가 문턱 이상이면 더킹 시작 (진행 중이면 다시 처음부터 유지) */
	public static void onShake(float strength) {
		ClientConfig.Duck d = ClientConfig.get().sound.duck;
		if (!d.enabled || strength < d.minShake) {
			return;
		}
		long now = System.nanoTime();
		// 이미 깊이 내려가 있으면 올라가기 시작점만 뒤로 미룸 (다시 내려가는 턱 없음)
		start = factorAt(now) < 1.0F ? now - (long) (d.attackSeconds * 1.0E9) : now;
		triggered++;
	}

	/** 소리 종류별 볼륨 곱 (SoundEngineDuckMixin) */
	public static float factor(SoundSource source) {
		if (lastFactor >= 1.0F || !sources().contains(source)) {
			return 1.0F;
		}
		return lastFactor;
	}

	public static int triggered() {
		return triggered;
	}

	public static float current() {
		return lastFactor;
	}

	private static void tick(Minecraft mc) {
		float f = factorAt(System.nanoTime());
		if (f == lastFactor) {
			return;
		}
		lastFactor = f;
		// 이미 울리고 있는 소리의 볼륨도 다시 계산
		for (SoundSource s : sources()) {
			mc.getSoundManager().refreshCategoryVolume(s);
		}
	}

	/** 공격 (attack) → 유지 (hold) → 회복 (release) 봉투 */
	private static float factorAt(long now) {
		if (start == Long.MIN_VALUE) {
			return 1.0F;
		}
		ClientConfig.Duck d = ClientConfig.get().sound.duck;
		double t = (now - start) / 1.0E9;
		double depth = Math.max(0.0, Math.min(1.0, d.depth));
		double k;
		if (t < d.attackSeconds) {
			k = t / Math.max(1.0E-3, d.attackSeconds);
		} else if (t < d.attackSeconds + d.holdSeconds) {
			k = 1.0;
		} else {
			double r = (t - d.attackSeconds - d.holdSeconds) / Math.max(1.0E-3, d.releaseSeconds);
			if (r >= 1.0) {
				start = Long.MIN_VALUE;
				return 1.0F;
			}
			k = 1.0 - r;
		}
		return (float) (1.0 - (1.0 - depth) * k);
	}

	private static Set<SoundSource> sources() {
		SOURCES.clear();
		for (String name : ClientConfig.get().sound.duck.sources) {
			try {
				SOURCES.add(SoundSource.valueOf(name.toUpperCase(Locale.ROOT)));
			} catch (IllegalArgumentException ignored) {
				// 모르는 이름은 건너뜀
			}
		}
		return SOURCES;
	}
}
