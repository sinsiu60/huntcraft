package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import kr.huntcraft.combat.CombatInput;
import kr.huntcraft.client.input.ComboResolver;
import kr.huntcraft.client.input.HuntKeys;
import kr.huntcraft.core.FixedTick;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Phase 0 보완 시험 — 실제 클라이언트 + 싱글플레이 월드.
 *
 * 시간은 전부 서버 틱 수 ÷ 60 (게임 속 초)으로 잽니다. 시험 환경은 틱을 하나씩 직접 돌리므로 실제 시간과 무관합니다.
 *
 * 1) 바닐라 속도: 바닐라 1초 20틱에서의 기준값과 8% 안
 *      플레이어 걷기 4.19 블록/초 · 주민 20칸 낙하 1.25초 · 낮밤 1초에 20 · 걷기 동작 세기
 * 2) 겹치는 키: Space 점프 · Shift 웅크리기 · E 인벤토리 · F 손 바꾸기 · 좌클릭 채굴 · 휠 핫바 가 막힘
 * 3) 조합 입력: 좌+우 동시 / 간격 · 기인+우 동시 / 기인 누른 채 우 · 단독 입력 · 휠
 *
 * 결과는 [huntcraft-test] 줄로 로그에 남습니다. 하나라도 틀리면 AssertionError 로 실패.
 */
public final class Phase0ClientTest implements FabricClientGameTest {
	private static final double WALK_REF = 4.19;
	private static final double FALL_REF = 1.25;
	private static final double CLOCK_REF = 20.0;
	private static final List<CombatInput> EVENTS = new CopyOnWriteArrayList<>();
	private static final List<String> FAILURES = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		ComboResolver.listen(EVENTS::add);
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("weather clear");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);

			tickRates(ctx, sp);
			walk(ctx, sp);
			fall(ctx, sp);
			clock(ctx, sp);
			keys(ctx, sp);
			combos(ctx, sp);
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("Phase 0 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("전부 통과");
	}

	// ── 1) 바닐라 속도 ──────────────────────────────────────

	private static void tickRates(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		float server = sp.getServer().computeOnServer(s -> s.tickRateManager().tickrate());
		float client = ctx.computeOnClient(mc -> mc.level.tickRateManager().tickrate());
		log("틱레이트 서버 %.1f · 클라이언트 %.1f", server, client);
		expect(server == FixedTick.HZ && client == FixedTick.HZ, "틱레이트가 60 이 아님");
	}

	private static void walk(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		Vec3 start = pos(ctx);
		int ticks = 2 * FixedTick.HZ;
		ctx.getInput().holdKeyFor(opts -> opts.keyUp, ticks);
		double speed = horizontal(pos(ctx), start) / 2.0;
		double walkAnim = ctx.computeOnClient(mc -> (double) mc.player.walkAnimation.speed());
		log("걷기 %.2f 블록/초 (기준 %.2f) · 걷기 동작 세기 %.2f", speed, WALK_REF, walkAnim);
		near(speed, WALK_REF, "플레이어 걷기 속도");
		ctx.waitTicks(20);
	}

	private static void fall(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		double groundY = pos(ctx).y;
		sp.getServer().runOnServer(server -> {
			ServerLevel level = server.overworld();
			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			v.snapTo(4.5, groundY + 20.0, 4.5, 0.0F, 0.0F);
			v.addTag("hc_fall");
			level.addFreshEntity(v);
		});
		int t0 = sp.getServer().computeOnServer(MinecraftServer::getTickCount);
		int t1 = -1;
		for (int i = 0; i < 600; i++) {
			ctx.waitTick();
			boolean landed = sp.getServer().computeOnServer(server -> server.overworld()
					.getEntitiesOfClass(Villager.class, new AABB(-10, -100, -10, 20, 400, 20), v -> v.entityTags().contains("hc_fall"))
					.stream().anyMatch(Villager::onGround));
			if (landed) {
				t1 = sp.getServer().computeOnServer(MinecraftServer::getTickCount);
				break;
			}
		}
		sp.getServer().runCommand("kill @e[tag=hc_fall]");
		double seconds = t1 < 0 ? 99.0 : (t1 - t0) / (double) FixedTick.HZ;
		log("주민 20칸 낙하 %.3f초 (기준 %.3f)", seconds, FALL_REF);
		near(seconds, FALL_REF, "주민 낙하 시간 (서버 물리)");
	}

	private static void clock(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		// 시험 월드는 낮밤 주기가 꺼진 채 만들어질 수 있음 — 켜고 잼
		boolean was = sp.getServer().computeOnServer(server -> {
			boolean on = server.getGlobalGameRules().get(GameRules.ADVANCE_TIME);
			server.getGlobalGameRules().set(GameRules.ADVANCE_TIME, true, server);
			return on;
		});
		log("낮밤 게임룰 원래 값 %s → true", was);
		ctx.waitTicks(10);
		long[] before = clockAndTick(sp);
		ctx.waitTicks(3 * FixedTick.HZ);
		long[] after = clockAndTick(sp);
		double gameSeconds = (after[1] - before[1]) / (double) FixedTick.HZ;
		double perSecond = (after[0] - before[0]) / gameSeconds;
		log("낮밤 시계 %.1f/초 (기준 %.1f)", perSecond, CLOCK_REF);
		near(perSecond, CLOCK_REF, "낮밤 속도");
	}

	private static long[] clockAndTick(TestSingleplayerContext sp) {
		return sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			long clock = level.dimensionType().defaultClock().map(c -> server.clockManager().getTotalTicks(c)).orElse(-1L);
			return new long[] {clock, server.getTickCount()};
		});
	}

	// ── 2) 겹치는 키 ────────────────────────────────────────

	private static void keys(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		home(ctx, sp);
		ctx.waitTicks(10);

		// Space: 점프 없이 회피만
		EVENTS.clear();
		double y0 = pos(ctx).y;
		double apex = y0;
		ctx.getInput().holdKeyFor(HuntKeys.evade, 2);
		for (int i = 0; i < 40; i++) {
			ctx.waitTick();
			apex = Math.max(apex, pos(ctx).y);
		}
		log("Space: 높이 변화 %.3f · 입력 %s", apex - y0, EVENTS);
		expect(apex - y0 < 0.05, "Space 에 바닐라 점프가 남아 있음");
		expect(EVENTS.equals(List.of(CombatInput.EVADE)), "Space 가 회피로 해석되지 않음: " + EVENTS);

		// Shift: 웅크리기 없이 보조 키만 (강공격 · 패링)
		ctx.getInput().holdKey(HuntKeys.modifier);
		ctx.waitTicks(10);
		boolean crouching = ctx.computeOnClient(mc -> mc.player.isShiftKeyDown());
		boolean modDown = ctx.computeOnClient(mc -> HuntKeys.modifier.isDown());
		ctx.getInput().releaseKey(HuntKeys.modifier);
		log("Shift: 웅크림 %s · 보조 키 %s", crouching, modDown);
		expect(!crouching && modDown, "Shift 에 바닐라 웅크리기가 남아 있음");

		// E: 인벤토리가 열리지 않음 (상호작용 — Phase 7)
		ctx.getInput().pressKey(HuntKeys.interact);
		ctx.waitTicks(5);
		boolean screen = ctx.computeOnClient(mc -> mc.gui.screen() != null);
		// R: 소모품 사용
		EVENTS.clear();
		ctx.getInput().pressKey(HuntKeys.useItem);
		ctx.waitTicks(5);
		log("E: 화면 열림 %s · R: 입력 %s", screen, EVENTS);
		expect(!screen, "E 에 바닐라 인벤토리가 남아 있음");
		expect(EVENTS.equals(List.of(CombatInput.USE_ITEM)), "R 이 소모품 사용으로 해석되지 않음: " + EVENTS);

		// C: 점프 (바닐라 핫바 저장 키 기능 없이)
		sp.getServer().runOnServer(server -> player(server).setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STICK)));
		ctx.waitTicks(5);
		// R 로 시작한 마시기 모션이 끝나야 점프 가능 (모션 중에는 점프 없음)
		ctx.waitFor(mc -> kr.huntcraft.client.combat.ClientHunters.local().current() == null, 300);
		ctx.waitTicks(5);
		double fy0 = pos(ctx).y;
		double fApex = fy0;
		ctx.getInput().pressKey(HuntKeys.jump);
		for (int i = 0; i < 30; i++) {
			ctx.waitTick();
			fApex = Math.max(fApex, pos(ctx).y);
		}
		boolean stillMain = sp.getServer().computeOnServer(server -> player(server).getMainHandItem().is(Items.STICK));
		log("C: 주 손 막대기 유지 %s · 점프 높이 %.2f", stillMain, fApex - fy0);
		expect(fApex - fy0 > 1.0, "C 로 점프하지 않음");
		// F: 손 바꾸기 없이 패링
		ctx.waitFor(mc -> kr.huntcraft.client.combat.ClientHunters.local().current() == null, 300);
		EVENTS.clear();
		ctx.getInput().pressKey(HuntKeys.parry);
		ctx.waitTicks(10);
		boolean stillMain2 = sp.getServer().computeOnServer(server -> player(server).getMainHandItem().is(Items.STICK));
		log("F: 주 손 막대기 유지 %s · 입력 %s", stillMain2, EVENTS);
		expect(stillMain2, "F 에 바닐라 손 바꾸기가 남아 있음");
		expect(EVENTS.equals(List.of(CombatInput.PARRY)), "F 가 패링으로 해석되지 않음: " + EVENTS);

		// 좌클릭: 발밑 블록을 1.5초 눌러도 안 부서짐
		BlockPos below = sp.getServer().computeOnServer(server -> player(server).blockPosition().below());
		sp.getServer().runOnServer(server -> server.overworld().setBlockAndUpdate(below, Blocks.DIRT.defaultBlockState()));
		ctx.getInput().lookAt(below);
		ctx.waitTicks(5);
		ctx.getInput().holdMouseFor(GLFW.GLFW_MOUSE_BUTTON_LEFT, 90);
		ctx.waitTicks(20);
		boolean intact = sp.getServer().computeOnServer(server -> server.overworld().getBlockState(below).is(Blocks.DIRT));
		log("좌클릭: 흙 블록 남음 %s", intact);
		expect(intact, "좌클릭에 바닐라 채굴이 남아 있음");
		ctx.getInput().lookAt(0.0F, 0.0F);
		ctx.waitTicks(10);

		// 휠: 핫바 그대로, 아이템 선택 입력
		EVENTS.clear();
		int slot0 = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		ctx.getInput().scroll(-1.0);
		ctx.waitTicks(3);
		int slot1 = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
		log("휠: 핫바 %d → %d · 입력 %s", slot0, slot1, EVENTS);
		expect(slot0 == slot1, "휠에 바닐라 핫바 선택이 남아 있음");
		expect(EVENTS.equals(List.of(CombatInput.ITEM_NEXT)), "휠이 아이템 선택으로 해석되지 않음: " + EVENTS);
	}

	// ── 3) 공격 · 가드 입력 (소울류) ─────────────────────────

	private static void combos(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		int left = GLFW.GLFW_MOUSE_BUTTON_LEFT;
		int right = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
		combo(ctx, "좌클릭", List.of(CombatInput.LIGHT), () -> ctx.getInput().pressMouse(left));
		combo(ctx, "Shift+좌클릭", List.of(CombatInput.HEAVY, CombatInput.HEAVY_RELEASE), () -> {
			ctx.getInput().holdKey(HuntKeys.modifier);
			ctx.waitTick();
			ctx.getInput().pressMouse(left);
			ctx.waitTick();
			ctx.getInput().releaseKey(HuntKeys.modifier);
		});
		combo(ctx, "Shift+좌 20틱 누르기 (차지)", List.of(CombatInput.HEAVY, CombatInput.HEAVY_RELEASE), () -> {
			ctx.getInput().holdKey(HuntKeys.modifier);
			ctx.waitTick();
			ctx.getInput().holdMouse(left);
			ctx.waitTicks(20);
			boolean onlyHeavy = EVENTS.equals(List.of(CombatInput.HEAVY));
			expect(onlyHeavy, "누르고 있는 동안 강공격 뗌이 먼저 옴: " + EVENTS);
			ctx.getInput().releaseMouse(left);
			ctx.getInput().releaseKey(HuntKeys.modifier);
		});
		combo(ctx, "우클릭 20틱 (가드)", List.of(CombatInput.GUARD, CombatInput.GUARD_RELEASE), () -> {
			ctx.getInput().holdMouse(right);
			ctx.waitTicks(20);
			ctx.getInput().releaseMouse(right);
		});
		combo(ctx, "F (패링)", List.of(CombatInput.PARRY), () -> ctx.getInput().pressKey(HuntKeys.parry));
	}

	private static void combo(ClientGameTestContext ctx, String name, List<CombatInput> expected, Runnable action) {
		ctx.waitTicks(15);
		EVENTS.clear();
		action.run();
		ctx.waitTicks(15);
		List<CombatInput> got = List.copyOf(EVENTS);
		log("조합 %-22s → %s", name, got);
		expect(got.equals(expected), "조합 [" + name + "] 기대 " + expected + " 실제 " + got);
	}

	// ── 도우미 ──────────────────────────────────────────────

	private static void home(ClientGameTestContext ctx, TestSingleplayerContext sp) {
		sp.getServer().runOnServer(server -> {
			ServerPlayer p = player(server);
			int y = p.level().getHeight(Heightmap.Types.MOTION_BLOCKING, 0, 0);
			p.teleportTo(0.5, y, 0.5);
			p.setYRot(0.0F);
			p.setXRot(0.0F);
			p.setDeltaMovement(Vec3.ZERO);
		});
		ctx.getInput().lookAt(0.0F, 0.0F);
		ctx.waitTicks(30);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static Vec3 pos(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> mc.player.position());
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	private static void near(double actual, double reference, String what) {
		expect(Math.abs(actual - reference) / reference <= 0.08,
				String.format(Locale.ROOT, "%s: %.3f (기준 %.3f, 허용 8%%)", what, actual, reference));
	}

	private static void expect(boolean ok, String failure) {
		if (!ok) {
			FAILURES.add(failure);
			log("실패: %s", failure);
		}
	}

	private static void log(String format, Object... args) {
		System.out.println("[huntcraft-test] " + String.format(Locale.ROOT, format, args));
	}
}
