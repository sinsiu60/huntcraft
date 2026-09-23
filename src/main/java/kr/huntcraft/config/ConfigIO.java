package kr.huntcraft.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import kr.huntcraft.HuntCraft;
import net.fabricmc.loader.api.FabricLoader;

/**
 * config/huntcraft/*.json 읽기 · 쓰기.
 *
 * 설정 클래스는 필드 초기값이 곧 기본값입니다. 파일에 없는 항목은 기본값으로 채운 뒤 다시 저장하므로,
 * 모드가 업데이트되어 새 항목이 생겨도 기존 파일의 튜닝 값은 그대로 남고 새 항목만 추가됩니다.
 * 파일이 깨져 있으면 덮어쓰지 않고 기본값으로 실행합니다 (손으로 고친 내용을 날리지 않게).
 */
public final class ConfigIO {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private ConfigIO() {}

	public static Path dir() {
		return FabricLoader.getInstance().getConfigDir().resolve(HuntCraft.MOD_ID);
	}

	public static <T> T load(String fileName, Class<T> type, T defaults) {
		Path file = dir().resolve(fileName);
		T value = defaults;
		if (Files.isRegularFile(file)) {
			try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				T read = GSON.fromJson(r, type);
				if (read != null) {
					value = read;
				}
			} catch (IOException | JsonParseException e) {
				HuntCraft.LOGGER.error("설정 파일 {} 을 읽지 못해 기본값으로 실행합니다 (파일은 그대로 둡니다): {}", file, e.getMessage());
				return defaults;
			}
		}
		save(fileName, value);
		return value;
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
