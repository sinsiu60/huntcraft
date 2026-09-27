package kr.huntcraft.client.hud;

import kr.huntcraft.client.input.ComboResolver;
import kr.huntcraft.combat.CombatInput;
import kr.huntcraft.monster.MonsterEntity;
import kr.huntcraft.net.ArenaSyncPayload;
import kr.huntcraft.net.BannerPayload;
import kr.huntcraft.net.BossBarPayload;
import kr.huntcraft.net.HitDebugPayload;
import kr.huntcraft.net.InteractPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 진행 흐름 (클라이언트): 서버가 보낸 보스 체력바 · 큰 글씨 연출 · 안개 문 자리를 들고 있고, E (상호작용) 를 서버에 보냄.
 * HUD (HuntHud) 가 이 값을 그림. 보스에 넣은 대미지는 체력바 옆에 모아서 (명중 자리에 띄우지 않음).
 */
public final class ClientProgress {
	private static int bossId = -1;
	private static String bossName = "";
	private static int bannerKind = -1;
	private static long bannerStart;
	private static ArenaSyncPayload arena;
	/** 체력바 옆 누적 대미지 · 마지막 명중 시각 */
	private static double damageSum;
	private static long lastDamage;
	private static int interacts;

	private ClientProgress() {}

	public static void register() {
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			bossId = -1;
			bannerKind = -1;
			arena = null;
			damageSum = 0.0;
		});
		ClientPlayNetworking.registerGlobalReceiver(BossBarPayload.TYPE, (msg, ctx) -> {
			bossId = msg.entityId();
			bossName = msg.name();
			damageSum = 0.0;
		});
		ClientPlayNetworking.registerGlobalReceiver(BannerPayload.TYPE, (msg, ctx) -> {
			bannerKind = msg.kind();
			bannerStart = System.nanoTime();
		});
		ClientPlayNetworking.registerGlobalReceiver(ArenaSyncPayload.TYPE, (msg, ctx) -> arena = msg);
		ComboResolver.listen(in -> {
			if (in == CombatInput.INTERACT && ClientPlayNetworking.canSend(InteractPayload.TYPE)) {
				interacts++;
				ClientPlayNetworking.send(InteractPayload.INSTANCE);
			}
		});
	}

	/** 내 공격이 보스에 맞음 (ClientHunters.onHit 에서) */
	public static void onOwnHit(HitDebugPayload msg) {
		if (msg.targetId() == bossId && msg.damage() > 0.0F) {
			long now = System.nanoTime();
			if ((now - lastDamage) / 1.0E9 > kr.huntcraft.client.config.ClientConfig.get().hud.bossDamageKeepSeconds) {
				damageSum = 0.0;
			}
			damageSum += msg.damage();
			lastDamage = now;
		}
	}

	/** 보이는 보스 (없으면 null) */
	public static MonsterEntity boss() {
		Minecraft mc = Minecraft.getInstance();
		if (bossId < 0 || mc.level == null) {
			return null;
		}
		return mc.level.getEntity(bossId) instanceof MonsterEntity m ? m : null;
	}

	public static String bossName() {
		return bossName;
	}

	public static double damageSum() {
		return damageSum;
	}

	/** 마지막 명중 뒤 지난 초 */
	public static double sinceDamage() {
		return lastDamage == 0 ? 1.0E9 : (System.nanoTime() - lastDamage) / 1.0E9;
	}

	public static int bannerKind() {
		return bannerKind;
	}

	/** 연출 시작 뒤 지난 초 */
	public static double bannerAge() {
		return (System.nanoTime() - bannerStart) / 1.0E9;
	}

	public static int interacts() {
		return interacts;
	}

	/** 상호작용 안내 문구 (없으면 null) — 안개 문 밖 · 불 붙은 모닥불 가까이 */
	public static String prompt(LocalPlayer p) {
		kr.huntcraft.config.CommonConfig.Progress cfg = kr.huntcraft.config.CommonConfig.get().progress;
		if (arena != null && arena.has() && !arena.defeated() && bossId < 0) {
			Vec3 gate = Vec3.atBottomCenterOf(arena.gate());
			Vec3 in = new Vec3(arena.center().getX() - arena.gate().getX(), 0.0, arena.center().getZ() - arena.gate().getZ());
			if (in.lengthSqr() > 1.0E-6) {
				in = in.normalize();
				Vec3 rel = p.position().subtract(gate);
				double along = rel.x * in.x + rel.z * in.z;
				double lateral = Math.hypot(rel.x - in.x * along, rel.z - in.z * along);
				if (along < 0.2 && along > -cfg.fogReach && Math.abs(rel.y) < 3.0
						&& lateral < kr.huntcraft.config.CommonConfig.get().arena.gateWidth / 2.0 + 0.8) {
					return "안개 속으로 들어간다";
				}
			}
		}
		int r = (int) Math.ceil(cfg.restRange);
		for (BlockPos q : BlockPos.betweenClosed(p.blockPosition().offset(-r, -1, -r), p.blockPosition().offset(r, 1, r))) {
			BlockState s = p.level().getBlockState(q);
			if (s.getBlock() instanceof CampfireBlock && s.getValue(CampfireBlock.LIT)
					&& Vec3.atCenterOf(q).distanceToSqr(p.position()) <= cfg.restRange * cfg.restRange) {
				return bossId < 0 ? "모닥불 곁에서 쉰다" : null;
			}
		}
		return null;
	}
}
