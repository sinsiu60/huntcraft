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
 * WASD 는 바닐라 이동 키를 그대로 읽습니다. 조합(좌+우 = 물러나 베기, 기인+우 = 간파베기)은 키를 따로 두지 않고
 * {@link ComboResolver} 가 기본 키 조합으로 판정합니다 (키를 바꿔도 조합이 따라감). 마우스 휠은 MouseHandlerScrollMixin.
 *
 * 바닐라 키와 겹치면 바닐라 쪽 기능을 없앱니다 (KeyMappingMixin) — 판정은 "지금 묶인 물리 키" 기준이라
 * 바닐라 동작을 다른 키로 옮기면 그 키에서는 다시 동작합니다. 기본값으로 막히는 바닐라 동작:
 *   좌클릭 공격/채굴 · 우클릭 사용/설치 · Shift 웅크리기 · Ctrl 달리기 · Space 점프 · E 인벤토리 · F 손 바꾸기 · Tab 플레이어 목록 · 휠 핫바
 */
public final class HuntKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(HuntCraft.id("huntcraft"));
	private static final List<KeyMapping> ALL = new ArrayList<>();

	/** Space: 짧게 = 구르기 (방향 없으면 백스텝) · 길게 = 달리기 */
	public static KeyMapping evade;
	public static KeyMapping jump;
	/** 패링 (F) */
	public static KeyMapping parry;
	/** Shift: 강공격 (Shift + 좌클릭) 보조 키 — 바닐라 웅크리기 대신 */
	public static KeyMapping modifier;
	public static KeyMapping attack;
	/** 우클릭: 가드 (누르고 있는 동안) */
	public static KeyMapping guard;
	public static KeyMapping lockOn;
	public static KeyMapping lockOnAlt;
	public static KeyMapping useItem;
	public static KeyMapping itemPrev;
	public static KeyMapping itemNext;
	/** 상호작용 (체크포인트 · 문, Phase 7) — 미리 잡아 둠 (비우면 바닐라 인벤토리가 열림) */
	public static KeyMapping interact;

	public static KeyMapping debugShapes;
	public static KeyMapping debugInfo;
	public static KeyMapping reloadConfig;

	private HuntKeys() {}

	public static void register() {
		evade = key("evade", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SPACE);
		jump = key("jump", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_C);
		parry = key("parry", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F);
		modifier = key("modifier", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_SHIFT);
		attack = key("attack", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_LEFT);
		guard = key("guard", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_RIGHT);
		lockOn = key("lock_on", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Q);
		lockOnAlt = key("lock_on_alt", InputConstants.Type.MOUSE, GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
		useItem = key("use_item", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R);
		itemPrev = key("item_prev", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP);
		itemNext = key("item_next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN);
		interact = key("interact", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_E);

		debugShapes = key("debug_shapes", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7);
		debugInfo = key("debug_info", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8);
		reloadConfig = key("reload_config", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9);

		ComboResolver.listen(DebugState::input);
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(HuntKeys::migrateLayout);
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
			ComboResolver.tick(mc);
		});
	}

	/**
	 * 키 배치 바꿈 이전 (한 번만): 옛 기본값 그대로인 키만 새 기본값으로 — 사용자가 직접 바꾼 키는 그대로.
	 *   2: 점프 F → C (F 는 패링)
	 * 바닐라가 options.txt 에 키를 저장하므로 기본값만 바꾸면 기존 설치에는 옛 키가 남음.
	 */
	private static void migrateLayout(net.minecraft.client.Minecraft mc) {
		ClientConfig.Input cfg = ClientConfig.get().input;
		if (cfg.keyLayout >= 2) {
			return;
		}
		InputConstants.Key f = InputConstants.getKey("key.keyboard.f");
		if (f.equals(KeyMappingHelper.getBoundKeyOf(jump))) {
			jump.setKey(jump.getDefaultKey());
			KeyMapping.resetMapping();
			mc.options.save();
			kr.huntcraft.HuntCraft.LOGGER.info("[HuntCraft] 키 배치: 점프 F → C (F = 패링)");
		}
		cfg.keyLayout = 2;
		kr.huntcraft.config.ConfigIO.save("client.json", ClientConfig.get());
	}

	public static boolean isOurs(KeyMapping mapping) {
		return ALL.contains(mapping);
	}

	/** 이 물리 키가 HuntCraft 동작에 묶여 있는지 */
	public static boolean owns(InputConstants.Key key) {
		if (key == null || key.equals(InputConstants.UNKNOWN)) {
			return false;
		}
		for (KeyMapping m : ALL) {
			if (key.equals(KeyMappingHelper.getBoundKeyOf(m))) {
				return true;
			}
		}
		return false;
	}

	private static KeyMapping key(String name, InputConstants.Type type, int code) {
		KeyMapping k = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.huntcraft." + name, type, code, CATEGORY));
		ALL.add(k);
		return k;
	}
}
