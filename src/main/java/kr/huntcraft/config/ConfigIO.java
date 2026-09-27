package kr.huntcraft.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import kr.huntcraft.HuntCraft;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/huntcraft/*.json 읽기 · 쓰기.
 *
 * 설정 클래스는 필드 초기값이 곧 기본값입니다. 파일에 없는 항목은 기본값으로 채운 뒤 다시 저장하므로,
 * 모드가 업데이트되어 새 항목이 생겨도 기존 파일의 튜닝 값은 그대로 남고 새 항목만 추가됩니다.
 * 파일이 깨져 있으면 덮어쓰지 않고 기본값으로 실행합니다 (손으로 고친 내용을 날리지 않게).
 *
 * 기본값이 바뀐 항목은 {@link Migration} 으로 옮김: 파일의 configVersion 이 낮고 그 항목이 옛 기본값 그대로면 새 기본값으로
 * (손으로 바꾼 값은 그대로 둠).
 */
public final class ConfigIO {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private ConfigIO() {}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(HuntCraft.MOD_ID);
	}

	/** 기본값 변경: 이 버전에서 path ("camera.distance") 의 기본값이 oldValue → newValue (숫자 · 참거짓 · 문자열) */
	public record Migration(int version, String path, Object oldValue, Object newValue) {}

	public static <T> T load(String fileName, Class<T> type, T defaults) {
		return load(fileName, type, defaults, 1, java.util.List.of());
	}

	public static <T> T load(String fileName, Class<T> type, T defaults, int currentVersion, java.util.List<Migration> migrations) {
		Path file = dir().resolve(fileName);
		T value = defaults;
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				JsonElement tree = JsonParser.parseReader(r);
				if (tree != null && tree.isJsonObject()) {
					migrate(fileName, tree.getAsJsonObject(), currentVersion, migrations);
				}
				T read = GSON.fromJson(tree, type);
				if (read != null) {
					value = read;
				}
			} catch (IOException | JsonParseException | IllegalStateException e) {
				HuntCraft.LOGGER.error("설정 파일 {} 을 읽지 못해 기본값으로 실행합니다 (파일은 그대로 둡니다): {}", file, e.getMessage());
				return defaults;
			}
		}
		save(fileName, value);
		return value;
	}

	private static void migrate(String fileName, JsonObject root, int currentVersion, java.util.List<Migration> migrations) {
		int version = root.has("configVersion") ? root.get("configVersion").getAsInt() : 1;
		for (Migration m : migrations) {
			if (version >= m.version()) {
				continue;
			}
			String[] keys = m.path().split("\\.");
			JsonObject o = root;
			for (int i = 0; i < keys.length - 1 && o != null; i++) {
				o = o.has(keys[i]) && o.get(keys[i]).isJsonObject() ? o.getAsJsonObject(keys[i]) : null;
			}
			String last = keys[keys.length - 1];
			if (o != null && o.has(last) && o.get(last).isJsonPrimitive() && same(o.get(last).getAsJsonPrimitive(), m.oldValue())) {
				o.add(last, GSON.toJsonTree(m.newValue()));
				HuntCraft.LOGGER.info("설정 {}: {} 기본값 변경 {} → {}", fileName, m.path(), m.oldValue(), m.newValue());
			}
		}
		root.addProperty("configVersion", currentVersion);
	}

	private static boolean same(com.google.gson.JsonPrimitive cur, Object old) {
		if (old instanceof Number n) {
			return cur.isNumber() && Math.abs(cur.getAsDouble() - n.doubleValue()) < 1.0E-9;
		}
		if (old instanceof Boolean b) {
			return cur.isBoolean() && cur.getAsBoolean() == b;
		}
		return cur.isString() && cur.getAsString().equals(String.valueOf(old));
	}

	public static String toJson(Object value) {
		return GSON.toJson(value);
	}

	public static <T> T fromJson(String json, Class<T> type) {
		try {
			return GSON.fromJson(json, type);
		} catch (JsonParseException e) {
			HuntCraft.LOGGER.error("서버 설정을 읽지 못함: {}", e.getMessage());
			return null;
		}
	}

	public static void save(String fileName, Object value) {
		Path file = dir().resolve(fileName);
		try {
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				GSON.toJson(value, w);
			}
		} catch (IOException e) {
			HuntCraft.LOGGER.error("설정 파일 {} 저장 실패: {}", file, e.getMessage());
		}
	}
}
