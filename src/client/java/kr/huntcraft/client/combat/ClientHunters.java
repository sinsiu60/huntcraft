package kr.huntcraft.client.combat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import kr.huntcraft.client.input.ComboResolver;
import kr.huntcraft.client.movement.HunterMovement;
import kr.huntcraft.combat.HunterBody;
import kr.huntcraft.combat.HunterCombat;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterState;
import kr.huntcraft.net.CombatInputPayload;
import kr.huntcraft.net.CombatSyncPayload;
import kr.huntcraft.net.HitDebugPayload;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * 클라이언트의 헌터들 — 내 헌터는 예측(입력 즉시 같은 상태 머신을 돌림), 다른 헌터는 서버 동기화 + 사이 프레임 진행.
 *
 *   내 입력: ComboResolver → 상태 머신에 바로 넣고 서버에도 보냄 (입력한 순간의 이동 입력 방향과 함께)
 *   서버 동기화: 내 헌터는 모션 · 시작 번호가 다르거나 프레임이 3 이상 어긋날 때만 맞춤 (예측이 1~2프레임 앞서는 건 정상)
 *   히트스톱: 서버의 명중 통지(HitDebugPayload)가 오면 내 모션을 그만큼 멈춤
 */
public final class ClientHunters {
	private static final class Remote {
		final HunterCombat combat = new HunterCombat();
		final HunterState state = new HunterState(100.0);
		HunterBody body;
	}

	private static HunterCombat local = new HunterCombat();
	private static HunterBody localBody;
	private static final Map<Integer, Remote> REMOTES = new HashMap<>();
	/** 명중 통지 (디버그 표시 · 시험) */
	private static HitDebugPayload lastOwnHit;

	private ClientHunters() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			local = new HunterCombat();
			localBody = null;
			REMOTES.clear();
		});
		ClientTickEvents.START_CLIENT_TICK.register(ClientHunters::tick);
		ComboResolver.listen(in -> {
			Minecraft mc = Minecraft.getInstance();
			if (!in.combat || mc.player == null) {
				return;
			}
			float[] dir = HunterMovement.inputDirection(mc.player);
			boolean hasDir = dir != null;
			float yaw = hasDir ? dir[0] : mc.player.getYRot();
			// 치명 일격을 넣을 수 있으면 공격은 그 보스 쪽으로
			kr.huntcraft.monster.MonsterEntity crit = kr.huntcraft.combat.CriticalRules.target(mc.player);
			if (crit != null && (in == kr.huntcraft.combat.CombatInput.LIGHT || in == kr.huntcraft.combat.CombatInput.HEAVY)) {
				yaw = kr.huntcraft.combat.CriticalRules.yawTo(mc.player, crit);
				hasDir = true;
			}
			// 락온 중 공격 · 가드는 대상 쪽으로 (시작 선회 한계 안에서), 회피는 입력 방향 그대로 (8방향 변형)
			boolean locked = kr.huntcraft.client.camera.LockOn.locked();
			float lockYaw = kr.huntcraft.client.camera.LockOn.bodyYaw(mc.player);
			if (crit == null && locked && !Float.isNaN(lockYaw) && in != kr.huntcraft.combat.CombatInput.EVADE && !HunterMovement.state().sprinting) {
				yaw = lockYaw;
				hasDir = true;
			}
			local.input(in, yaw, hasDir, locked);
			if (ClientPlayNetworking.canSend(CombatInputPayload.TYPE)) {
				ClientPlayNetworking.send(new CombatInputPayload(in, yaw, hasDir, locked));
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(CombatSyncPayload.TYPE, (msg, ctx) -> onSync(msg));
	}

	public static HunterCombat local() {
		return local;
	}

	public static HitDebugPayload lastOwnHit() {
		return lastOwnHit;
	}

	/** 내 헌터 몸 (플레이어가 바뀌면 새로 만듦 — 리스폰 · 차원 이동) */
	public static HunterBody localBody() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) {
			return null;
		}
		if (localBody == null || localBody.player() != p) {
			localBody = new HunterBody(p, local, HunterMovement.state());
		}
		return localBody;
	}

	/** 엔티티 id 의 헌터 몸 (동기화를 받은 적 없는 다른 플레이어는 null) */
	public static HunterBody body(int entityId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && mc.player.getId() == entityId) {
			return localBody();
		}
		Remote r = REMOTES.get(entityId);
		return r == null ? null : r.body;
	}

	public static List<HunterBody> all() {
		List<HunterBody> out = new ArrayList<>();
		HunterBody me = localBody();
		if (me != null) {
			out.add(me);
		}
		for (Remote r : REMOTES.values()) {
			if (r.body != null && !r.body.player().isRemoved()) {
				out.add(r.body);
			}
		}
		return out;
	}

	/** 서버의 명중 통지 (ShapeDebugRenderer 가 받아서 넘김) */
	public static void onHit(HitDebugPayload msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || msg.attackerId() != mc.player.getId() || msg.damage() < 0.0F) {
			return;
		}
		lastOwnHit = msg;
		kr.huntcraft.client.hud.ClientProgress.onOwnHit(msg);
		// 서버가 이미 멈춘 만큼 — 통지가 오는 사이 흐른 프레임은 서버 동기화가 맞춤
		CommonConfig.Hitstop cfg = CommonConfig.get().hitstop;
		if (cfg.enabled && msg.hitstop() > 0 && local.current() != null) {
			local.applySync(local.motionId(), local.frame(), Math.max(local.hitstop(), msg.hitstop()), local.serial(), local.facing(),
					local.rootYaw());
		}
	}

	private static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			return;
		}
		local.setAirborne(!p.onGround() && !p.isInWater());
		local.setCritReady(kr.huntcraft.combat.CriticalRules.target(p) != null);
		local.tick(HunterMovement.state(), p.getYRot());
		// 모션 데이터의 화면 흔들림 (이번 스텝에 그 프레임을 지났으면)
		kr.huntcraft.combat.MoveSet.Motion m = local.current();
		if (m != null && local.hitstop() == 0) {
			for (kr.huntcraft.combat.MoveSet.Shake sh : m.shakes()) {
				if (local.prevFrame() < sh.frame() && local.frame() >= sh.frame()) {
					kr.huntcraft.client.camera.ShoulderCamera.shake(sh.strength(), sh.duration());
				}
			}
		}
		REMOTES.entrySet().removeIf(e -> mc.level.getEntity(e.getKey()) == null);
		for (Remote r : REMOTES.values()) {
			r.combat.tick(r.state, r.body == null ? 0.0F : r.body.player().getYRot());
		}
	}

	private static void onSync(CombatSyncPayload msg) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null) {
			return;
		}
		if (msg.entityId() == mc.player.getId()) {
			HunterState st = HunterMovement.state();
			boolean differ = !Objects.equals(msg.motionOrNull(), local.motionId()) || msg.serial() != local.serial()
					|| Math.abs(msg.frame() - local.frame()) >= 3;
			if (differ) {
				local.applySync(msg.motionOrNull(), msg.frame(), msg.hitstop(), msg.serial(), msg.facing(), msg.rootYaw());
				st.drawn = msg.drawn();
			} else if (local.current() == null && st.drawn != msg.drawn()) {
				st.drawn = msg.drawn();
			}
			return;
		}
		Entity e = mc.level.getEntity(msg.entityId());
		if (!(e instanceof Player player)) {
			return;
		}
		Remote r = REMOTES.computeIfAbsent(msg.entityId(), k -> new Remote());
		if (r.body == null || r.body.player() != player) {
			r.body = new HunterBody(player, r.combat, r.state);
		}
		r.combat.applySync(msg.motionOrNull(), msg.frame(), msg.hitstop(), msg.serial(), msg.facing(), msg.rootYaw());
		r.state.drawn = msg.drawn();
	}
}
