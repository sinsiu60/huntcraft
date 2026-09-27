package kr.huntcraft.hunter;

import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;

/** 서버 · 클라이언트가 똑같이 돌리는 상태 규칙 (60Hz 한 스텝). 같은 입력이면 같은 결과가 나와야 예측이 맞습니다. */
public final class HunterRules {
	private HunterRules() {}

	/**
	 * @param sprintHeld 달리기 (Space 길게) 중
	 * @param moving     이동 입력이 있음
	 */
	public static void tick(HunterState s, boolean sprintHeld, boolean moving) {
		CommonConfig.Stamina cfg = CommonConfig.get().stamina;
		s.sprinting = sprintHeld && moving && !s.exhausted && s.stamina > 0.0;
		if (s.sprinting) {
			s.stamina -= cfg.sprintDrainPerSec * FixedTick.DT;
			s.regenDelayTicks = FixedTick.ticks(cfg.regenDelaySeconds);
			if (s.stamina <= 0.0) {
				s.stamina = 0.0;
				s.exhausted = true;
				s.sprinting = false;
			}
		} else if (s.regenDelayTicks > 0) {
			s.regenDelayTicks--;
		} else {
			double rate = cfg.regenPerSec * (s.guarding ? cfg.guardRegenMultiplier : 1.0);
			s.stamina = Math.min(cfg.max, s.stamina + rate * FixedTick.DT);
		}
		if (s.exhausted && s.stamina >= cfg.exhaustedResumeAt) {
			s.exhausted = false;
		}
	}

	/** 지금 상태의 목표 이동 속도 (블록/초) */
	public static double moveSpeed(HunterState s) {
		CommonConfig.Movement m = CommonConfig.get().movement;
		return s.sprinting ? m.sprintSpeed : m.walkSpeed;
	}
}
