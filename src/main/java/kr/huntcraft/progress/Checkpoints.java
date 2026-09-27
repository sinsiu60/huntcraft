package kr.huntcraft.progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.config.CommonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

/**
 * 체크포인트 (화톳불 역할) — 불이 붙은 모닥불 가까이에서 E: 휴식 (체력 · 회복 물약 · 스태미나 채움) + 부활 자리 저장.
 * 부활 자리: 마지막으로 쉰 체크포인트 → 없으면 전장의 체크포인트 → 없으면 월드 스폰. 월드 폴더/huntcraft/checkpoints.json 에 저장.
 */
public final class Checkpoints {
	/** 부활 자리 [x, y, z, yaw] */
	private static final Map<UUID, double[]> SPOTS = new HashMap<>();
	private static Path loadedFor;
	private static int rests;

	private Checkpoints() {}

	public static int rests() {
		return rests;
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("huntcraft").resolve("checkpoints.json");
	}

	private static void ensureLoaded(MinecraftServer server) {
		Path f = file(server);
		if (f.equals(loadedFor)) {
			return;
		}
		loadedFor = f;
		SPOTS.clear();
		if (!Files.exists(f)) {
			return;
		}
		try {
			JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : o.entrySet()) {
				JsonArray a = e.getValue().getAsJsonArray();
				SPOTS.put(UUID.fromString(e.getKey()), new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(),
						a.get(3).getAsDouble()});
			}
		} catch (IOException | RuntimeException e) {
			HuntCraft.LOGGER.error("[HuntCraft] 체크포인트 읽기 실패: {}", f, e);
		}
	}

	private static void save(MinecraftServer server) {
		JsonObject o = new JsonObject();
		for (Map.Entry<UUID, double[]> e : SPOTS.entrySet()) {
			JsonArray a = new JsonArray();
			for (double v : e.getValue()) {
				a.add(v);
			}
			o.add(e.getKey().toString(), a);
		}
		Path f = file(server);
		try {
			Files.createDirectories(f.getParent());
			Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			HuntCraft.LOGGER.error("[HuntCraft] 체크포인트 저장 실패: {}", f, e);
		}
	}

	/** 가까운 불 붙은 모닥불 (없으면 null) */
	public static BlockPos near(ServerPlayer p) {
		double reach = CommonConfig.get().progress.restRange;
		int r = (int) Math.ceil(reach);
		BlockPos base = p.blockPosition();
		BlockPos best = null;
		double bestD = reach * reach;
		for (BlockPos q : BlockPos.betweenClosed(base.offset(-r, -1, -r), base.offset(r, 1, r))) {
			BlockState s = p.level().getBlockState(q);
			if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)) {
				double d = Vec3.atCenterOf(q).distanceToSqr(p.position());
				if (d <= bestD) {
					bestD = d;
					best = q.immutable();
				}
			}
		}
		return best;
	}

	/** 휴식: 채우고 부활 자리를 지금 서 있는 곳으로 */
	public static void rest(ServerPlayer p, BlockPos fire) {
		MinecraftServer server = p.level().getServer();
		ensureLoaded(server);
		float yaw = (float) Math.toDegrees(Math.atan2(-(fire.getX() + 0.5 - p.getX()), fire.getZ() + 0.5 - p.getZ()));
		SPOTS.put(p.getUUID(), new double[] {p.getX(), p.getY(), p.getZ(), yaw});
		save(server);
		rests++;
	}

	/** 부활 자리 [x, y, z, yaw] */
	public static double[] respawn(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		ensureLoaded(server);
		double[] s = SPOTS.get(p.getUUID());
		if (s != null) {
			return s;
		}
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (l != null) {
			return new double[] {l.checkpoint().getX() + 0.5, l.checkpoint().getY(), l.checkpoint().getZ() + 0.5, 180.0};
		}
		BlockPos sp = server.overworld().getRespawnData().pos();
		return new double[] {sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0.0};
	}
}
