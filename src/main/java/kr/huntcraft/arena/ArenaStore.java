package kr.huntcraft.arena;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.huntcraft.HuntCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 만든 전장 자리 — 월드 폴더/huntcraft/arena.json (체크포인트 · 안개 문 · 보스 자리를 이후 Phase 7 이 씀).
 * 월드마다 하나 (다시 만들면 덮어씀).
 */
public final class ArenaStore {
	private static ArenaBuilder.Layout cached;
	private static Path cachedFor;

	private ArenaStore() {}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("huntcraft").resolve("arena.json");
	}

	public static void save(MinecraftServer server, ArenaBuilder.Layout l) {
		JsonObject o = new JsonObject();
		o.add("center", pos(l.center()));
		o.addProperty("radius", l.radius());
		o.addProperty("fightRadius", l.fightRadius());
		o.add("gate", pos(l.gate()));
		o.add("checkpoint", pos(l.checkpoint()));
		o.add("bossSpawn", pos(l.bossSpawn()));
		o.addProperty("bossYaw", l.bossYaw());
		o.addProperty("seed", l.seed());
		o.addProperty("defeated", false);
		o.addProperty("introPlayed", false);
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HuntCraft.LOGGER.error("[HuntCraft] 전장 저장 실패: {}", f, e);
		}
		cached = l;
		cachedFor = f;
	}

	/** 없으면 null */
	public static ArenaBuilder.Layout load(MinecraftServer server) {
		Path f = file(server);
		if (f.equals(cachedFor)) {
			return cached;
		}
		cachedFor = f;
		cached = null;
		if (!Files.exists(f)) {
			return null;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			cached = new ArenaBuilder.Layout(pos(o, "center"), o.get("radius").getAsInt(), o.get("fightRadius").getAsInt(), pos(o, "gate"),
					pos(o, "checkpoint"), pos(o, "bossSpawn"), o.get("bossYaw").getAsFloat(), o.get("seed").getAsLong());
		} catch (IOException | RuntimeException e) {
			HuntCraft.LOGGER.error("[HuntCraft] 전장 읽기 실패: {}", f, e);
		}
		return cached;
	}

	/** 첫 등장 연출 (앉아 있다 깨어남) 을 봤는지 */
	public static boolean introPlayed(MinecraftServer server) {
		return flag(server, "introPlayed");
	}

	public static void setIntroPlayed(MinecraftServer server, boolean value) {
		setFlag(server, "introPlayed", value);
	}

	/** 이 전장의 보스를 쓰러뜨렸는지 (안개 문이 사라짐 — 전장을 다시 만들면 false) */
	public static boolean defeated(MinecraftServer server) {
		return flag(server, "defeated");
	}

	public static void setDefeated(MinecraftServer server, boolean value) {
		setFlag(server, "defeated", value);
	}

	private static boolean flag(MinecraftServer server, String key) {
		Path f = file(server);
		try {
			if (!Files.exists(f)) {
				return false;
			}
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			return o.has(key) && o.get(key).getAsBoolean();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static void setFlag(MinecraftServer server, String key, boolean value) {
		Path f = file(server);
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			o.addProperty(key, value);
			Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException | RuntimeException e) {
			HuntCraft.LOGGER.error("[HuntCraft] 전장 상태 저장 실패: {}", f, e);
		}
	}

	private static JsonArray pos(BlockPos p) {
		JsonArray a = new JsonArray();
		a.add(p.getX());
		a.add(p.getY());
		a.add(p.getZ());
		return a;
	}

	private static BlockPos pos(JsonObject o, String key) {
		JsonArray a = o.getAsJsonArray(key);
		return new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt());
	}
}
