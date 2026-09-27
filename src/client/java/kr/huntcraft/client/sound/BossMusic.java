package kr.huntcraft.client.sound;

import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.hud.ClientProgress;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * 보스 음악 (내 클라이언트) — 곡은 몬스터 정의의 music (intro · phase1 · phase2 · ending).
 *   체력바가 뜨면 시작: 깨어나는 중이면 intro (한 번) → 끝나면 phase1 반복, 분노 (2페이즈) 면 phase2 반복 (엇갈려 바뀜)
 *   처치: ending 을 한 번 — endingFadeStart 초부터 endingFadeSeconds 동안 천천히 줄어 사라짐
 *   체력바가 사라졌는데 보스가 살아 있음 (헌터가 쓰러짐 · 전장 초기화) → 짧게 줄여 끔
 * 음악 종류 (MUSIC) 라 게임의 음악 음량을 따름 · music.volume 배 (배경 수준). 도는 동안 바닐라 음악은 멈춤 (MusicManagerBossMixin).
 */
public final class BossMusic {
	public enum State { OFF, INTRO, PHASE1, PHASE2, ENDING }

	private static State state = State.OFF;
	private static Track current;
	private static Track fading;
	private static int bossId = -1;
	private static MonsterDef.Music music;
	private static boolean introFinished;
	/** 체력바가 뜬 뒤 행동 동기화를 기다린 틱 */
	private static int settle;
	private static final int SETTLE_TICKS = 10;

	private BossMusic() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(BossMusic::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> stopNow());
	}

	public static State state() {
		return state;
	}

	/** 바닐라 음악을 멈출지 (보스 음악이 도는 동안) */
	public static boolean suppressing() {
		return state != State.OFF || (fading != null && !fading.isStopped());
	}

	/** 지금 트랙의 음량 (시험) */
	public static float volume() {
		return current == null ? 0.0F : current.getVolume();
	}

	public static String track() {
		return current == null ? null : current.getIdentifier().toString();
	}

	/** 시험: 인트로를 건너뜀 (48초 기다리지 않게) */
	public static void skipIntro() {
		if (state == State.INTRO) {
			introFinished = true;
		}
	}

	private static void tick(Minecraft mc) {
		ClientConfig.Music cfg = ClientConfig.get().music;
		if (fading != null && fading.isStopped()) {
			fading = null;
		}
		if (!cfg.enabled || mc.level == null) {
			if (state != State.OFF) {
				fadeOut(cfg.stopFadeSeconds);
			}
			return;
		}
		MonsterEntity bar = ClientProgress.boss();
		MonsterEntity boss = bossId >= 0 && mc.level.getEntity(bossId) instanceof MonsterEntity m ? m : null;
		switch (state) {
			case OFF -> {
				if (bar == null) {
					settle = 0;
				}
				if (bar != null && bar.def() != null && bar.def().music != null && !bar.dead()) {
					bossId = bar.getId();
					music = bar.def().music;
					introFinished = false;
					// 체력바가 행동 동기화보다 먼저 올 수 있음 → 앉아 있음 · 깨어나는 중이면 첫 등장, 아니면 조금 (settleTicks) 기다렸다 판단
					String act = bar.actionId();
					boolean waking = (bar.def().awaken != null && bar.def().awaken.equals(act)) || (bar.def().dormant != null && bar.def().dormant.equals(act));
					if (!waking && ++settle < SETTLE_TICKS) {
						break;
					}
					settle = 0;
					if (waking && music.intro() != null) {
						play(music.intro(), State.INTRO, false, 0.0);
					} else {
						play(phaseTrack(bar), bar.enraged() ? State.PHASE2 : State.PHASE1, true, cfg.crossfadeSeconds);
					}
				}
			}
			case INTRO, PHASE1, PHASE2 -> {
				if (boss != null && boss.dead()) {
					if (music.ending() != null) {
						play(music.ending(), State.ENDING, false, cfg.crossfadeSeconds);
					} else {
						fadeOut(cfg.endingFadeSeconds);
					}
				} else if (bar == null || boss == null) {
					// 헌터가 쓰러짐 · 전장 초기화 · 멀리 떠남
					fadeOut(cfg.stopFadeSeconds);
				} else if (state == State.INTRO) {
					if (introFinished || current == null || current.finished()) {
						play(phaseTrack(boss), boss.enraged() ? State.PHASE2 : State.PHASE1, true, 0.0);
					}
				} else if (state == State.PHASE1 && boss.enraged() && music.phase2() != null) {
					play(music.phase2(), State.PHASE2, true, cfg.crossfadeSeconds);
				}
			}
			case ENDING -> {
				if (current == null || current.isStopped() || current.finished()) {
					stopNow();
				}
			}
		}
	}

	private static String phaseTrack(MonsterEntity boss) {
		return boss.enraged() && music.phase2() != null ? music.phase2() : music.phase1();
	}

	/**
	 * 곡 파일이 있는지 — 공개 배포판은 음악 파일을 빼고 올리므로 (저작권), 없으면 조용히 건너뜀.
	 * 곡은 리소스팩으로 assets/huntcraft/sounds/music/<곡>.ogg 에 넣으면 됨 (sounds.json 은 모드에 있음)
	 */
	private static boolean available(String id) {
		if (id == null) {
			return false;
		}
		var set = Minecraft.getInstance().getSoundManager().getSoundEvent(Identifier.parse(id));
		if (set == null) {
			return false;
		}
		var sound = set.getSound(RandomSource.create());
		return sound != null && sound != net.minecraft.client.sounds.SoundManager.EMPTY_SOUND
				&& Minecraft.getInstance().getResourceManager().getResource(sound.getPath()).isPresent();
	}

	/** 새 트랙 시작 — 지금 트랙은 crossfade 초 동안 줄여 끔 (0 = 바로 끊지 않고 이어 붙임). 곡 파일이 없으면 소리 없이 상태만 넘어감 */
	private static void play(String id, State next, boolean loop, double crossfade) {
		Minecraft mc = Minecraft.getInstance();
		if (!available(id)) {
			if (current != null) {
				if (fading != null) {
					fading.kill();
				}
				fading = current;
				fading.fadeTo(0.0, Math.max(0.05, crossfade));
				current = null;
			}
			state = next;
			return;
		}
		if (current != null) {
			if (fading != null) {
				fading.kill();
			}
			fading = current;
			fading.fadeTo(0.0, Math.max(0.05, crossfade));
		}
		ClientConfig.Music cfg = ClientConfig.get().music;
		current = new Track(Identifier.parse(id), loop, next == State.ENDING ? cfg.endingFadeStart : -1.0, cfg.endingFadeSeconds);
		current.fadeFromZero(crossfade);
		mc.getSoundManager().play(current);
		state = next;
	}

	private static void fadeOut(double seconds) {
		if (current != null) {
			if (fading != null) {
				fading.kill();
			}
			fading = current;
			fading.fadeTo(0.0, seconds);
			current = null;
		}
		state = State.OFF;
		bossId = -1;
	}

	private static void stopNow() {
		if (current != null) {
			current.kill();
		}
		if (fading != null) {
			fading.kill();
		}
		current = null;
		fading = null;
		state = State.OFF;
		bossId = -1;
	}

	/** 음악 한 트랙 — 매 틱 음량을 목표 쪽으로 (페이드), 처치 곡은 정해진 때부터 스스로 줄어듦 */
	private static final class Track extends AbstractTickableSoundInstance {
		private double gain = 1.0;
		private double target = 1.0;
		/** 초당 음량 변화 (실제 시간 — 클라이언트 틱이 60Hz 여도 같은 속도) */
		private double rate = 1.0;
		private long last = -1L;
		private boolean selfFading;
		private final double selfFadeStart;
		private final double selfFadeSeconds;
		private long started = -1L;
		private boolean heard;
		private boolean done;

		Track(Identifier id, boolean loop, double selfFadeStart, double selfFadeSeconds) {
			super(SoundEvent.createVariableRangeEvent(id), SoundSource.MUSIC, RandomSource.create());
			this.looping = loop;
			this.relative = true;
			this.attenuation = SoundInstance.Attenuation.NONE;
			this.selfFadeStart = selfFadeStart;
			this.selfFadeSeconds = selfFadeSeconds;
			apply();
		}

		void fadeFromZero(double seconds) {
			if (seconds > 0.0) {
				gain = 0.0;
				fadeTo(1.0, seconds);
			}
			apply();
		}

		void fadeTo(double to, double seconds) {
			target = to;
			rate = Math.abs(target - gain) / Math.max(0.05, seconds);
		}

		void kill() {
			done = true;
			stop();
			Minecraft.getInstance().getSoundManager().stop(this);
		}

		/** 한 번 재생하는 곡이 끝났는지 (한 번이라도 울린 뒤 더 이상 안 울림) */
		boolean finished() {
			return heard && !Minecraft.getInstance().getSoundManager().isActive(this);
		}

		@Override
		public void tick() {
			if (done) {
				stop();
				return;
			}
			long now = System.nanoTime();
			if (started < 0L) {
				started = now;
				last = now;
			}
			double dt = Math.min(0.25, (now - last) / 1.0E9);
			last = now;
			if (Minecraft.getInstance().getSoundManager().isActive(this)) {
				heard = true;
			}
			if (selfFadeStart >= 0.0 && !selfFading && (now - started) / 1.0E9 >= selfFadeStart) {
				selfFading = true;
				fadeTo(0.0, selfFadeSeconds);
			}
			if (gain < target) {
				gain = Math.min(target, gain + rate * dt);
			} else if (gain > target) {
				gain = Math.max(target, gain - rate * dt);
			}
			if (target <= 0.0 && gain <= 0.0) {
				done = true;
				stop();
			}
			apply();
		}

		private void apply() {
			volume = (float) (gain * ClientConfig.get().music.volume);
		}
	}
}
