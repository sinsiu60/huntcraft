package kr.huntcraft.collision;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import kr.huntcraft.HuntCraft;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.net.HitDebugPayload;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 명중 로그 — 채팅(debug.hitLogChat) · 파일 logs/huntcraft-hits.log (debug.hitLogFile) · 클라이언트 디버그 렌더용 패킷.
 * 부위 · 접촉점 · 첫 접촉 시각 · 서브스텝 수 + 대미지 · 강인도 대미지 · 자세 대미지 (계산식 요소), 가드 · 슈퍼아머 · 강인도 결과는 note 로 덧붙임.
 * 채팅은 공격자마다 초당 4줄까지 (빠른 시험 막대가 채팅을 덮지 않게), 파일에는 전부.
 */
public final class HitLog {
	private static final int CHAT_PER_SECOND = 4;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final Map<Integer, int[]> CHAT_BUDGET = new HashMap<>();
	private static Writer file;
	/** 마지막 줄 (시험) */
	private static volatile String lastLine = "";
	private static volatile String lastNote = "";

	public static String lastLine() {
		return lastLine;
	}

	public static String lastNote() {
		return lastNote;
	}

	/** 명중 뒤 결과 한 줄 (가드 · 슈퍼아머 · 대상 강인도) — 파일에, 채팅 설정이면 채팅에도 */
	public static void note(String line) {
		lastNote = line;
		CommonConfig.Debug cfg = CommonConfig.get().debug;
		if (cfg.hitLogFile) {
			writeFile("[" + LocalTime.now().format(TIME) + "]" + line);
		}
	}

	private HitLog() {}

	public static void init() {
		HitSystem.listen(HitLog::onHit);
	}

	private static void onHit(HitSystem.HitEvent e) {
		kr.huntcraft.combat.DamageCalc.Result dmg = e.damage() instanceof kr.huntcraft.combat.DamageCalc.Result r ? r : null;
		String damage = dmg == null ? "" : String.format(Locale.ROOT, "  대미지=%.1f%s 강인도=%.1f 자세=%.1f  (모션값 %.0f × (1-흡수 %.2f) × 부위 %.2f%s%s)",
				dmg.damage(), dmg.weak() ? " [약점]" : "", dmg.poise(), dmg.stance(), dmg.motionValue(), dmg.absorption(), dmg.multiplier(),
				dmg.charge() > 0.0 ? String.format(Locale.ROOT, " · 차지 %.0f%%", dmg.charge() * 100.0) : "", dmg.crit() ? " × 치명" : "");
		if (e.damage() instanceof kr.huntcraft.combat.DamageCalc.MonsterHit mh) {
			damage = String.format(Locale.ROOT, "  대미지=%.1f 강인도=%.1f 가드소모=%.1f%s%s", mh.damage(), mh.poise(), mh.guardStamina(),
					mh.parryable() ? " [패링 가능]" : "", mh.unblockable() ? " [가드 불가]" : "");
		}
		String line = String.format(Locale.ROOT, "%s → %s  부위=%s (%s)%s  인스턴스=#%d  접촉=(%.2f, %.2f, %.2f)  깊이=%.3f  TOI=%.3f  서브스텝=%d%s",
				name(e.attacker()), name(e.target()), e.part(), e.hurtbox(), damage, e.instance(), e.point().x, e.point().y, e.point().z,
				e.depth(), e.toi(), e.substeps(), e.rewindTicks() > 0 ? "  되감기=" + e.rewindTicks() + "틱" : "");
		lastLine = line;
		CommonConfig.Debug cfg = CommonConfig.get().debug;
		if (cfg.hitLogFile) {
			writeFile("[" + LocalTime.now().format(TIME) + "] tick " + e.tick() + "  " + line);
		}
		if (!(e.attacker().self().level() instanceof ServerLevel level)) {
			return;
		}
		int hitstop = 0;
		if (dmg != null && CommonConfig.get().hitstop.enabled) {
			hitstop = dmg.hit().hitstop() >= 0 ? dmg.hit().hitstop() : CommonConfig.get().hitstop.defaultFrames;
		}
		HitDebugPayload payload = new HitDebugPayload(e.attacker().self().getId(), e.target().self().getId(), e.hurtbox(), e.part(),
				e.point().x, e.point().y, e.point().z, (float) e.toi(), e.substeps(), dmg == null ? -1.0F : (float) dmg.damage(),
				dmg != null && dmg.weak(), false, hitstop);
		java.util.Set<ServerPlayer> receivers = new java.util.HashSet<>(PlayerLookup.tracking(e.target().self()));
		if (e.attacker().self() instanceof ServerPlayer attacker) {
			receivers.add(attacker); // 본인 히트스톱
		}
		for (ServerPlayer p : receivers) {
			if (ServerPlayNetworking.canSend(p, HitDebugPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
		if (cfg.hitLogChat && allowChat(e.attacker().self().getId(), e.tick())) {
			// 채팅은 짧게 (부위 · 대미지 · 약점), 전체 내용은 logs/huntcraft-hits.log
			String shortLine = dmg == null
					? String.format(Locale.ROOT, "%s ← %s  %s", e.part(), name(e.attacker()), e.hurtbox())
					: String.format(Locale.ROOT, "%s  %.1f%s  강인도 %.1f · 자세 %.1f (MV %.0f)", e.part(), dmg.damage(), dmg.weak() ? " 약점" : "",
							dmg.poise(), dmg.stance(), dmg.motionValue());
			Component msg = Component.literal("[명중] ").withStyle(ChatFormatting.GOLD)
					.append(Component.literal(shortLine).withStyle(dmg != null && dmg.weak() ? ChatFormatting.GOLD : ChatFormatting.GRAY));
			for (ServerPlayer p : level.players()) {
				if (p.distanceToSqr(e.target().self()) < 64.0 * 64.0) {
					p.sendSystemMessage(msg);
				}
			}
		}
	}

	private static boolean allowChat(int attackerId, long tick) {
		int[] b = CHAT_BUDGET.computeIfAbsent(attackerId, k -> new int[] {0, 0});
		int second = (int) (tick / 60L);
		if (b[0] != second) {
			b[0] = second;
			b[1] = 0;
		}
		return b[1]++ < CHAT_PER_SECOND;
	}

	private static String name(ColliderOwner o) {
		return o.self().getType().getDescription().getString() + "#" + o.self().getId();
	}

	private static synchronized void writeFile(String line) {
		try {
			if (file == null) {
				Path p = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("huntcraft-hits.log");
				Files.createDirectories(p.getParent());
				file = Files.newBufferedWriter(p, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			file.write(line);
			file.write(System.lineSeparator());
			file.flush();
		} catch (IOException ex) {
			HuntCraft.LOGGER.error("명중 로그 파일 쓰기 실패: {}", ex.getMessage());
		}
	}
}
