package kr.huntcraft.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import kr.huntcraft.config.CommonConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * 프루니스의 전장 — 부서진 마을 (절차 생성, 같은 seed 면 같은 모양).
 *
 *   가운데 (fightRadius 안)   평평한 싸움터: 땅 색만 바뀜 (골렘 주먹 자국 · 짓밟힌 흙 · 양귀비) — 걸려 넘어질 턱이 없음
 *   둘레 (fightRadius ~ radius) 무너진 집 · 우물 · 종탑 + 잔해 · 부서진 울타리 (돌진이 벽에 박히는 곳)
 *   바깥 (radius ~ +wallThickness) 들쭉날쭉한 돌 · 흙 벽 (밖이 안 보이게), 남쪽 (+Z) 에 부서진 문 → 통로 → 체크포인트 (모닥불)
 *   보스 자리 = 가운데에서 북쪽 (-Z) bossOffset, 문 쪽을 봄
 *
 * 블록은 한 번에 놓음 (크기 기본 반지름 34 → 약 9만 블록, 1초 안팎). 값은 CommonConfig.arena.
 */
public final class ArenaBuilder {
	/** 만든 전장의 자리 (ArenaStore 가 월드 폴더에 저장) */
	public record Layout(BlockPos center, int radius, int fightRadius, BlockPos gate, BlockPos checkpoint, BlockPos bossSpawn, float bossYaw,
						 long seed) {}

	private final ServerLevel level;
	private final CommonConfig.Arena cfg;
	private final BlockPos c;
	private final int y0;
	private final long seed;
	private final Random rnd;
	private int placed;

	private ArenaBuilder(ServerLevel level, BlockPos center, CommonConfig.Arena cfg, long seed) {
		this.level = level;
		this.cfg = cfg;
		this.c = center;
		this.y0 = center.getY();
		this.seed = seed;
		this.rnd = new Random(seed);
	}

	/** center = 싸움터 가운데 발 높이 (땅 = center.y - 1) */
	public static Layout build(ServerLevel level, BlockPos center, CommonConfig.Arena cfg, long seed) {
		ArenaBuilder b = new ArenaBuilder(level, center, cfg, seed);
		return b.run();
	}

	public int placed() {
		return placed;
	}

	private Layout run() {
		int R = cfg.radius;
		int T = cfg.wallThickness;
		clearAndGround(R + T + 1);
		boundary(R, T);
		BlockPos gate = gate(R, T);
		BlockPos checkpoint = corridor(R, T);
		structures(R);
		debris(R);
		fightArea();
		BlockPos boss = c.offset(0, 0, -cfg.bossOffset);
		return new Layout(c, R, cfg.fightRadius, gate, checkpoint, boss, 0.0F, seed);
	}

	// ── 땅 ────────────────────────────────────────────────

	private void clearAndGround(int reach) {
		for (int dx = -reach; dx <= reach; dx++) {
			for (int dz = -reach; dz <= reach; dz++) {
				if (dx * dx + dz * dz > reach * reach) {
					continue;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				for (int y = y0; y <= y0 + cfg.clearHeight; y++) {
					set(x, y, z, Blocks.AIR.defaultBlockState(), false);
				}
				set(x, y0 - 1, z, surface(x, z, Math.sqrt(dx * dx + dz * dz)), false);
				for (int y = y0 - 2; y >= y0 - 4; y--) {
					set(x, y, z, Blocks.DIRT.defaultBlockState(), false);
				}
				for (int y = y0 - 5; y >= y0 - cfg.groundDepth; y--) {
					set(x, y, z, Blocks.STONE.defaultBlockState(), false);
				}
			}
		}
	}

	/** 윗면: 풀 · 짓밟힌 흙 · 자갈 · 뿌리 흙 (잡음 무늬), 문 → 가운데 흙길 */
	private BlockState surface(int x, int z, double r) {
		double n = noise(x, z, 7.0, 1);
		double m = noise(x, z, 3.0, 2);
		// 문 (남쪽) 에서 가운데로 가는 흙길 (폭 3, 군데군데 끊김)
		if (Math.abs(x - c.getX()) <= 1 && z >= c.getZ() + cfg.fightRadius - 2 && m > 0.2) {
			return Blocks.DIRT_PATH.defaultBlockState();
		}
		if (r < cfg.fightRadius) {
			// 싸움터: 골렘이 오래 짓밟은 땅
			if (n > 0.62) {
				return Blocks.COARSE_DIRT.defaultBlockState();
			}
			if (n < 0.22) {
				return m > 0.5 ? Blocks.GRAVEL.defaultBlockState() : Blocks.ROOTED_DIRT.defaultBlockState();
			}
			return m > 0.8 ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
		}
		if (n > 0.7) {
			return Blocks.COARSE_DIRT.defaultBlockState();
		}
		if (n < 0.18) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		if (m > 0.85) {
			return Blocks.PODZOL.defaultBlockState();
		}
		return Blocks.GRASS_BLOCK.defaultBlockState();
	}

	// ── 바깥 벽 · 문 · 통로 ──────────────────────────────────

	private void boundary(int R, int T) {
		for (int dx = -(R + T); dx <= R + T; dx++) {
			for (int dz = -(R + T); dz <= R + T; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r < R || r > R + T) {
					continue;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (inGate(dx, dz, R, T)) {
					continue;
				}
				// 안쪽 가장자리는 낮게 (비탈) · 바깥으로 갈수록 높게
				double edge = Math.min(1.0, (r - R) / Math.max(1.0, T * 0.6));
				double h = cfg.wallHeightMin + (cfg.wallHeightMax - cfg.wallHeightMin) * noise(x, z, 6.0, 3);
				int top = (int) Math.round(h * (0.35 + 0.65 * edge));
				for (int y = 0; y < top; y++) {
					set(x, y0 + y, z, wallBlock(x, y0 + y, z, y == top - 1), false);
				}
			}
		}
	}

	private BlockState wallBlock(int x, int y, int z, boolean top) {
		double n = noise(x + y * 3, z - y * 5, 2.5, 4);
		if (top && n > 0.55) {
			return Blocks.MOSS_BLOCK.defaultBlockState();
		}
		if (n < 0.3) {
			return Blocks.COBBLESTONE.defaultBlockState();
		}
		if (n < 0.45) {
			return Blocks.MOSSY_COBBLESTONE.defaultBlockState();
		}
		if (n < 0.62) {
			return Blocks.ANDESITE.defaultBlockState();
		}
		if (n < 0.75) {
			return Blocks.TUFF.defaultBlockState();
		}
		return Blocks.STONE.defaultBlockState();
	}

	private boolean inGate(int dx, int dz, int R, int T) {
		return dz > 0 && Math.abs(dx) <= cfg.gateWidth / 2 && dz >= R - 2;
	}

	/** 부서진 돌 문 (기둥 두 개 + 반쯤 무너진 상인방) — 반환 = 문 가운데 (안개 문 자리) */
	private BlockPos gate(int R, int T) {
		int half = cfg.gateWidth / 2;
		int gz = c.getZ() + R + T / 2;
		for (int side : new int[] {-1, 1}) {
			int px = c.getX() + side * (half + 1);
			int h = side < 0 ? cfg.gateHeight : cfg.gateHeight - 3;
			for (int y = 0; y < h; y++) {
				for (int dz = -1; dz <= 1; dz++) {
					set(px, y0 + y, gz + dz, rnd.nextDouble() < 0.25 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
							: rnd.nextDouble() < 0.3 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState(), false);
				}
			}
		}
		// 상인방: 왼쪽 기둥에서 반만 남음
		for (int dx = -half - 1; dx <= 0; dx++) {
			set(c.getX() + dx, y0 + cfg.gateHeight, gz, Blocks.STONE_BRICKS.defaultBlockState(), false);
			set(c.getX() + dx, y0 + cfg.gateHeight - 1, gz, dx == 0 ? Blocks.STONE_BRICK_STAIRS.defaultBlockState()
					.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST).setValue(BlockStateProperties.HALF, Half.TOP)
					: Blocks.AIR.defaultBlockState(), false);
		}
		// 안개 문 (보이지 않는 벽 — 안개는 Encounter 가 파티클로 그림, 보스를 쓰러뜨리면 걷힘)
		fogWall(level, new BlockPos(c.getX(), y0, gz), cfg, true);
		// 떨어진 상인방 조각
		set(c.getX() + 1, y0, gz - 2, Blocks.STONE_BRICK_SLAB.defaultBlockState(), true);
		set(c.getX() + 2, y0, gz - 1, Blocks.CRACKED_STONE_BRICKS.defaultBlockState(), true);
		return new BlockPos(c.getX(), y0, gz);
	}

	/** 안개 문 벽 (문 가운데 gate 기준 폭 gateWidth · 높이 gateHeight) — closed = 막음, false = 걷음 */
	public static void fogWall(ServerLevel level, BlockPos gate, CommonConfig.Arena cfg, boolean closed) {
		int half = cfg.gateWidth / 2;
		for (int dx = -half; dx <= half; dx++) {
			for (int y = 0; y < cfg.gateHeight; y++) {
				BlockPos p = gate.offset(dx, y, 0);
				if (closed && level.getBlockState(p).isAir()) {
					level.setBlock(p, Blocks.BARRIER.defaultBlockState(), Block.UPDATE_ALL);
				} else if (!closed && level.getBlockState(p).is(Blocks.BARRIER)) {
					level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
	}

	/** 문 밖 통로 (양쪽 낮은 돌담) 끝에 체크포인트 모닥불 — 반환 = 헌터가 설 자리 */
	private BlockPos corridor(int R, int T) {
		int half = cfg.gateWidth / 2;
		int z0 = c.getZ() + R + T + 1;
		int z1 = z0 + cfg.corridorLength;
		for (int z = z0; z <= z1 + 3; z++) {
			for (int dx = -half - 2; dx <= half + 2; dx++) {
				int x = c.getX() + dx;
				boolean wall = Math.abs(dx) >= half + 1 && z <= z1;
				for (int y = y0; y <= y0 + 7; y++) {
					set(x, y, z, Blocks.AIR.defaultBlockState(), false);
				}
				set(x, y0 - 1, z, Math.abs(dx) <= 1 ? Blocks.DIRT_PATH.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState(), false);
				for (int y = y0 - 2; y >= y0 - 4; y--) {
					set(x, y, z, Blocks.DIRT.defaultBlockState(), false);
				}
				if (wall) {
					int h = 2 + (int) Math.round(2 * noise(x, z, 3.0, 5));
					for (int y = 0; y < h; y++) {
						set(x, y0 + y, z, wallBlock(x, y0 + y, z, y == h - 1), false);
					}
				}
			}
		}
		// 체크포인트: 돌 둘레 안의 모닥불
		BlockPos fire = new BlockPos(c.getX() + half, y0, z1);
		set(fire.getX(), y0, fire.getZ(), Blocks.CAMPFIRE.defaultBlockState(), true);
		for (int[] o : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
			set(fire.getX() + o[0], y0, fire.getZ() + o[1], Blocks.COBBLESTONE_SLAB.defaultBlockState(), true);
		}
		return new BlockPos(c.getX(), y0, z1 + 1);
	}

	// ── 둘레 건물 ───────────────────────────────────────────

	private enum Kind { HOUSE, WELL, TOWER }

	private void structures(int R) {
		List<Kind> kinds = new ArrayList<>();
		for (int i = 0; i < cfg.houses; i++) {
			kinds.add(Kind.HOUSE);
		}
		kinds.add(Kind.WELL);
		kinds.add(Kind.TOWER);
		Collections.shuffle(kinds, rnd);
		int n = kinds.size();
		// 문 쪽 (+Z) 앞 ±gateClearAngle 은 비움 (들어오는 길)
		double from = Math.toRadians(cfg.gateClearAngle);
		double span = 2.0 * Math.PI - 2.0 * from;
		for (int i = 0; i < n; i++) {
			double theta = from + span * (i + 0.5) / n + (rnd.nextDouble() - 0.5) * span / n * 0.35;
			double r = cfg.fightRadius + 4 + rnd.nextDouble() * (R - cfg.fightRadius - 10);
			int x = c.getX() + (int) Math.round(Math.sin(theta) * r);
			int z = c.getZ() + (int) Math.round(Math.cos(theta) * r);
			// 가운데를 보는 방향 (축에 맞춤)
			double ix = c.getX() - x;
			double iz = c.getZ() - z;
			Direction in = Math.abs(ix) > Math.abs(iz) ? (ix > 0 ? Direction.EAST : Direction.WEST) : (iz > 0 ? Direction.SOUTH : Direction.NORTH);
			switch (kinds.get(i)) {
				case HOUSE -> house(x, z, in);
				case WELL -> well(x, z);
				case TOWER -> tower(x, z, in);
			}
		}
	}

	/** 무너진 집: 돌 아랫단 + 나무 벽 (칸마다 무너진 높이), 모서리 통나무, 가운데 쪽 벽에 문 구멍 · 그 앞으로 쏟아진 잔해 */
	private void house(int hx, int hz, Direction in) {
		int w = 5 + 2 * rnd.nextInt(2);   // 가로 5 · 7
		int d = 5 + 2 * rnd.nextInt(3);   // 깊이 5 · 7 · 9
		double damage = 0.35 + rnd.nextDouble() * 0.55;
		Direction right = in.getClockWise();
		int hw = w / 2;
		int hd = d / 2;
		for (int u = -hw; u <= hw; u++) {
			for (int v = -hd; v <= hd; v++) {
				int x = hx + right.getStepX() * u + in.getStepX() * v;
				int z = hz + right.getStepZ() * u + in.getStepZ() * v;
				boolean edgeU = Math.abs(u) == hw;
				boolean edgeV = Math.abs(v) == hd;
				// 바닥
				set(x, y0 - 1, z, rnd.nextDouble() < 0.6 ? Blocks.COBBLESTONE.defaultBlockState()
						: rnd.nextDouble() < 0.5 ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState(), false);
				if (!edgeU && !edgeV) {
					interior(x, z);
					continue;
				}
				boolean corner = edgeU && edgeV;
				boolean door = v == hd && Math.abs(u) <= (w >= 7 ? 1 : 0);   // 가운데를 보는 벽 (v = +hd 쪽이 in 방향)
				int full = cfg.houseWallHeight;
				int h = (int) Math.round(full * (1.0 - damage * noise(x, z, 2.2, 6)));
				if (corner) {
					h = Math.max(h, (int) Math.round(full * (1.0 - damage * 0.5)) + 1);
				}
				if (door) {
					h = 0;
				}
				for (int y = 0; y < h; y++) {
					BlockState s;
					if (corner) {
						s = Blocks.OAK_LOG.defaultBlockState();
					} else if (y == 0) {
						s = rnd.nextDouble() < 0.8 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
					} else {
						double p = rnd.nextDouble();
						s = p < 0.55 ? Blocks.OAK_PLANKS.defaultBlockState() : p < 0.72 ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
								: p < 0.84 ? Blocks.STRIPPED_OAK_LOG.defaultBlockState() : p < 0.92 ? Blocks.COBBLESTONE.defaultBlockState()
								: Blocks.AIR.defaultBlockState();
					}
					set(x, y0 + y, z, s, false);
				}
				// 무너진 벽 위에 걸친 지붕 조각
				if (h > 1 && !corner && rnd.nextDouble() < 0.3) {
					set(x, y0 + h, z, Blocks.OAK_STAIRS.defaultBlockState()
							.setValue(BlockStateProperties.HORIZONTAL_FACING, rnd.nextBoolean() ? right : right.getOpposite()), true);
				}
			}
		}
		// 문 앞 (가운데 쪽) 으로 쏟아진 잔해
		spill(hx + in.getStepX() * (hd + 1), hz + in.getStepZ() * (hd + 1), in, 3 + rnd.nextInt(2), 2 + rnd.nextInt(2));
		// 쓰러진 들보 (통나무 눕힘)
		if (rnd.nextDouble() < 0.7) {
			Direction.Axis axis = right.getAxis();
			for (int u = -hw + 1; u <= hw - 1 - rnd.nextInt(2); u++) {
				set(hx + right.getStepX() * u, y0, hz + right.getStepZ() * u,
						Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS, axis), false);
			}
		}
	}

	/** 집 안: 떨어진 지붕 반블록 · 거미줄 · 통 · 퇴비통 */
	private void interior(int x, int z) {
		double p = rnd.nextDouble();
		if (p < 0.12) {
			set(x, y0, z, Blocks.OAK_SLAB.defaultBlockState(), false);
		} else if (p < 0.17) {
			set(x, y0, z, Blocks.COBWEB.defaultBlockState(), false);
		} else if (p < 0.2) {
			set(x, y0, z, Blocks.BARREL.defaultBlockState(), false);
		} else if (p < 0.22) {
			set(x, y0, z, Blocks.COMPOSTER.defaultBlockState(), false);
		} else if (p < 0.3) {
			set(x, y0, z, Blocks.LEAF_LITTER.defaultBlockState(), true);
		}
	}

	/** 잔해 더미: dir 쪽으로 length 칸 · 폭 width, 벽에서 멀수록 낮고 성김 */
	private void spill(int x0, int z0, Direction dir, int length, int width) {
		Direction side = dir.getClockWise();
		for (int l = 0; l < length; l++) {
			for (int s = -width; s <= width; s++) {
				double chance = (1.0 - (double) l / length) * (1.0 - Math.abs(s) / (width + 1.0));
				if (rnd.nextDouble() > chance + 0.15) {
					continue;
				}
				int x = x0 + dir.getStepX() * l + side.getStepX() * s;
				int z = z0 + dir.getStepZ() * l + side.getStepZ() * s;
				if (Math.hypot(x - c.getX(), z - c.getZ()) < cfg.fightRadius) {
					continue; // 싸움터는 평평하게
				}
				double p = rnd.nextDouble();
				BlockState b = p < 0.35 ? Blocks.COBBLESTONE.defaultBlockState() : p < 0.55 ? Blocks.GRAVEL.defaultBlockState()
						: p < 0.7 ? Blocks.OAK_PLANKS.defaultBlockState() : p < 0.85 ? Blocks.COBBLESTONE_SLAB.defaultBlockState()
						: Blocks.MOSSY_COBBLESTONE.defaultBlockState();
				set(x, y0, z, b, true);
				if (l == 0 && rnd.nextDouble() < 0.4) {
					set(x, y0 + 1, z, Blocks.COBBLESTONE_SLAB.defaultBlockState(), true);
				}
			}
		}
	}

	/** 우물: 돌 둘레 + 물 (3칸 깊이) + 기울어진 울타리 기둥 */
	private void well(int wx, int wz) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int x = wx + dx;
				int z = wz + dz;
				boolean ring = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				if (ring) {
					if (!(Math.abs(dx) == 2 && Math.abs(dz) == 2) || rnd.nextDouble() < 0.7) {
						set(x, y0, z, rnd.nextDouble() < 0.3 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), false);
					}
					set(x, y0 - 1, z, Blocks.COBBLESTONE.defaultBlockState(), false);
				} else {
					for (int y = y0 - 1; y >= y0 - 3; y--) {
						set(x, y, z, Blocks.WATER.defaultBlockState(), false);
					}
					set(x, y0 - 4, z, Blocks.COBBLESTONE.defaultBlockState(), false);
				}
			}
		}
		// 지붕 기둥: 하나는 서 있고 하나는 부러짐
		for (int y = 1; y <= 3; y++) {
			set(wx - 2, y0 + y, wz, Blocks.OAK_FENCE.defaultBlockState(), true);
		}
		set(wx + 2, y0 + 1, wz, Blocks.OAK_FENCE.defaultBlockState(), true);
		set(wx - 2, y0 + 4, wz, Blocks.OAK_SLAB.defaultBlockState(), true);
		set(wx - 1, y0 + 4, wz, Blocks.OAK_SLAB.defaultBlockState(), true);
	}

	/** 종탑: 속이 빈 돌벽돌 탑이 한쪽으로 무너짐, 종은 땅에 떨어짐 */
	private void tower(int tx, int tz, Direction in) {
		int full = cfg.towerHeight;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				int x = tx + dx;
				int z = tz + dz;
				set(x, y0 - 1, z, Blocks.STONE_BRICKS.defaultBlockState(), false);
				boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2;
				if (!wall) {
					continue;
				}
				// 가운데를 보는 쪽이 무너짐 (그쪽으로 쏟아짐)
				double facing = (dx * in.getStepX() + dz * in.getStepZ()) / 2.0;   // -1 (뒤) ~ 1 (가운데 쪽)
				int h = (int) Math.round(full * (0.75 - 0.55 * facing) * (0.7 + 0.3 * noise(x, z, 1.5, 7)));
				boolean door = dx * in.getStepX() + dz * in.getStepZ() == 2 && Math.abs(dx * in.getStepZ() - dz * in.getStepX()) == 0;
				for (int y = door ? 3 : 0; y < h; y++) {
					double p = rnd.nextDouble();
					set(x, y0 + y, z, p < 0.2 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
							: p < 0.38 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : p < 0.45 ? Blocks.AIR.defaultBlockState()
							: Blocks.STONE_BRICKS.defaultBlockState(), false);
				}
			}
		}
		// 떨어진 종 (바닥에 놓임) · 탑에서 쏟아진 돌
		set(tx, y0, tz, Blocks.BELL.defaultBlockState().setValue(BlockStateProperties.BELL_ATTACHMENT, BellAttachType.FLOOR)
				.setValue(BlockStateProperties.HORIZONTAL_FACING, in), true);
		spill(tx + in.getStepX() * 3, tz + in.getStepZ() * 3, in, 4, 2);
	}

	// ── 흩어진 것 · 싸움터 ────────────────────────────────────

	/** 둘레에 흩어진 잔해 · 부서진 울타리 · 쓰러진 건초 · 등불 · 마른 풀 */
	private void debris(int R) {
		for (int dx = -R; dx <= R; dx++) {
			for (int dz = -R; dz <= R; dz++) {
				double r = Math.sqrt(dx * dx + dz * dz);
				if (r < cfg.fightRadius + 1 || r > R - 1) {
					continue;
				}
				int x = c.getX() + dx;
				int z = c.getZ() + dz;
				if (!level.getBlockState(new BlockPos(x, y0, z)).isAir() || Math.abs(dx) <= 2 && dz > 0) {
					continue;
				}
				double p = rnd.nextDouble();
				double k = cfg.debrisDensity;
				if (p < k * 0.35) {
					set(x, y0, z, rnd.nextBoolean() ? Blocks.COBBLESTONE_SLAB.defaultBlockState() : Blocks.MOSSY_COBBLESTONE_SLAB.defaultBlockState(), true);
				} else if (p < k * 0.55) {
					fence(x, z);
				} else if (p < k * 0.62) {
					set(x, y0, z, Blocks.HAY_BLOCK.defaultBlockState().setValue(BlockStateProperties.AXIS,
							rnd.nextBoolean() ? Direction.Axis.X : Direction.Axis.Z), true);
				} else if (p < k * 0.66) {
					set(x, y0, z, Blocks.LANTERN.defaultBlockState(), true);
				} else if (p < k * 1.4 && level.getBlockState(new BlockPos(x, y0 - 1, z)).is(Blocks.GRASS_BLOCK)) {
					set(x, y0, z, rnd.nextDouble() < 0.5 ? Blocks.SHORT_DRY_GRASS.defaultBlockState() : Blocks.DEAD_BUSH.defaultBlockState(), true);
				}
			}
		}
	}

	/** 부서진 울타리 1~3칸 */
	private void fence(int x, int z) {
		Direction dir = rnd.nextBoolean() ? Direction.EAST : Direction.SOUTH;
		int n = 1 + rnd.nextInt(3);
		for (int i = 0; i < n; i++) {
			int fx = x + dir.getStepX() * i;
			int fz = z + dir.getStepZ() * i;
			if (Math.hypot(fx - c.getX(), fz - c.getZ()) < cfg.fightRadius + 1 || !level.getBlockState(new BlockPos(fx, y0, fz)).isAir()) {
				break;
			}
			set(fx, y0, fz, Blocks.OAK_FENCE.defaultBlockState(), true);
		}
	}

	/** 싸움터: 골렘 주먹 자국 (갈라진 땅 무늬 — 높이는 그대로) · 떨어진 양귀비 (골렘이 아이들에게 주던 꽃) */
	private void fightArea() {
		int F = cfg.fightRadius;
		for (int i = 0; i < cfg.craters; i++) {
			double a = rnd.nextDouble() * Math.PI * 2.0;
			double r = rnd.nextDouble() * (F - 4);
			int x = c.getX() + (int) Math.round(Math.sin(a) * r);
			int z = c.getZ() + (int) Math.round(Math.cos(a) * r);
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					set(x + dx, y0 - 1, z + dz, rnd.nextBoolean() ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), false);
				}
			}
			// 금: 가운데에서 뻗는 선 4~6개
			int rays = 4 + rnd.nextInt(3);
			for (int k = 0; k < rays; k++) {
				double ra = a + k * Math.PI * 2.0 / rays + rnd.nextDouble() * 0.5;
				int len = 3 + rnd.nextInt(3);
				for (int s = 2; s <= len; s++) {
					int px = x + (int) Math.round(Math.sin(ra) * s);
					int pz = z + (int) Math.round(Math.cos(ra) * s);
					if (Math.hypot(px - c.getX(), pz - c.getZ()) < F) {
						set(px, y0 - 1, pz, s == len ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), false);
					}
				}
			}
		}
		for (int i = 0; i < cfg.poppies; i++) {
			double a = rnd.nextDouble() * Math.PI * 2.0;
			double r = 2.0 + rnd.nextDouble() * (F - 3);
			int x = c.getX() + (int) Math.round(Math.sin(a) * r);
			int z = c.getZ() + (int) Math.round(Math.cos(a) * r);
			if (level.getBlockState(new BlockPos(x, y0 - 1, z)).is(Blocks.GRASS_BLOCK)) {
				set(x, y0, z, Blocks.POPPY.defaultBlockState(), true);
			}
		}
	}

	// ── 도구 ──────────────────────────────────────────────

	/** shape = 이웃에 맞춰 모양 갱신 (울타리 잇기 · 계단 모서리) */
	private void set(int x, int y, int z, BlockState s, boolean shape) {
		BlockPos p = new BlockPos(x, y, z);
		if (shape) {
			s = Block.updateFromNeighbourShapes(s, level, p);
			level.setBlock(p, s, Block.UPDATE_ALL);
		} else {
			level.setBlock(p, s, Block.UPDATE_CLIENTS);
		}
		placed++;
	}

	/** 0~1 값 잡음 (격자 해시 + 부드러운 보간) — 같은 seed 면 같은 무늬 */
	private double noise(int x, int z, double scale, int salt) {
		double fx = x / scale;
		double fz = z / scale;
		int ix = (int) Math.floor(fx);
		int iz = (int) Math.floor(fz);
		double tx = fx - ix;
		double tz = fz - iz;
		tx = tx * tx * (3 - 2 * tx);
		tz = tz * tz * (3 - 2 * tz);
		double a = hash(ix, iz, salt);
		double b = hash(ix + 1, iz, salt);
		double cc = hash(ix, iz + 1, salt);
		double d = hash(ix + 1, iz + 1, salt);
		return (a + (b - a) * tx) + ((cc + (d - cc) * tx) - (a + (b - a) * tx)) * tz;
	}

	private double hash(int x, int z, int salt) {
		long h = seed * 0x9E3779B97F4A7C15L + x * 0xC2B2AE3D27D4EB4FL + z * 0x165667B19E3779F9L + salt * 0x27D4EB2F165667C5L;
		h ^= h >>> 31;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 29;
		return (h >>> 11) / (double) (1L << 53);
	}
}
