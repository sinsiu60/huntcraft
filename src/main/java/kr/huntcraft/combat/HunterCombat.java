package kr.huntcraft.combat;

import kr.huntcraft.collision.Defs;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.hunter.HunterState;
import net.minecraft.util.Mth;

/**
 * 무기 모션 상태 머신 — 서버(권한)와 클라이언트(예측)가 같은 입력으로 같은 결과를 냅니다.
 *
 *   입력은 곧바로 실행하지 않고 선입력 버퍼에 넣음 (player.inputBufferSeconds 동안 유효)
 *   매 스텝: 히트스톱 중이면 모션 시간 정지 → 아니면 프레임 +1, 끝나면 대기 자세로 → 버퍼 입력이 지금 받아들여지면 다음 모션 시작
 *   받아들이는 조건: 대기 중이면 즉시 (끝난 지 comboWindow 안이면 그 모션의 next 파생) / 모션 중이면 next 에 있고 cancel 프레임 이후 (회피는 evadeCancel,
 *   물러나 베기는 next 에 없어도 공격 모션의 cancel 이후면 항상 가능)
 *   시작할 때: 입력 방향으로 turn 도까지만 돎 (turnFree 는 제한 없음), 모션 도중에는 회전 없음.
 *             variants 가 있는 모션은 입력 방향(몸 기준 앞 · 왼 · 오른 · 뒤)의 변형으로 바꿔서 시작 (락온 회피)
 *   대기 입력표: 공중이면 air (점프 공격) · 달리는 중이면 sprint (달리기 공격) · 방금 끝난 모션의 next (콤보 유지) · drawn
 *   누르고 있는 키: 강공격 (차지 — charge.at 에 누르고 있으면 차지 모션, 떼거나 max 면 release) · 가드 (guardHold 모션은 뗄 때까지)
 *   가드 키를 누르고 있으면 선입력이 없어도 받아들여지는 순간 가드로 (소울류: 공격이 끝나면 바로 방패)
 */
public final class HunterCombat {
	public static final String WEAPON = "longsword";

	/** 지금 모션 (null = 대기) */
	private String motion;
	/** 모션 시작부터 지난 프레임 (히트스톱 동안은 멈춤) */
	private int frame;
	/** 이번 스텝 전 프레임 (판정 스윕의 시작 시각). 이번 스텝에 시작했으면 -1 */
	private int prevFrame = -1;
	private int hitstop;
	/** 모션 시작마다 +1 — 공격 인스턴스 번호의 바탕 */
	private long serial;
	/** 모션 중 몸 방향 (yaw) */
	private float facing;
	/** 이동량 방향 (몸 기준 추가 회전, 도) — 물러나 베기의 좌우 */
	private float rootYaw;

	/** 콤보 유지: 방금 끝난 모션과 남은 프레임 — 대기 중이라도 이 동안은 그 모션의 next 파생이 나감 */
	private String comboFrom;
	private int comboLeft;

	/** 기상 무적 (wakeUp 모션이 끝난 뒤 남은 프레임) */
	private int postInvuln;

	/** 지금 모션이 쓰는 아이템 번호 (-1 = 아이템 모션 아님) · 효과 프레임을 지났을 때 서버가 가져갈 번호 */
	private int usingItem = -1;
	private int itemEvent = -1;

	/** 누르고 있는 키 (강공격 · 가드) — *_RELEASE 입력으로 풀림 */
	private boolean heavyHeld;
	private boolean guardHeld;
	/** 공중 (점프 공격 입력표) — 매 스텝 전에 호출하는 쪽이 넣음 */
	private boolean airborne;
	/** 앞에 치명 일격을 넣을 수 있는 보스가 있음 (CriticalRules) — 매 스텝 전에 넣음 */
	private boolean critReady;
	/** 차지 모션에서 지난 프레임 · 방금 낸 차지 공격의 차지량 (0~1) */
	private int chargeFrames;
	private double chargeLevel;

	private CombatInput buffered;
	private int bufferAge;
	private float bufferedYaw;
	private boolean bufferedHasDir;
	/** 입력할 때 락온 중이었는지 (회피 → 방향별 변형, MoveSet.locked 표) */
	private boolean bufferedLocked;

	public static MoveSet moveSet() {
		return Defs.moveSet(WEAPON);
	}

	public MoveSet.Motion current() {
		MoveSet set = moveSet();
		return set == null ? null : set.motion(motion);
	}

	public String motionId() {
		return motion;
	}

	public int frame() {
		return frame;
	}

	public int prevFrame() {
		return prevFrame;
	}

	public int hitstop() {
		return hitstop;
	}

	public long serial() {
		return serial;
	}

	public float facing() {
		return facing;
	}

	public float rootYaw() {
		return rootYaw;
	}

	/** 콤보 유지 남은 프레임 (디버그) */
	public int comboLeft() {
		return comboLeft;
	}

	public String comboFrom() {
		return comboFrom;
	}

	public CombatInput buffered() {
		return buffered;
	}

	public void setAirborne(boolean airborne) {
		this.airborne = airborne;
	}

	public void setCritReady(boolean ready) {
		this.critReady = ready;
	}

	public boolean critReady() {
		return critReady;
	}

	public boolean heavyHeld() {
		return heavyHeld;
	}

	public boolean guardHeld() {
		return guardHeld;
	}

	/** 방금 낸 차지 공격의 차지량 (0 = 차지 없음, 1 = 최대) */
	public double chargeLevel() {
		return chargeLevel;
	}

	/** 입력 (입력한 순간의 이동 입력 방향 yaw 와 함께) */
	public void input(CombatInput in, float dirYaw, boolean hasDir) {
		input(in, dirYaw, hasDir, false);
	}

	/** locked = 락온 중 (회피는 락온 표의 방향별 구르기) */
	public void input(CombatInput in, float dirYaw, boolean hasDir, boolean locked) {
		if (!in.combat) {
			return;
		}
		switch (in) {
			case HEAVY -> heavyHeld = true;
			case HEAVY_RELEASE -> heavyHeld = false;
			case GUARD -> guardHeld = true;
			case GUARD_RELEASE -> guardHeld = false;
			default -> {
			}
		}
		if (in.release()) {
			return;
		}
		buffered = in;
		bufferAge = 0;
		bufferedYaw = dirYaw;
		bufferedHasDir = hasDir;
		bufferedLocked = locked;
	}

	/** 한 스텝 (60Hz). currentYaw = 지금 몸 방향 */
	public void tick(HunterState st, float currentYaw) {
		MoveSet set = moveSet();
		if (set == null) {
			return;
		}
		if (hitstop > 0) {
			hitstop--;
			prevFrame = frame;
			ageBuffer();
			return;
		}
		MoveSet.Motion m = set.motion(motion);
		if (m != null) {
			prevFrame = frame;
			frame++;
			MoveSet.Extra x = m.x();
			// 강공격 차지: at 프레임에 키를 누르고 있으면 차지 모션으로
			if (x.chargeAt() >= 0 && prevFrame < x.chargeAt() && frame >= x.chargeAt() && heavyHeld && set.motion(x.chargeInto()) != null) {
				chargeFrames = 0;
				begin(x.chargeInto(), facing);
				finishTick(st, set);
				return;
			}
			// 차지 중: 떼거나 최대면 차지 공격
			if (x.chargeMax() > 0) {
				chargeFrames++;
				if (!heavyHeld || chargeFrames >= x.chargeMax()) {
					chargeLevel = Math.min(1.0, chargeFrames / (double) x.chargeMax());
					begin(x.chargeRelease(), facing);
					finishTick(st, set);
					return;
				}
			}
			// 가드: 키를 떼면 끝
			if (x.guardHold() && !guardHeld) {
				motion = null;
				frame = 0;
				prevFrame = -1;
				finishTick(st, set);
				return;
			}
			if (usingItem >= 0 && m.itemFrame() >= 0 && prevFrame < m.itemFrame() && frame >= m.itemFrame()) {
				itemEvent = usingItem;
			}
			if (frame >= m.length()) {
				usingItem = -1;
				if (m.sheathes()) {
					st.drawn = false;
				}
				if (m.wakeUp()) {
					postInvuln = FixedTick.ticks(CommonConfig.get().player.wakeUpInvulnSeconds);
				}
				if (!m.next().isEmpty()) {
					comboFrom = m.id();
					comboLeft = m.comboWindow() >= 0 ? m.comboWindow() : FixedTick.ticks(CommonConfig.get().player.comboWindowSeconds);
				}
				motion = null;
				frame = 0;
				prevFrame = -1;
				// 막기 경직이 끝났는데 가드 키를 누르고 있으면 다시 가드
				if (x.guardNext() != null && guardHeld && set.motion(x.guardNext()) != null) {
					begin(x.guardNext(), facing);
				}
			}
		} else {
			if (comboLeft > 0 && --comboLeft == 0) {
				comboFrom = null;
			}
			if (postInvuln > 0) {
				postInvuln--;
			}
		}
		if (buffered != null) {
			String next = transition(set, buffered, st);
			if (next != null) {
				start(set, next, st, currentYaw);
				buffered = null;
			}
		}
		// 가드 키를 누르고 있음 = 계속 들어오는 가드 입력 (이미 가드 중이면 그대로)
		MoveSet.Motion now = set.motion(motion);
		if (buffered == null && guardHeld && (now == null || now.x().guardFrom() < 0)) {
			String next = transition(set, CombatInput.GUARD, st);
			if (next != null) {
				bufferedHasDir = false;
				start(set, next, st, currentYaw);
			}
		}
		finishTick(st, set);
	}

	/** 스텝 끝: 가드 상태 (스태미나 회복 느림) · 선입력 나이 */
	private void finishTick(HunterState st, MoveSet set) {
		MoveSet.Motion m = set.motion(motion);
		st.guarding = m != null && m.x().guardAt(frame);
		ageBuffer();
	}

	/** 모션 안에서 다른 모션으로 이어 감 (차지 · 차지 공격 · 다시 가드) — 몸 방향 그대로, 선입력은 유지 */
	private void begin(String id, float facingYaw) {
		motion = id;
		frame = 0;
		prevFrame = -1;
		hitstop = 0;
		serial++;
		comboFrom = null;
		comboLeft = 0;
		facing = facingYaw;
		rootYaw = 0.0F;
	}

	private void ageBuffer() {
		if (buffered != null && ++bufferAge > FixedTick.ticks(CommonConfig.get().player.inputBufferSeconds)) {
			buffered = null;
		}
	}

	/** 받아들여지는 모션 — 락온 중이면 locked 표가 우선 (지금 받아들일 수 있을 때만) */
	private String transition(MoveSet set, CombatInput in, HunterState st) {
		String next = transitionRaw(set, in, st);
		// 치명 일격: 공격이 받아들여지는 순간 앞에 치명 구간 보스가 있으면 (공중 제외)
		if (next != null && critReady && !airborne && set.critical.containsKey(in)) {
			return set.critical.get(in);
		}
		if (next != null && bufferedLocked && set.locked.containsKey(in)) {
			return set.locked.get(in);
		}
		return next;
	}

	private String transitionRaw(MoveSet set, CombatInput in, HunterState st) {
		MoveSet.Motion m = set.motion(motion);
		if (m == null) {
			// 공중: 점프 공격 · 달리는 중: 달리기 공격
			if (airborne) {
				return set.air.get(in);
			}
			if (st.sprinting && set.sprint.containsKey(in)) {
				return set.sprint.get(in);
			}
			// 콤보 유지 중이면 방금 끝난 모션의 파생
			MoveSet.Motion last = comboLeft > 0 && st.drawn ? set.motion(comboFrom) : null;
			if (last != null && last.next().containsKey(in)) {
				return last.next().get(in);
			}
			return (st.drawn ? set.idleDrawn : set.idleSheathed).get(in);
		}
		String next = m.next().get(in);
		if (next == null && m.idleNext()) {
			next = (st.drawn ? set.idleDrawn : set.idleSheathed).get(in);
		}
		if (in == CombatInput.EVADE) {
			if (next == null) {
				next = (st.drawn ? set.idleDrawn : set.idleSheathed).get(in);
			}
			return frame >= m.evadeCancel() ? next : null;
		}
		return next != null && frame >= m.cancel() ? next : null;
	}


	private void start(MoveSet set, String id, HunterState st, float currentYaw) {
		MoveSet.Motion m = set.motion(id);
		int item = -1;
		if (m.itemSelect()) {
			// 아이템 사용: 고른 아이템의 사용 모션으로 (다 떨어졌으면 입력 버림)
			kr.huntcraft.config.CommonConfig.ItemDef d = st.selectedItem();
			if (d == null || st.count(st.itemIndex) == 0 || set.motion(d.motion) == null) {
				return;
			}
			item = st.itemIndex;
			id = d.motion;
			m = set.motion(id);
		}
		// 방향 입력 없이 누름 → neutral 모션 (구르기 → 백스텝)
		if (!bufferedHasDir && m.neutral() != null && set.motion(m.neutral()) != null) {
			id = m.neutral();
			m = set.motion(id);
		}
		Float variantFacing = null;
		if (!m.variants().isEmpty()) {
			// 방향별 변형: 몸 방향 기준 입력 방향으로 고름 (입력 없으면 앞). 몸은 그 변형이 정확히 입력 방향으로 가게
			// 맞춤 (최대 45°) — 입력 직전 걷기로 몸이 조금 돌아 있어도 구르기는 누른 방향 그대로
			MoveSet.Direction dir = bufferedHasDir ? MoveSet.Direction.of(Mth.wrapDegrees(bufferedYaw - currentYaw)) : MoveSet.Direction.FORWARD;
			String variant = m.variants().getOrDefault(dir, m.variants().get(MoveSet.Direction.FORWARD));
			if (variant == null || set.motion(variant) == null) {
				return;
			}
			if (bufferedHasDir) {
				variantFacing = Mth.wrapDegrees(bufferedYaw - dir.offset());
			}
			id = variant;
			m = set.motion(variant);
		}
		if (m.stamina() > 0.0) {
			CommonConfig cfg = CommonConfig.get();
			double cost = m.stamina() * (m.evade() ? cfg.rollClass().staminaScale : 1.0);
			// 소울류: 조금이라도 남았으면 하고 0 에서 멈춤 (0 이면 못 함) · allowPartialCost=false 면 모자라면 못 함
			if (cfg.stamina.allowPartialCost ? st.stamina <= 0.0 : st.stamina < cost) {
				return; // 스태미나 부족 — 입력은 버려짐
			}
			st.stamina = Math.max(0.0, st.stamina - cost);
			st.regenDelayTicks = FixedTick.ticks(cfg.stamina.regenDelaySeconds);
		}
		float yaw = currentYaw;
		if (bufferedHasDir) {
			yaw = m.turnFree() ? bufferedYaw : approach(currentYaw, bufferedYaw, (float) m.turn());
		}
		facing = variantFacing != null ? variantFacing : yaw;
		rootYaw = 0.0F;
		if (m.directional() && bufferedHasDir) {
			float rel = Mth.wrapDegrees(bufferedYaw - yaw);
			if (rel > 45.0F && rel < 135.0F) {
				rootYaw = -90.0F; // 오른쪽 입력 → 뒤로 가는 이동량을 오른쪽으로
			} else if (rel < -45.0F && rel > -135.0F) {
				rootYaw = 90.0F;
			}
		}
		motion = id;
		frame = 0;
		prevFrame = -1;
		hitstop = 0;
		serial++;
		comboFrom = null;
		comboLeft = 0;
		usingItem = item;
		if (!m.x().charged()) {
			chargeLevel = 0.0;
		}
		if (m.draws()) {
			st.drawn = true;
		}
	}

	/**
	 * 강제 모션 (피격 반응 · 포효 귀막기) — 입력 · 캔슬 조건 없이 바로 시작, 선입력 버림.
	 * facing = 몸 방향 (피격 반응은 몬스터 쪽을 보게 해서 뒤로 밀려남)
	 */
	public void force(String id, float facingYaw) {
		MoveSet set = moveSet();
		MoveSet.Motion m = set == null ? null : set.motion(id);
		if (m == null) {
			return;
		}
		buffered = null;
		motion = id;
		frame = 0;
		prevFrame = -1;
		hitstop = 0;
		serial++;
		comboFrom = null;
		comboLeft = 0;
		postInvuln = 0;
		usingItem = -1; // 맞아서 끊기면 아이템 효과 없음
		facing = facingYaw;
		rootYaw = 0.0F;
	}

	/** 모션 · 선입력 · 콤보를 모두 비움 (부활) */
	public void clear() {
		motion = null;
		frame = 0;
		prevFrame = -1;
		hitstop = 0;
		buffered = null;
		comboFrom = null;
		comboLeft = 0;
		postInvuln = 0;
		usingItem = -1;
		heavyHeld = false;
		guardHeld = false;
		chargeLevel = 0.0;
		serial++;
	}

	/** 가드 중인지 (지금 모션의 guardFrom 이후) */
	public boolean guardActive(double time) {
		MoveSet.Motion m = current();
		return m != null && m.x().guardAt(time);
	}

	/** 슈퍼아머 강인도 보너스 (구간 밖이면 -1) */
	public double hyperArmorBonus(double time) {
		MoveSet.Motion m = current();
		if (m == null || !m.x().hyperArmorAt(time)) {
			return -1.0;
		}
		return m.x().poiseBonus() >= 0.0 ? m.x().poiseBonus() : CommonConfig.get().player.hyperArmorPoise;
	}

	/** 패링 구간인지 — 모션 parry 의 시작 프레임부터 player.parryWindowSeconds 동안 */
	public boolean parry(double time) {
		MoveSet.Motion m = current();
		if (m == null || m.parry() == null) {
			return false;
		}
		int from = m.parry()[0];
		return time >= from && time < from + FixedTick.ticks(CommonConfig.get().player.parryWindowSeconds);
	}

	/** 아이템 효과 프레임을 지났으면 그 아이템 번호 (서버가 한 번 가져감), 아니면 -1 */
	public int takeItemEvent() {
		int i = itemEvent;
		itemEvent = -1;
		return i;
	}

	/** 지금 모션이 쓰는 아이템 (-1 = 없음) — 손에 든 물건 렌더 */
	public int usingItem() {
		return usingItem;
	}

	/** 명중 → 히트스톱 (기술별 값, 없으면 기본값) */
	public void onHit(MoveSet.Hit hit) {
		CommonConfig.Hitstop cfg = CommonConfig.get().hitstop;
		if (cfg.enabled) {
			hitstop = Math.max(hitstop, hit.hitstop() >= 0 ? hit.hitstop() : cfg.defaultFrames);
		}
	}

	/** time(프레임)의 공격 인스턴스 번호 (0 = 없음) — 활성 구간마다 따로 */
	public long instance(double time) {
		MoveSet.Motion m = current();
		if (m == null) {
			return 0L;
		}
		int idx = m.hitIndex(time);
		return idx < 0 ? 0L : serial * 16L + idx + 1L;
	}

	public MoveSet.Hit hitAt(double time) {
		MoveSet.Motion m = current();
		if (m == null) {
			return null;
		}
		int idx = m.hitIndex(time);
		return idx < 0 ? null : m.hits().get(idx);
	}

	/** 회피 무적 (구르기 = player.evadeInvulnSeconds × 무게 배율, 백스텝 = backstepInvulnSeconds, 그 외 모션은 invuln 구간) */
	public boolean invulnerable(double time) {
		MoveSet.Motion m = current();
		if (m == null) {
			return postInvuln > 0;
		}
		CommonConfig cfg = CommonConfig.get();
		if (m.backstep()) {
			return time >= 1.0 && time <= FixedTick.ticks(cfg.player.backstepInvulnSeconds);
		}
		if (m.evade()) {
			return time >= 1.0 && time <= FixedTick.ticks(cfg.player.evadeInvulnSeconds * cfg.rollClass().invulnScale);
		}
		return m.invulnerable(time);
	}

	/** 서버 상태로 맞춤 (클라이언트) */
	public void applySync(String motionId, int syncFrame, int syncHitstop, long syncSerial, float syncFacing, float syncRootYaw) {
		if (motionId == null || !motionId.equals(motion) || syncSerial != serial) {
			prevFrame = -1;
		} else {
			prevFrame = Math.min(prevFrame, syncFrame - 1);
		}
		motion = motionId;
		frame = syncFrame;
		hitstop = syncHitstop;
		serial = syncSerial;
		facing = syncFacing;
		rootYaw = syncRootYaw;
	}

	private static float approach(float from, float to, float maxStep) {
		float delta = Mth.wrapDegrees(to - from);
		if (Math.abs(delta) <= maxStep) {
			return to;
		}
		return from + Math.signum(delta) * maxStep;
	}
}
