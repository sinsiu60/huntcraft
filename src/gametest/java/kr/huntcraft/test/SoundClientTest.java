package kr.huntcraft.test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.client.sound.SoundDuck;
import kr.huntcraft.config.CommonConfig;
import kr.huntcraft.hunter.HunterServer;
import kr.huntcraft.monster.MonsterCommands;
import kr.huntcraft.monster.MonsterEntity;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * 사운드 강화 (모션 · 판정은 그대로):
 *   sounds.json — huntcraft: 소리마다 변형 2개 이상 · 바닐라보다 넓은 감쇠 · 파일이 실제로 있음
 *   레이어 — 공격마다 예고 → 휘두름 → 쾅 순서로 들림 · 귀로 구분: 일반 (tell) / 가드 불가 (warn) / 재배치 (kick) 가 섞이지 않음
 *   뼈 위치 — 주먹 예고가 보스 가운데가 아닌 주먹 쪽에서 남 · 높낮이 ±5% 무작위
 *   거리 — 45칸 밖에서도 큰 쾅은 들리고 휘두름은 안 들림 · 더킹 — 큰 쾅 순간 다른 소리가 잠깐 낮아졌다 돌아옴
 *   헌터 — 약한 / 센 공격에 맞은 소리가 다름 · 가드 · 가드 붕괴 · 패링 소리가 서로 다름
 */
public final class SoundClientTest implements FabricClientGameTest {
	private static final int Y = 150;
	private static final List<String> FAILURES = new ArrayList<>();
	private static final List<Heard> HEARD = new ArrayList<>();
	private static int golemId;

	private record Heard(String id, Vec3 pos, float pitch, Vec3 golem) {}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (TestFilter.skip(getClass(), false)) {
			return;
		}
		assets(ctx);
		SoundEventListener listener = (instance, set, range) -> {
			Identifier id = instance.getIdentifier();
			if (id.getNamespace().equals("huntcraft")) {
				var mc = net.minecraft.client.Minecraft.getInstance();
				Vec3 g = mc.level != null && mc.level.getEntity(golemId) != null ? mc.level.getEntity(golemId).position() : Vec3.ZERO;
				HEARD.add(new Heard(id.getPath(), new Vec3(instance.getX(), instance.getY(), instance.getZ()), instance.getPitch(), g));
			}
		};
		ctx.runOnClient(mc -> mc.getSoundManager().addListener(listener));
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getServer().runCommand("gamemode survival @a");
			sp.getServer().runCommand("time set noon");
			sp.getServer().runCommand("fill -40 " + Y + " -60 40 " + Y + " 40 minecraft:grass_block");
			sp.getServer().runCommand("fill -40 " + (Y - 1) + " -60 40 " + (Y - 1) + " 40 minecraft:dirt");
			sp.getServer().runCommand("fill -40 " + (Y + 1) + " -60 40 " + (Y + 14) + " 40 minecraft:air");
			sp.getServer().runCommand("tp @a 0.5 " + (Y + 1) + " 12.5 180 0");
			sp.getConnection().waitForChunksRender();
			ctx.waitTicks(30);
			golemId = sp.getServer().computeOnServer(server -> {
				MonsterEntity m = MonsterCommands.spawn(server.overworld(), "iron_golem", new Vec3(0.5, Y + 1, 0.5), 0.0F, 0.0);
				m.setAi(false);
				return m.getId();
			});
			ctx.waitTicks(20);

			Map<String, List<Heard>> byAttack = new LinkedHashMap<>();
			float[] minDuck = {1.0F};
			int duck0 = SoundDuck.triggered();
			for (String a : new String[] {"swing_1", "swing_3", "stomp", "delayed_slam", "sweep_1", "punch"}) {
				byAttack.put(a, run(ctx, sp, a, 3.3, minDuck));
			}
			byAttack.put("grab_reach", run(ctx, sp, "grab_reach", 4.5, minDuck));
			byAttack.put("leap", run(ctx, sp, "leap", 18.0, minDuck));
			byAttack.put("charge", run(ctx, sp, "charge", 14.0, minDuck));

			// 레이어 · 분류
			Set<String> tells = Set.of("golem.tell", "golem.tell_heavy");
			Set<String> swings = Set.of("golem.swing", "golem.swing_heavy");
			Set<String> impacts = Set.of("golem.impact", "golem.impact_heavy", "golem.grab");
			for (var en : byAttack.entrySet()) {
				String a = en.getKey();
				List<String> ids = en.getValue().stream().map(Heard::id).toList();
				log("%s: %s", a, String.join(" → ", ids));
				int tell = firstOf(ids, a.equals("grab_reach") ? Set.of("golem.warn") : tells);
				int swing = firstOf(ids, swings);
				int impact = firstOf(ids, impacts);
				expect(tell >= 0 && swing > tell && impact > swing, a + ": 예고 → 휘두름 → 쾅 순서가 아님 (" + tell + ", " + swing + ", " + impact + ")");
				boolean warn = ids.contains("golem.warn");
				boolean kick = ids.contains("golem.kick");
				expect(warn == a.equals("grab_reach"), a + ": 가드 불가 경고가 " + (warn ? "섞임" : "없음"));
				expect(kick == (a.equals("leap") || a.equals("charge")), a + ": 재배치 발 구름이 " + (kick ? "섞임" : "없음"));
			}
			// 뼈 위치: swing_1 예고는 오른 주먹 (보스 가운데 · 발에서 떨어짐)
			Heard t = byAttack.get("swing_1").stream().filter(h -> h.id().equals("golem.tell")).findFirst().orElse(null);
			double off = t == null ? 0.0 : t.pos().distanceTo(t.golem().add(0.0, 2.7, 0.0));
			log("swing_1 예고 위치: 보스 가운데에서 %.2f칸", off);
			expect(off > 1.0, "예고 소리가 보스 가운데에서 남 (뼈 위치 아님)");
			// 높낮이 무작위
			Set<Integer> pitches = new HashSet<>();
			for (Heard h : HEARD) {
				if (h.id().equals("golem.tell_heavy")) {
					pitches.add(Math.round(h.pitch() * 1000.0F));
				}
			}
			log("golem.tell_heavy 높낮이 %d가지", pitches.size());
			expect(pitches.size() >= 3, "높낮이 무작위가 없음");
			// 헌터 맞은 소리 2단계
			Set<String> all = new HashSet<>(HEARD.stream().map(Heard::id).toList());
			expect(all.contains("hunter.hurt") && all.contains("hunter.hurt_heavy"), "약한 / 센 맞은 소리가 둘 다 나지 않음: " + all);
			// 더킹
			log("더킹: %d번 · 가장 낮을 때 %.2f · 지금 %.2f", SoundDuck.triggered() - duck0, minDuck[0], SoundDuck.current());
			expect(SoundDuck.triggered() > duck0 && minDuck[0] < 0.6F, "큰 쾅에 더킹이 걸리지 않음");
			ctx.waitTicks(150);
			expect(SoundDuck.current() >= 1.0F, "더킹이 풀리지 않음");

			// 거리: 45칸 밖에서 늦게 내려찍기 — 쾅 (64칸) 은 들리고 휘두름 (36칸) 은 안 들림
			// 바닥이 z -60 까지 → 보스 뒤쪽 (-Z) 45칸
			List<Heard> far = run(ctx, sp, "delayed_slam", -45.0, minDuck);
			List<String> farIds = far.stream().map(Heard::id).toList();
			log("45칸 밖: %s", farIds);
			expect(farIds.contains("golem.impact_heavy") && !farIds.contains("golem.swing_heavy"), "들리는 거리가 설정과 다름: " + farIds);
		} finally {
			ctx.runOnClient(mc -> mc.getSoundManager().removeListener(listener));
		}
		if (!FAILURES.isEmpty()) {
			throw new AssertionError("사운드 시험 실패 " + FAILURES.size() + "건:\n  " + String.join("\n  ", FAILURES));
		}
		log("사운드 시험 전부 통과");
	}

	/** sounds.json: 설정에 쓴 huntcraft: 소리가 전부 있고, 변형 2개 이상 · 감쇠 > 16 · 파일이 실제로 있음. 헌터 소리 다섯 개는 서로 다름 */
	private static void assets(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Set<String> ids = new HashSet<>();
			for (var e : CommonConfig.Effect.defaults().values()) {
				if (e.sound.startsWith("huntcraft:")) {
					ids.add(e.sound);
				}
			}
			for (String id : ids) {
				WeighedSoundEvents set = mc.getSoundManager().getSoundEvent(Identifier.parse(id));
				if (set == null) {
					expect(false, "sounds.json 에 없음: " + id);
					continue;
				}
				Set<String> variants = new HashSet<>();
				int minAtt = Integer.MAX_VALUE;
				RandomSource r = RandomSource.create(1);
				for (int i = 0; i < 300; i++) {
					Sound s = set.getSound(r);
					variants.add(s.getLocation() + "@" + s.getPitch().sample(r));
					minAtt = Math.min(minAtt, s.getAttenuationDistance());
					expect(mc.getResourceManager().getResource(s.getPath()).isPresent(), "파일 없음: " + s.getPath());
				}
				expect(variants.size() >= 2, id + ": 변형이 " + variants.size() + "개");
				expect(minAtt > 16, id + ": 감쇠 거리 " + minAtt + " (바닐라 16 이하)");
			}
			Map<String, CommonConfig.Effect> d = CommonConfig.Effect.defaults();
			Set<String> hunter = new HashSet<>();
			for (String n : List.of("hunter_hurt", "hunter_hurt_heavy", "guard_hit", "guard_break", "parry")) {
				hunter.add(d.get(n).sound);
			}
			expect(hunter.size() == 5, "헌터 소리가 겹침: " + hunter);
			log("sounds.json: huntcraft 소리 %d개 확인 · 헌터 소리 %s", ids.size(), hunter);
		});
	}

	/** 공격 하나를 시작해 끝날 때까지 (잡히면 쥐기 · 내리꽂기까지) 들린 huntcraft 소리 */
	private static List<Heard> run(ClientGameTestContext ctx, TestSingleplayerContext sp, String attack, double distance, float[] minDuck) {
		ctx.waitFor(mc -> ClientHunters.local().current() == null, 600);
		sp.getServer().runOnServer(server -> {
			MonsterEntity g = golem(server);
			g.startAction("idle");
			g.teleportTo(0.5, Y + 1, 0.5);
			g.setYRot(0.0F);
			g.setDeltaMovement(Vec3.ZERO);
			HunterServer.get(player(server)).health = CommonConfig.get().player.maxHealth;
		});
		sp.getServer().runCommand(String.format(Locale.ROOT, "tp @a 0.5 %d %.2f 180 0", Y + 1, 0.5 + distance));
		for (int i = 0; i < 15; i++) {
			tick(ctx);
		}
		int from = HEARD.size();
		sp.getServer().runOnServer(server -> golem(server).startAction(attack));
		for (int t = 0; t < 420; t++) {
			tick(ctx);
			minDuck[0] = Math.min(minDuck[0], SoundDuck.current());
			String a = sp.getServer().computeOnServer(server -> golem(server).actionId());
			if (a == null || a.equals("idle") || a.equals("pause") || a.equals("chase")) {
				log("  %s: %d틱 뒤 %s", attack, t, a);
				break;
			}
		}
		ctx.waitTicks(10);
		return new ArrayList<>(HEARD.subList(from, HEARD.size()));
	}

	private static void tick(ClientGameTestContext ctx) {
		ctx.waitTick();
		ctx.runOnClient(mc -> {
			if (mc.level.getEntity(golemId) instanceof MonsterEntity g) {
				double dx = g.getX() - mc.player.getX();
				double dz = g.getZ() - mc.player.getZ();
				float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
				mc.player.setYRot(yaw);
				ShoulderCamera.setAngles(yaw, -10.0F);
			}
		});
	}

	private static int firstOf(List<String> ids, Set<String> set) {
		for (int i = 0; i < ids.size(); i++) {
			if (set.contains(ids.get(i))) {
				return i;
			}
		}
		return -1;
	}

	private static MonsterEntity golem(MinecraftServer server) {
		return (MonsterEntity) server.overworld().getEntity(golemId);
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
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
