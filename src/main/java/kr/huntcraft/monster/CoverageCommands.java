package kr.huntcraft.monster;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.net.CoveragePayload;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * 사각지대 커버리지 맵 (관리자 · 디버그)
 *   /huntcraft coverage                 가까운 몬스터 발밑에 맵 표시 + 콘솔 표 (빨강 0 · 주황 1 · 노랑 2~3 · 초록 4+)
 *   /huntcraft coverage track <참/거짓>  선회 (추적 보정) 반영 / 미반영
 *   /huntcraft coverage select <참/거짓> 그 자리에서 고를 수 있는 공격만 셈 (거리 · 각도 조건)
 *   /huntcraft coverage waves <참/거짓>  땅 충격파도 셈 (기본 hitbox 스윕만)
 *   /huntcraft coverage angles <참/거짓> 선택 조건에 각도도 넣기 (거짓 = 거리만 — 제자리 회전 뒤 고르는 경우)
 *   /huntcraft coverage inner <참/거짓>  근접 보조 판정 포함 (거짓 = 빼고 — 전후 비교)
 *   /huntcraft coverage rage <참/거짓>   선택 조건을 2페이즈 (분노 전용 패턴 포함) 로
 *   /huntcraft coverage toggle <패턴>    패턴 하나 끄기 / 켜기
 *   /huntcraft coverage off             맵 지우기
 * 격자 간격 · 반지름 · 프레임당 샘플 수는 common.json coverage.
 */
public final class CoverageCommands {
	private static boolean tracking = true;
	private static boolean selectable = false;
	private static boolean waves = false;
	private static boolean rage = false;
	private static boolean angles = true;
	private static boolean inner = true;
	private static final Set<String> DISABLED = new HashSet<>();
	private static Coverage.Result last;

	private CoverageCommands() {}

	public static Coverage.Result last() {
		return last;
	}

	public static void init() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> dispatcher.register(
				Commands.literal("huntcraft").then(Commands.literal("coverage")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.executes(CoverageCommands::show)
						.then(Commands.literal("track").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							tracking = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("select").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							selectable = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("waves").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							waves = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("angles").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							angles = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("inner").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							inner = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("rage").then(Commands.argument("on", BoolArgumentType.bool()).executes(c -> {
							rage = BoolArgumentType.getBool(c, "on");
							return show(c);
						})))
						.then(Commands.literal("toggle").then(Commands.argument("pattern", StringArgumentType.word())
								.suggests((c, b) -> {
									MonsterEntity m = MonsterCommands.nearestMonster(c.getSource());
									if (m != null && m.def() != null) {
										Coverage.patterns(m.def()).forEach(b::suggest);
									}
									return b.buildFuture();
								})
								.executes(c -> {
									String p = StringArgumentType.getString(c, "pattern");
									if (!DISABLED.remove(p)) {
										DISABLED.add(p);
									}
									return show(c);
								})))
						.then(Commands.literal("off").executes(c -> {
							for (ServerPlayer p : c.getSource().getServer().getPlayerList().getPlayers()) {
								if (ServerPlayNetworking.canSend(p, CoveragePayload.TYPE)) {
									ServerPlayNetworking.send(p, CoveragePayload.clear());
								}
							}
							return 1;
						})))));
	}

	private static int show(CommandContext<CommandSourceStack> c) {
		MonsterEntity m = MonsterCommands.nearestMonster(c.getSource());
		if (m == null || m.def() == null) {
			c.getSource().sendFailure(Component.literal("[HuntCraft] 가까운 몬스터가 없음"));
			return 0;
		}
		Coverage.Result r = compute(m.def());
		for (String line : Coverage.table(r)) {
			HuntCraft.LOGGER.info("[coverage] {}", line);
			c.getSource().sendSuccess(() -> Component.literal(line), false);
		}
		send(c.getSource().getServer().getPlayerList().getPlayers(), m, r);
		return 1;
	}

	/** 지금 설정으로 계산 (시험도 씀) */
	public static Coverage.Result compute(MonsterDef d) {
		CommonConfig.CoverageTool t = CommonConfig.get().coverage;
		last = Coverage.run(d, new Coverage.Options(t.spacing, t.radius, t.substeps, tracking, selectable, waves, rage, angles, inner, Set.copyOf(DISABLED)));
		return last;
	}

	public static void set(boolean track, boolean select, boolean withWaves, boolean inRage, Set<String> disabled) {
		tracking = track;
		selectable = select;
		waves = withWaves;
		rage = inRage;
		angles = true;
		inner = true;
		DISABLED.clear();
		DISABLED.addAll(disabled);
	}

	/** 선택 조건의 각도 포함 여부 (set 뒤에 바꿈) */
	public static void setAngles(boolean on) {
		angles = on;
	}

	/** 근접 보조 판정 포함 여부 (set 뒤에 바꿈) */
	public static void setInner(boolean on) {
		inner = on;
	}

	public static void send(List<ServerPlayer> players, Entity monster, Coverage.Result r) {
		int n = r.cells().size();
		float[] x = new float[n];
		float[] z = new float[n];
		byte[] count = new byte[n];
		for (int i = 0; i < n; i++) {
			Coverage.Cell cell = r.cells().get(i);
			x[i] = (float) cell.x();
			z[i] = (float) cell.z();
			count[i] = (byte) Math.min(127, r.count(cell));
		}
		CoveragePayload msg = new CoveragePayload(monster.getId(), (float) r.options().spacing(), x, z, count);
		for (ServerPlayer p : players) {
			if (ServerPlayNetworking.canSend(p, CoveragePayload.TYPE)) {
				ServerPlayNetworking.send(p, msg);
			}
		}
	}
}
