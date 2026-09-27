package kr.huntcraft.client.input;

import kr.huntcraft.combat.CombatInput;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.core.FixedTick;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * 키 입력 → {@link CombatInput} 해석 (클라이언트 60Hz 틱마다).
 *
 * 소울류: 좌클릭 약공격 · Shift+좌클릭 강공격(홀드 차지) · 우클릭 가드(홀드) · F 패링 · Space 구르기/달리기 · C 점프.
 * 누르고 있어야 하는 동작(차지 · 가드)은 뗄 때 *_RELEASE 를 보냄. 키를 바꿔도 "그 동작에 묶인 키" 기준.
 */
public final class ComboResolver {
	private static final List<Consumer<CombatInput>> LISTENERS = new ArrayList<>();
	/** 강공격 · 가드 키를 누르고 있음 (뗄 때 알림) */
	private static boolean heavyDown;
	private static boolean guardDown;
	/** Space 를 누르고 있는 틱 (0 = 안 누름) · 이번 틱 달리기 중 · 점프 요청 */
	private static int evadeHeld;
	private static boolean sprintHeld;
	private static boolean jumpRequested;

	private ComboResolver() {}

	public static void listen(Consumer<CombatInput> listener) {
		LISTENERS.add(listener);
	}

	/** 휠 입력 (MouseHandlerScrollMixin 에서) */
	public static void scrolled(int steps) {
		if (steps == 0) {
			return;
		}
		// 바닐라 핫바와 같은 방향: 휠 위 = 이전
		CombatInput dir = steps > 0 ? CombatInput.ITEM_PREV : CombatInput.ITEM_NEXT;
		for (int i = 0; i < Math.abs(steps); i++) {
			emit(dir);
		}
	}

	/** Space 를 길게 누르고 있음 (달리기) */
	public static boolean sprintHeld() {
		return sprintHeld;
	}

	/** C 점프 요청 (한 번 가져가면 지움) */
	public static boolean takeJump() {
		boolean j = jumpRequested;
		jumpRequested = false;
		return j;
	}

	static void tick(Minecraft mc) {
		if (mc.player == null || mc.gui.screen() != null) {
			clear();
			evadeHeld = 0;
			sprintHeld = false;
			jumpRequested = false;
			return;
		}
		ClientConfig.Input cfg = ClientConfig.get().input;
		evadeKey(cfg);
		if (pressed(HuntKeys.jump)) {
			jumpRequested = true;
		}
		immediate(pressed(HuntKeys.lockOn) | pressed(HuntKeys.lockOnAlt), CombatInput.LOCK_ON_TOGGLE);
		immediate(pressed(HuntKeys.useItem), CombatInput.USE_ITEM);
		immediate(pressed(HuntKeys.itemPrev), CombatInput.ITEM_PREV);
		immediate(pressed(HuntKeys.itemNext), CombatInput.ITEM_NEXT);
		immediate(pressed(HuntKeys.interact), CombatInput.INTERACT);

		attackKeys();
	}

	/**
	 * 좌클릭 = 약공격, Shift + 좌클릭 = 강공격 (누르고 있으면 차지 — 뗄 때 HEAVY_RELEASE).
	 * 우클릭 = 가드 (누르고 있는 동안 — 뗄 때 GUARD_RELEASE), F = 패링.
	 * Shift 는 누른 순간에만 봄 (차지 · 가드 도중에 Shift 를 떼도 그대로).
	 */
	private static void attackKeys() {
		boolean mod = HuntKeys.modifier.isDown();
		if (pressed(HuntKeys.attack)) {
			heavyDown = mod;
			emit(mod ? CombatInput.HEAVY : CombatInput.LIGHT);
		}
		if (heavyDown && !HuntKeys.attack.isDown()) {
			heavyDown = false;
			emit(CombatInput.HEAVY_RELEASE);
		}
		if (pressed(HuntKeys.guard)) {
			guardDown = true;
			emit(CombatInput.GUARD);
		}
		if (pressed(HuntKeys.parry)) {
			emit(CombatInput.PARRY);
		}
		if (guardDown && !HuntKeys.guard.isDown()) {
			guardDown = false;
			emit(CombatInput.GUARD_RELEASE);
		}
	}

	/**
	 * Space: 누르는 동안 틱을 셈 → evadeHoldSeconds 를 넘기면 달리기, 그 전에 떼면 뗄 때 회피 (소울류 — 구르기는 뗄 때 나감).
	 * 한 틱 안에 눌렀다 뗀 것도 회피 1번.
	 */
	private static void evadeKey(ClientConfig.Input cfg) {
		boolean clicked = pressed(HuntKeys.evade);
		boolean down = HuntKeys.evade.isDown();
		int hold = Math.max(1, FixedTick.ticks(cfg.evadeHoldSeconds));
		if (down) {
			evadeHeld++;
		} else if (evadeHeld > 0 || clicked) {
			if (evadeHeld < hold) {
				emit(CombatInput.EVADE);
			}
			evadeHeld = 0;
		}
		sprintHeld = down && evadeHeld >= hold;
	}

	/** 이번 틱에 눌렸는지 — 한 틱(1/60초) 안에 여러 번 눌린 건 1회로 */
	private static boolean pressed(KeyMapping key) {
		boolean any = false;
		while (key.consumeClick()) {
			any = true;
		}
		return any;
	}

	private static void immediate(boolean pressed, CombatInput input) {
		if (pressed) {
			emit(input);
		}
	}

	private static void clear() {
		if (heavyDown) {
			emit(CombatInput.HEAVY_RELEASE);
		}
		if (guardDown) {
			emit(CombatInput.GUARD_RELEASE);
		}
		heavyDown = false;
		guardDown = false;
	}

	private static void emit(CombatInput input) {
		for (Consumer<CombatInput> l : LISTENERS) {
			l.accept(input);
		}
	}
}
