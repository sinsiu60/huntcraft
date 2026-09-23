package kr.huntcraft.client.input;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.client.config.ClientConfig;
import kr.huntcraft.client.debug.DebugState;
import kr.huntcraft.config.CommonConfig;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * 조작 키 — 옵션 → 조작 → "HuntCraft" 에서 전부 변경 가능.
 *
 * WASD 는 바닐라 이동 키를 그대로 읽습니다. 조합 입력(좌+우 동시 = 물러나 베기, 기인 + 우 = 간파베기)은
 * 키를 따로 두지 않고 Phase 3 입력 해석기가 기본 키 조합으로 판정합니다 (키를 바꿔도 조합이 따라감).
 * 아이템 선택(마우스 휠)은 키바인딩이 아니라 스크롤 입력이라 Phase 5 에서 처리합니다.
 *
 * Phase 0: 키 등록 + 디버그 표시만. 바닐라 동작(좌클릭 공격 · Shift 웅크리기 · E 인벤토리 · F 손 바꾸기 · Tab 목록)
 * 과 겹치는 키는 Phase 1/3 입력 Mixin 에서 전투 모드일 때 바닐라 쪽을 막습니다.
 */
public final class HuntKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HuntCraft.id("huntcraft"));
	private static final List<KeyMapping> ACTIONS = new ArrayList<>();

	public static KeyMapping sprint;
	public static KeyMapping evade;
	public static KeyMapping attack;
	public static KeyMapping thrust;
	public static KeyMapping spirit;
	public static KeyMapping sheathe;
	public static KeyMapping lockOn;
	public static KeyMapping useItem;

	public static KeyMapping debugShapes;
	public static KeyMapping debugInfo;
	public static KeyMapping reloadConfig;

	private HuntKeys() {}

	public static void register() {
		sprint = action("sprint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT);
		evade = action("evade", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE);
		attack = action("attack", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		thrust = action("thrust", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		spirit = action("spirit", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_CONTROL);
		sheathe = action("sheathe", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F);
		lockOn = action("lock_on", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_TAB);
		useItem = action("use_item", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E);

		debugShapes = key("debug_shapes", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7);
		debugInfo = key("debug_info", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8);
		reloadConfig = key("reload_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (debugShapes.consumeClick()) {
				DebugState.showShapes = !DebugState.showShapes;
			}
			while (debugInfo.consumeClick()) {
				DebugState.showInfo = !DebugState.showInfo;
			}
			while (reloadConfig.consumeClick()) {
				ClientConfig.load();
				// 싱글플레이에서는 공용 설정도 같은 프로세스라 함께 다시 읽음 (멀티는 /huntcraft reload)
				if (mc.hasSingleplayerServer()) {
					CommonConfig.load();
				}
				DebugState.note("설정 다시 읽음");
			}
			for (KeyMapping k : ACTIONS) {
				while (k.consumeClick()) {
					DebugState.note(k.getName());
				}
			}
		});
	}

	private static KeyMapping action(String name, InputConstants.Type type, int code) {
		KeyMapping k = key(name, type, code);
		ACTIONS.add(k);
		return k;
	}

	private static KeyMapping key(String name, InputConstants.Type type, int code) {
		return KeyMappingHelper.registerKeyMapping(new KeyMapping("key.huntcraft." + name, type, code, CATEGORY));
	}
}
