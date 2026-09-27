package kr.huntcraft.progress;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import kr.huntcraft.arena.ArenaBuilder;
import kr.huntcraft.arena.ArenaStore;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.core.FixedTick;
import kr.huntcraft.fx.Effects;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.net.ArenaSyncPayload;
import kr.huntcraft.net.BannerPayload;
import kr.huntcraft.net.BossBarPayload;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * 보스전 흐름 (전장 = ArenaStore 의 부서진 마을, 월드에 하나).
 *   안개 문: 보스를 쓰러뜨리기 전까지 문 틈이 보이지 않는 벽 + 안개 (파티클). 밖에서 E → 안개를 헤치고 들어감 (fog_pass 모션 뒤 안쪽으로)
 *   들어가면: 보스가 없으면 보스 자리에 나오고, 체력 가득 · 행동 켬 → 보스 체력바. 안개는 그대로 (나갈 수 없음)
 *   첫 등장: 보스는 전원이 꺼진 채 앉아 있음 (dormant) — 들어온 헌터가 awakenRange 안으로 다가오거나 때리면 깨어나서 (awaken) 싸움 시작
 *   헌터가 쓰러짐: 전투에서 빠짐 — 안에 남은 헌터가 없으면 보스는 제자리 · 체력 가득 · 행동 끔 (다음 도전 대기, 첫 등장을 못 봤으면 다시 앉음)
 *   보스 처치: 안개가 걷힘 (저장) · "강적 격파" 연출 · 체력바 사라짐
 */
public final class Encounter {
	private static boolean active;
	/** 들어왔지만 보스가 아직 앉아 있음 (다가오면 깨어남) */
	private static boolean awaiting;
	private static int awakenings;
	private static int bossId = -1;
	private static final Set<UUID> FIGHTERS = new HashSet<>();
	/** 안개를 헤치는 중인 헌터 → 남은 틱 */
	private static final Map<UUID, Integer> PASSING = new HashMap<>();
	private static long ticks;
	/** 시험: 들어간 횟수 · 처치 · 초기화 */
	private static int entries;
	private static int victories;
	private static int resets;

	private Encounter() {}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(Encounter::tick);
		// 월드마다 새로 (같은 프로세스에서 월드를 여러 번 열 때 — 싱글플레이 · 시험)
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			active = false;
			awaiting = false;
			bossId = -1;
			FIGHTERS.clear();
			PASSING.clear();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> server.execute(() -> syncArena(handler.player)));
	}

	public static boolean active() {
		return active;
	}

	public static int bossId() {
		return bossId;
	}

	public static int entries() {
		return entries;
	}

	public static int victories() {
		return victories;
	}

	public static int resets() {
		return resets;
	}

	public static boolean fighting(ServerPlayer p) {
		return FIGHTERS.contains(p.getUUID());
	}

	public static int awakenings() {
		return awakenings;
	}

	public static boolean awaiting() {
		return awaiting;
	}

	/** 전장을 새로 만들었을 때 · 초기화 명령 — 이전 전투 상태를 지우고 보스를 첫 등장 자세 (앉아 있음) 로 */
	public static void arenaBuilt(MinecraftServer server) {
		active = false;
		awaiting = false;
		bossId = -1;
		FIGHTERS.clear();
		PASSING.clear();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			syncArena(p);
			send(p, new BossBarPayload(-1, ""));
		}
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (l != null) {
			rest(server, l, findBoss(server, l));
		}
	}

	private static net.minecraft.world.phys.AABB arenaBox(ArenaBuilder.Layout l) {
		return new net.minecraft.world.phys.AABB(l.center()).inflate(l.radius() + 4, 16, l.radius() + 4);
	}

	/** 전장 안의 보스 (없으면 null) — 월드를 다시 열어도 저장된 보스를 찾음 */
	private static MonsterEntity findBoss(MinecraftServer server, ArenaBuilder.Layout l) {
		ServerLevel level = server.overworld();
		if (level.getEntity(bossId) instanceof MonsterEntity m && !m.isRemoved()) {
			return m;
		}
		for (MonsterEntity m : level.getEntitiesOfClass(MonsterEntity.class, arenaBox(l))) {
			if (!m.isRemoved() && !m.dead()) {
				bossId = m.getId();
				return m;
			}
		}
		return null;
	}

	private static Vec3 spawn(ArenaBuilder.Layout l) {
		return new Vec3(l.bossSpawn().getX() + 0.5, l.bossSpawn().getY(), l.bossSpawn().getZ() + 0.5);
	}

	/**
	 * 싸움 밖 자세: 첫 등장을 아직 못 봤으면 전원이 꺼진 채 앉아 있음, 봤으면 제자리에 서서 대기 (행동 끔).
	 * 보스가 없으면 (쓰러뜨리지 않았을 때) 새로 세움.
	 */
	private static MonsterEntity rest(MinecraftServer server, ArenaBuilder.Layout l, MonsterEntity boss) {
		if (ArenaStore.defeated(server)) {
			return boss;
		}
		if (boss == null) {
			boss = MonsterCommands.spawn(server.overworld(), CommonConfig.get().boss.defaultId, spawn(l), l.bossYaw(), 0.0);
			bossId = boss.getId();
		}
		boss.resetForFight(spawn(l), l.bossYaw(), false);
		kr.huntcraft.monster.MonsterDef d = boss.def();
		if (!ArenaStore.introPlayed(server) && d != null && d.dormant != null) {
			boss.setPowered(false);
			boss.startAction(d.dormant);
		}
		return boss;
	}

	/** 필드 보스 상태 초기화 (명령) — 보스 없앰 · 안개 문 다시 · 처치 · 첫 등장 기록 지움 → 처음 상태 (앉아 있는 보스) */
	public static void resetArena(MinecraftServer server) {
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (l == null) {
			return;
		}
		for (MonsterEntity m : server.overworld().getEntitiesOfClass(MonsterEntity.class, arenaBox(l))) {
			m.discard();
		}
		ArenaStore.setDefeated(server, false);
		ArenaStore.setIntroPlayed(server, false);
		ArenaBuilder.fogWall(server.overworld(), l.gate(), CommonConfig.get().arena, true);
		arenaBuilt(server);
	}

	/** 안쪽 방향 (문 → 가운데, 수평 단위) */
	private static Vec3 inward(ArenaBuilder.Layout l) {
		Vec3 d = new Vec3(l.center().getX() - l.gate().getX(), 0.0, l.center().getZ() - l.gate().getZ());
		return d.lengthSqr() < 1.0E-6 ? new Vec3(0, 0, -1) : d.normalize();
	}

	private static Vec3 gateCenter(ArenaBuilder.Layout l) {
		return new Vec3(l.gate().getX() + 0.5, l.gate().getY(), l.gate().getZ() + 0.5);
	}

	/** 안개 문 밖 가까이에 있는지 (E 로 들어갈 수 있음) */
	public static boolean canEnter(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (l == null || ArenaStore.defeated(server) || PASSING.containsKey(p.getUUID()) || FIGHTERS.contains(p.getUUID())) {
			return false;
		}
		// 문 바깥쪽 fogReach 칸 안 · 문 폭 안 (옆으로 빗겨 서 있으면 안 됨)
		Vec3 in = inward(l);
		Vec3 rel = p.position().subtract(gateCenter(l));
		double along = rel.x * in.x + rel.z * in.z;
		double lateral = Math.hypot(rel.x - in.x * along, rel.z - in.z * along);
		return along < 0.2 && along > -CommonConfig.get().progress.fogReach && Math.abs(rel.y) < 3.0
				&& lateral < CommonConfig.get().arena.gateWidth / 2.0 + 0.8;
	}

	/** 안개를 헤치고 들어감 (fog_pass 모션) — 끝나면 안쪽으로 옮기고 전투 시작 */
	public static void enter(ServerPlayer p) {
		ArenaBuilder.Layout l = ArenaStore.load(p.level().getServer());
		if (l == null) {
			return;
		}
		Vec3 in = inward(l);
		float yaw = (float) Math.toDegrees(Math.atan2(-in.x, in.z));
		HunterServer.forceMotion(p, "fog_pass", yaw);
		PASSING.put(p.getUUID(), FixedTick.ticks(CommonConfig.get().progress.fogPassSeconds));
		Effects.play((ServerLevel) p.level(), "fog_enter", gateCenter(l).add(0.0, 1.5, 0.0));
	}

	private static void tick(MinecraftServer server) {
		ticks++;
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (l == null) {
			return;
		}
		ServerLevel level = server.overworld();
		boolean defeated = ArenaStore.defeated(server);
		CommonConfig.Progress cfg = CommonConfig.get().progress;
		// 안개 (파티클): 문 틈 평면에 흰 안개가 천천히 피어오름
		if (!defeated && cfg.fogParticles > 0 && ticks % 3 == 0) {
			int half = CommonConfig.get().arena.gateWidth / 2;
			int h = CommonConfig.get().arena.gateHeight;
			for (int i = 0; i < cfg.fogParticles; i++) {
				double x = l.gate().getX() + 0.5 + (level.getRandom().nextDouble() * 2.0 - 1.0) * (half + 0.5);
				double y = l.gate().getY() + level.getRandom().nextDouble() * h;
				double z = l.gate().getZ() + 0.5 + (level.getRandom().nextDouble() - 0.5) * 0.6;
				level.sendParticles(ParticleTypes.CLOUD, x, y, z, 1, 0.05, 0.1, 0.05, 0.002);
				if (i % 3 == 0) {
					level.sendParticles(ParticleTypes.WHITE_ASH, x, y, z, 2, 0.2, 0.3, 0.2, 0.0);
				}
			}
		}
		// 안개를 헤치는 중 → 끝나면 안쪽으로
		for (UUID id : PASSING.keySet().toArray(new UUID[0])) {
			int left = PASSING.get(id) - 1;
			if (left > 0) {
				PASSING.put(id, left);
				continue;
			}
			PASSING.remove(id);
			if (server.getPlayerList().getPlayer(id) instanceof ServerPlayer p) {
				Vec3 in = inward(l);
				Vec3 to = gateCenter(l).add(in.scale(cfg.fogInside));
				float yaw = (float) Math.toDegrees(Math.atan2(-in.x, in.z));
				p.teleportTo(level, to.x, to.y, to.z, java.util.Set.of(), yaw, 0.0F, true);
				startFight(server, l, p);
			}
		}
		if (!active) {
			return;
		}
		MonsterEntity boss = level.getEntity(bossId) instanceof MonsterEntity m ? m : null;
		if (boss == null || boss.dead()) {
			if (boss != null) {
				victory(server, l);
			} else {
				reset(server, l);
			}
			return;
		}
		if (awaiting) {
			awakenCheck(server, boss);
		}
		// 나갔거나 (접속 끊김) 전장 밖으로 멀리 간 헌터는 빠짐
		FIGHTERS.removeIf(id -> {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			return p == null || p.distanceToSqr(Vec3.atCenterOf(l.center())) > Math.pow(l.radius() + 20, 2);
		});
		if (FIGHTERS.isEmpty()) {
			reset(server, l);
		}
	}

	private static void startFight(MinecraftServer server, ArenaBuilder.Layout l, ServerPlayer p) {
		MonsterEntity boss = findBoss(server, l);
		FIGHTERS.add(p.getUUID());
		entries++;
		if (!active) {
			boss = rest(server, l, boss);
			active = true;
			kr.huntcraft.monster.MonsterDef d = boss.def();
			// 첫 등장: 앉아 있는 채로 기다림 (다가오면 깨어남) — 체력바도 그때
			awaiting = !ArenaStore.introPlayed(server) && d != null && d.dormant != null;
			if (!awaiting) {
				boss.setAi(true);
			}
		}
		if (!awaiting && boss != null) {
			send(p, new BossBarPayload(boss.getId(), boss.def() == null ? "보스" : boss.def().displayName));
		}
	}

	/** 앉아 있는 보스: 들어온 헌터가 다가오거나 때리면 깨어남 (전원 켜짐 → 일어섬 → 공격) · 체력바 */
	private static void awakenCheck(MinecraftServer server, MonsterEntity boss) {
		kr.huntcraft.monster.MonsterDef d = boss.def();
		boolean near = false;
		for (UUID id : FIGHTERS) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null && p.distanceTo(boss) < CommonConfig.get().progress.awakenRange) {
				near = true;
			}
		}
		if (!near && boss.hp() >= boss.maxHp()) {
			return;
		}
		awaiting = false;
		ArenaStore.setIntroPlayed(server, true);
		boss.setAi(true);
		if (d != null && d.awaken != null) {
			boss.startAction(d.awaken);
		} else {
			boss.setPowered(true);
		}
		awakenings++;
		for (UUID id : FIGHTERS) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				send(p, new BossBarPayload(boss.getId(), d == null ? "보스" : d.displayName));
			}
		}
	}

	/** 헌터가 쓰러짐 (HunterServer 가 부름) — 전투에서 빠지고, 남은 헌터가 없으면 보스 초기화 */
	public static void onHunterDeath(ServerPlayer p) {
		PASSING.remove(p.getUUID());
		if (FIGHTERS.remove(p.getUUID())) {
			send(p, new BossBarPayload(-1, ""));
		}
		MinecraftServer server = p.level().getServer();
		ArenaBuilder.Layout l = ArenaStore.load(server);
		if (active && FIGHTERS.isEmpty() && l != null) {
			reset(server, l);
		}
	}

	/** 보스를 제자리 · 체력 가득 · 행동 끔 (다음 도전 대기 — 첫 등장을 못 봤으면 다시 앉음) */
	private static void reset(MinecraftServer server, ArenaBuilder.Layout l) {
		active = false;
		awaiting = false;
		resets++;
		for (UUID id : FIGHTERS) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				send(p, new BossBarPayload(-1, ""));
			}
		}
		FIGHTERS.clear();
		MonsterEntity m = findBoss(server, l);
		if (m != null) {
			rest(server, l, m);
		}
	}

	/** 보스 처치 — 안개가 걷힘 (저장), 강적 격파 연출 */
	private static void victory(MinecraftServer server, ArenaBuilder.Layout l) {
		active = false;
		victories++;
		ArenaStore.setDefeated(server, true);
		ArenaBuilder.fogWall(server.overworld(), l.gate(), CommonConfig.get().arena, false);
		for (UUID id : FIGHTERS) {
			ServerPlayer p = server.getPlayerList().getPlayer(id);
			if (p != null) {
				send(p, new BannerPayload(BannerPayload.VICTORY));
				send(p, new BossBarPayload(-1, ""));
			}
		}
		FIGHTERS.clear();
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			syncArena(p);
		}
	}

	public static void syncArena(ServerPlayer p) {
		MinecraftServer server = p.level().getServer();
		ArenaBuilder.Layout l = ArenaStore.load(server);
		send(p, l == null ? new ArenaSyncPayload(false, BlockPos.ZERO, BlockPos.ZERO, false)
				: new ArenaSyncPayload(true, l.gate(), l.center(), ArenaStore.defeated(server)));
	}

	private static void send(ServerPlayer p, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
		if (ServerPlayNetworking.canSend(p, payload.type())) {
			ServerPlayNetworking.send(p, payload);
		}
	}
}
