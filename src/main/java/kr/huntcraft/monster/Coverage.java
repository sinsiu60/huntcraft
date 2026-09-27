package kr.huntcraft.monster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import kr.huntcraft.anim.Animation;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.collision.Geometry;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Shape;
import kr.huntcraft.collision.Transform;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 사각지대 커버리지 (오프라인 시뮬레이션 — 디버그 도구, 게임 상태를 건드리지 않음).
 *
 * 보스를 원점에 +Z (yaw 0) 로 세우고, 주위 격자 (spacing 간격 · 반지름 radius) 의 각 점에 헌터가 가만히 서 있다고 할 때
 * 공격 행동마다 실제 게임과 같은 코드 (애니메이션 샘플 → 본 계층 → 판정 도형 → Geometry) 로 활성 구간 hitbox 를
 * 프레임당 substeps 번 훑어서 헌터 hurtbox 에 한 번이라도 닿는지 봄. 땅 충격파는 앞머리가 그 점까지 닿는지 (서 있으면 맞음).
 *   tracking = true: 행동의 track 구간 동안 trackRate 로 헌터 쪽으로 돎 (실제 싸움) / false: 처음 방향 그대로
 *   selectable = true: 그 자리에서 실제로 고를 수 있는 공격만 셈 (range · angle — 연계 공격은 이어지는 원래 공격을 고를 수 있으면,
 *                      분노 전용은 rage = true 일 때만)
 *   angles = false: 고르기 조건에서 각도는 빼고 거리만 (보스가 제자리 회전으로 돌아선 뒤 고르는 경우)
 *   inner = false: 근접 보조 판정 (hits.inner) 을 빼고 계산 (전후 비교)
 *   waves = true: 땅 충격파도 셈 (점프 · 구르기로 넘는 공격이라 기본은 hitbox 스윕만)
 *   root 이동 (돌진 · 도약 · rootToTarget) 은 반영, 벽 · 지형 충돌 · 헌터의 구르기 · 점프는 반영하지 않음.
 */
public final class Coverage {
	private Coverage() {}

	public record Options(double spacing, double radius, int substeps, boolean tracking, boolean selectable, boolean waves, boolean rage,
						  boolean angles, boolean inner, Set<String> disabled) {}

	/** 격자 한 점 — 보스 기준 (x = 왼쪽 +, z = 앞 +), 구역 · 방향, 닿는 패턴, 그 자리에서 고를 수 있는 패턴 */
	public record Cell(double x, double z, String zone, String dir, List<String> hits, List<String> selectable) {}

	public record Result(String monster, Options options, List<String> patterns, List<Cell> cells) {
		/** 색에 쓰는 수: 켠 패턴 중 닿는 것 (selectable 이면 고를 수 있는 것만) */
		public int count(Cell c) {
			int n = 0;
			for (String p : c.hits()) {
				if (!options.disabled().contains(p) && (!options.selectable() || c.selectable().contains(p))) {
					n++;
				}
			}
			return n;
		}
	}

	public static final String[] ZONES = {"inner", "close", "mid", "far"};
	public static final String[] DIRS = {"front", "left", "right", "back", "under"};
	private static final Map<String, String> KO = Map.of("inner", "밀착", "close", "근접", "mid", "중거리", "far", "원거리", "front", "정면", "left",
			"좌측", "right", "우측", "back", "후방", "under", "하부");

	public static String ko(String key) {
		return KO.getOrDefault(key, key);
	}

	/** 공격 패턴 = hitbox 구간이나 땅 충격파가 있는 행동 (역할 행동 — 처치 · 2페이즈 전환 등 — 은 뺌) */
	public static List<String> patterns(MonsterDef d) {
		Set<String> roles = new java.util.HashSet<>();
		for (String r : new String[] {d.idleAction, d.turnLeft, d.turnRight, d.chase, d.pause, d.discover, d.flinch, d.knockdown, d.death, d.rageAction,
				d.parried, d.stanceBreak, d.riposted, d.dormant, d.awaken}) {
			if (r != null) {
				roles.add(r);
			}
		}
		List<String> out = new ArrayList<>();
		for (MonsterDef.Action a : d.actions.values()) {
			boolean wave = a.events().stream().anyMatch(e -> e.shockwave() != null);
			if (!roles.contains(a.id()) && (!a.hits().isEmpty() || wave)) {
				out.add(a.id());
			}
		}
		out.sort(null);
		return out;
	}

	public static Result run(MonsterDef d, Options o) {
		ColliderDef col = Defs.collider(d.collider);
		ColliderDef hunter = Defs.collider("hunter");
		if (col == null || hunter == null) {
			throw new IllegalStateException("판정 정의가 없음: " + d.collider + " / hunter");
		}
		List<String> patterns = patterns(d);
		// 몸 윤곽 (쉬는 자세 · 원점) — 구역 거리 기준
		Transform[] rest = new Transform[col.skeleton.size()];
		col.skeleton.solveModel(new Pose(col.skeleton.size()), rest);
		Transform root0 = rootAt(Vec3.ZERO, 0.0, col.scale);
		List<Shape.Obb> body = new ArrayList<>();
		for (ColliderDef.Box b : col.hurtboxes) {
			if (d.zones.bodyParts().contains(b.part()) && b.shape().toWorld(root0.then(rest[b.bone()])) instanceof Shape.Obb ob) {
				body.add(ob);
			}
		}
		Map<String, Sampler> samplers = new HashMap<>();
		for (String p : patterns) {
			samplers.put(p, new Sampler(d.actions.get(p), col, o.substeps(), o.inner()));
		}
		Map<String, List<String>> parents = new HashMap<>();
		for (MonsterDef.Action a : d.actions.values()) {
			for (ActionFx.Follow f : a.flow().followups()) {
				parents.computeIfAbsent(f.action(), k -> new ArrayList<>()).add(a.id());
			}
			for (ActionFx.Combo c : a.flow().combo()) {
				parents.computeIfAbsent(c.action(), k -> new ArrayList<>()).add(a.id());
			}
		}
		List<Cell> cells = new ArrayList<>();
		int n = (int) Math.floor(o.radius() / o.spacing());
		for (int i = -n; i <= n; i++) {
			for (int j = -n; j <= n; j++) {
				double x = i * o.spacing();
				double z = j * o.spacing();
				if (x * x + z * z > o.radius() * o.radius() + 1.0E-9) {
					continue;
				}
				Vec3 at = new Vec3(x, 0.0, z);
				double dist = Math.hypot(x, z);
				double angle = Mth.wrapDegrees(Math.toDegrees(Math.atan2(-x, z)));
				double edge = edgeDistance(body, x, z);
				String zone = edge <= d.zones.inner() ? "inner" : edge <= d.zones.close() ? "close" : edge <= d.zones.mid() ? "mid" : "far";
				String dir = edge <= 0.0 ? "under" : Math.abs(angle) <= d.zones.front() ? "front" : Math.abs(angle) >= d.zones.back() ? "back"
						: angle > 0.0 ? "right" : "left";
				List<Shape> hurt = hunterShapes(hunter, at);
				List<String> hits = new ArrayList<>();
				List<String> sel = new ArrayList<>();
				for (String p : patterns) {
					MonsterDef.Action a = d.actions.get(p);
					// 밀착 · 근접이면 선회 배율 (zones.closeTurnRate — 게임과 같음)
					double turn = edge <= d.zones.close() ? d.zones.closeTurnRate() : 1.0;
					if (samplers.get(p).hits(at, hurt, o.tracking(), o.waves(), dist, turn)) {
						hits.add(p);
					}
					if ((o.rage() || !a.rageOnly()) && selectable(d, a, dist, o.angles() ? angle : Double.NaN, parents, 0)) {
						sel.add(p);
					}
				}
				cells.add(new Cell(x, z, zone, dir, hits, sel));
			}
		}
		return new Result(d.id, o, patterns, cells);
	}

	/** 콘솔 표: 구역 × 방향 — 점 수 · 빨강 (0개) 비율 · 평균 패턴 수, 그리고 패턴별 구역 커버율 */
	public static List<String> table(Result r) {
		List<String> out = new ArrayList<>();
		out.add(String.format(Locale.ROOT, "커버리지 %s — 선회 %s · 선택 조건 %s · 충격파 %s · 근접 보조 판정 %s · 끈 패턴 %s", r.monster(),
				r.options().tracking() ? "반영" : "미반영", r.options().selectable() ? (r.options().angles() ? "거리 · 각도" : "거리만") + (r.options().rage() ? " (2페이즈)" : " (1페이즈)") : "미반영",
				r.options().waves() ? "포함" : "제외", r.options().inner() ? "켬" : "끔", r.options().disabled().isEmpty() ? "없음" : r.options().disabled()));
		out.add("구역/방향     점   빨강(0)  주황(1)  노랑(2~3)  초록(4+)  평균");
		for (String z : ZONES) {
			for (String dir : DIRS) {
				row(out, r, ko(z) + "/" + ko(dir), c -> c.zone().equals(z) && c.dir().equals(dir));
			}
			row(out, r, ko(z) + " 전체", c -> c.zone().equals(z));
		}
		out.add("패턴별 닿는 비율 (밀착 · 근접 · 중거리)" + (r.options().selectable() ? " — 고를 수 있는 자리만" : ""));
		for (String p : r.patterns()) {
			StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-14s", p));
			for (String z : new String[] {"inner", "close", "mid"}) {
				int all = 0;
				int hit = 0;
				for (Cell c : r.cells()) {
					if (c.zone().equals(z)) {
						all++;
						if (c.hits().contains(p) && (!r.options().selectable() || c.selectable().contains(p))) {
							hit++;
						}
					}
				}
				sb.append(String.format(Locale.ROOT, " %s %3.0f%%", ko(z), all == 0 ? 0.0 : 100.0 * hit / all));
			}
			out.add(sb.toString());
		}
		return out;
	}

	/** 조건에 맞는 점 중 빨강 (0개) 비율 (%) */
	public static double redPercent(Result r, java.util.function.Predicate<Cell> f) {
		int all = 0;
		int red = 0;
		for (Cell c : r.cells()) {
			if (f.test(c)) {
				all++;
				if (r.count(c) == 0) {
					red++;
				}
			}
		}
		return all == 0 ? 0.0 : 100.0 * red / all;
	}

	private static void row(List<String> out, Result r, String name, java.util.function.Predicate<Cell> f) {
		int all = 0;
		int[] bands = new int[4];
		double sum = 0;
		for (Cell c : r.cells()) {
			if (!f.test(c)) {
				continue;
			}
			all++;
			int n = r.count(c);
			sum += n;
			bands[n == 0 ? 0 : n == 1 ? 1 : n <= 3 ? 2 : 3]++;
		}
		if (all == 0) {
			return;
		}
		out.add(String.format(Locale.ROOT, "%-12s %4d  %5.1f%%  %5.1f%%  %6.1f%%  %6.1f%%  %4.1f", name, all, 100.0 * bands[0] / all, 100.0 * bands[1] / all,
				100.0 * bands[2] / all, 100.0 * bands[3] / all, sum / all));
	}

	/** 그 자리에서 이 공격을 고를 수 있는지 (range · angle · weight). 연계 전용 (weight 0) 은 이어지는 원래 공격을 고를 수 있으면 */
	private static boolean selectable(MonsterDef d, MonsterDef.Action a, double dist, double angle, Map<String, List<String>> parents, int depth) {
		if (a.weight() > 0.0) {
			return (a.range() == null || (dist >= a.range()[0] && dist <= a.range()[1])) && (a.angle() == null || Double.isNaN(angle) || inAngle(angle, a.angle()));
		}
		if (depth > 4) {
			return false;
		}
		for (String p : parents.getOrDefault(a.id(), List.of())) {
			if (!p.equals(a.id()) && selectable(d, d.actions.get(p), dist, angle, parents, depth + 1)) {
				return true;
			}
		}
		return false;
	}

	private static boolean inAngle(double angle, double[] range) {
		if (range[0] <= range[1]) {
			return angle >= range[0] && angle <= range[1];
		}
		return angle >= range[0] || angle <= range[1];
	}

	/** 몸 윤곽 (OBB 바닥 투영) 까지 수평 거리 — 안이면 0 */
	private static double edgeDistance(List<Shape.Obb> body, double x, double z) {
		double best = Double.MAX_VALUE;
		for (Shape.Obb b : body) {
			best = Math.min(best, Geometry.distanceToObb(new Vec3(x, b.center().y, z), b));
		}
		return best;
	}

	/** 헌터가 point 에 서서 보스 (원점) 를 봄 — 쉬는 자세 hurtbox */
	private static List<Shape> hunterShapes(ColliderDef hunter, Vec3 point) {
		Transform[] bones = new Transform[hunter.skeleton.size()];
		float yaw = (float) Math.toDegrees(Math.atan2(point.x, -point.z));
		hunter.skeleton.solve(new Pose(hunter.skeleton.size()), new Transform(point, Transform.eulerZYX(0.0, 180.0 - yaw, 0.0), hunter.scale), bones);
		List<Shape> out = new ArrayList<>();
		for (ColliderDef.Box b : hunter.hurtboxes) {
			out.add(b.shape().toWorld(bones[b.bone()]));
		}
		return out;
	}

	private static Transform rootAt(Vec3 pos, double facing, double scale) {
		return new Transform(pos, Transform.eulerZYX(0.0, 180.0 - facing, 0.0), scale);
	}

	/** 행동 하나: 시각별 모델 공간 본 변환을 한 번만 계산해 두고, 점마다 루트 (위치 · 방향) 만 바꿔 끼움 */
	private static final class Sampler {
		private final MonsterDef.Action a;
		private final ColliderDef col;
		private final int sub;
		private final Animation anim;
		private final Map<Integer, Transform[]> model = new HashMap<>();
		private final Map<String, ColliderDef.Box> boxes = new LinkedHashMap<>();

		private final boolean inner;

		Sampler(MonsterDef.Action a, ColliderDef col, int sub, boolean inner) {
			this.a = a;
			this.inner = inner;
			this.col = col;
			this.sub = Math.max(1, sub);
			this.anim = Defs.animation(a.animation());
			for (ColliderDef.Box b : col.hitboxes) {
				boxes.put(b.id(), b);
			}
		}

		private Transform[] model(int k) {
			return model.computeIfAbsent(k, key -> {
				Pose p = new Pose(col.skeleton.size());
				if (anim != null) {
					anim.sample(key / (double) sub / 60.0, col.skeleton, p);
				}
				Transform[] out = new Transform[col.skeleton.size()];
				col.skeleton.solveModel(p, out);
				return out;
			});
		}

		boolean hits(Vec3 point, List<Shape> hurt, boolean tracking, boolean waves, double dist, double turnRate) {
			double scale = col.scale;
			double rootScale = 1.0;
			double[] fit = a.flow().rootToTarget();
			if (fit != null && a.root().length > 0) {
				double total = a.rootOffset(a.length())[0] * scale;
				if (total > 1.0E-3) {
					rootScale = Mth.clamp((dist - fit[2]) / total, fit[0], fit[1]);
				}
			}
			double facing = 0.0;
			double x = 0.0;
			double y = 0.0;
			double z = 0.0;
			double prevT = 0.0;
			int steps = a.length() * sub;
			for (int k = 1; k <= steps; k++) {
				double t = k / (double) sub;
				if (tracking && a.tracking(t)) {
					double want = Math.toDegrees(Math.atan2(-(point.x - x), point.z - z)) - a.aimRight();
					double maxTurn = a.trackRate() * turnRate * (t - prevT) / 60.0;
					facing += Mth.clamp(Mth.wrapDegrees(want - facing), -maxTurn, maxTurn);
				}
				facing += a.spinAt(t) - a.spinAt(prevT);
				double[] r0 = a.rootOffset(prevT);
				double[] r1 = a.rootOffset(t);
				double forward = (r1[0] - r0[0]) * rootScale;
				double right = r1[1] - r0[1];
				double rad = Math.toRadians(facing);
				x += (-Math.sin(rad) * forward - Math.cos(rad) * right) * scale;
				z += (Math.cos(rad) * forward - Math.sin(rad) * right) * scale;
				y = a.hasUp() ? Math.max(0.0, r1[2]) * scale : 0.0;
				int idx = a.hitIndex(t);
				Transform root = null;
				if (idx >= 0) {
					root = rootAt(new Vec3(x, y, z), facing, scale);
					Transform[] m = model(k);
					MonsterDef.Hit hit = a.hits().get(idx);
					Set<String> ids = inner ? hit.activeBoxes(t) : (t >= hit.from() && t <= hit.to() ? hit.hitboxes() : Set.of());
					for (String id : ids) {
						ColliderDef.Box b = boxes.get(id);
						if (b == null) {
							continue;
						}
						Shape s = b.shape().toWorld(root.then(m[b.bone()]));
						for (Shape h : hurt) {
							if (Geometry.test(s, h) != null) {
								return true;
							}
						}
					}
				}
				for (ActionFx.Event e : a.events()) {
					if (!waves || e.shockwave() == null || !e.firesBetween(prevT, t)) {
						continue;
					}
					if (root == null) {
						root = rootAt(new Vec3(x, y, z), facing, scale);
					}
					Vec3 o = origin(e, root, model(k));
					double dx = point.x - o.x;
					double dz = point.z - o.z;
					double dd = Math.hypot(dx, dz);
					double dir = Math.toDegrees(Math.atan2(-dx, dz));
					if (dd - 0.3 <= e.shockwave().range() && Math.abs(Mth.wrapDegrees(dir - facing)) <= e.shockwave().angle() / 2.0) {
						return true;
					}
				}
				prevT = t;
			}
			return false;
		}

		private Vec3 origin(ActionFx.Event e, Transform root, Transform[] m) {
			double[] px = e.at() == null ? new double[] {0, 0, 0} : e.at();
			Vec3 local = kr.huntcraft.collision.Skeleton.pixels(px[0], px[1], px[2]);
			int b = e.bone() == null ? -1 : col.skeleton.find(e.bone());
			if (b < 0) {
				return root.apply(local);
			}
			return root.then(m[b]).apply(col.skeleton.rest(b).inverse().apply(local));
		}
	}
}
