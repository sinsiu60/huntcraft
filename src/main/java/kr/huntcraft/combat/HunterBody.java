package kr.huntcraft.combat;

import kr.huntcraft.anim.Animation;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.ColliderOwner;
import kr.huntcraft.collision.Defs;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Skeleton;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.hunter.HunterState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 헌터(플레이어)의 판정 몸 — 태도 칼날 hitbox (katana 본) · 피격 hurtbox. 서버 · 클라이언트 공용.
 * 플레이어 엔티티를 고치지 않고(Mixin 없이) 옆에 붙이는 판정 주인이라 HitSystem 에 직접 등록합니다.
 *
 *   애니메이션 시간: 모션 중이면 모션 프레임 (히트스톱 동안 멈춤), 대기 중이면 월드 시간
 *   자세 = 바탕(발도 대기 / 납도 대기) 위에 → 모션 애니메이션 → 손 잡기
 *   손 잡기: 칼(katana)은 몸통의 자식이라 애니메이션이 칼 궤적을 직접 그리고, 키프레임이 없는 팔은
 *            칼자루의 grip_right / grip_left 본을 향해 자동으로 뻗습니다 (모션의 grip: 양손 · 오른손 · 없음)
 *   공격 판정: 모션의 활성 구간 · 구간마다 공격 인스턴스      피격 판정: 회피 무적 동안 꺼짐
 */
public final class HunterBody implements ColliderOwner {
	public static final String COLLIDER = "hunter";

	/** 모션 미리보기 (클라이언트 시험 · 모션 갤러리 전용) — motion = 모션 id 또는 #idle_drawn / #idle_sheathed */
	public record Preview(String motion, double frame) {}

	private final Player player;
	private final HunterCombat combat;
	private final HunterState state;
	private final ColliderInstance colliders = new ColliderInstance(this);
	private Preview preview;

	public HunterBody(Player player, HunterCombat combat, HunterState state) {
		this.player = player;
		this.combat = combat;
		this.state = state;
	}

	public Player player() {
		return player;
	}

	public HunterCombat combat() {
		return combat;
	}

	public HunterState state() {
		return state;
	}

	public void setPreview(Preview preview) {
		this.preview = preview;
	}

	public Preview preview() {
		return preview;
	}

	@Override
	public Entity self() {
		return player;
	}

	@Override
	public ColliderDef colliderDef() {
		return Defs.collider(COLLIDER);
	}

	@Override
	public ColliderInstance colliders() {
		return colliders;
	}

	/** 지금 모션 (미리보기 포함, 대기면 null) */
	public MoveSet.Motion motion() {
		if (preview != null) {
			MoveSet set = HunterCombat.moveSet();
			return set == null ? null : set.motion(preview.motion());
		}
		return combat.current();
	}

	/** 칼을 손에 들고 있는지 */
	public boolean drawn() {
		if (preview != null) {
			return !preview.motion().equals("#idle_sheathed");
		}
		return state.drawn;
	}

	@Override
	public double animTime(float partial) {
		if (preview != null) {
			return preview.frame();
		}
		if (combat.current() != null) {
			return combat.frame() + (combat.hitstop() > 0 ? 0.0F : partial);
		}
		return player.level().getGameTime() + partial;
	}

	@Override
	public double animTimePrev() {
		if (preview == null && combat.current() != null) {
			return combat.prevFrame();
		}
		return animTime(0.0F) - 1.0;
	}

	/** 지금 재생할 모션 애니메이션 (대기면 null) */
	public Animation animation() {
		MoveSet.Motion m = motion();
		return m == null || m.animation() == null ? null : Defs.animation(m.animation());
	}

	/**
	 * 바탕 자세 — 발도면 발도 대기(칼을 뒤로 늘어뜨린 태도 자세), 납도면 납도 대기(칼을 등에).
	 * 모션은 이 위에 덮어써서, 칼 키프레임이 없는 모션(회피 등)은 지금 상태에 맞는 칼 자리를 그대로 씀.
	 */
	public Animation base() {
		MoveSet set = HunterCombat.moveSet();
		if (set == null) {
			return null;
		}
		return Defs.animation(drawn() ? set.idleDrawnAnimation : set.idleSheathedAnimation);
	}

	private MoveSet.Grip grip() {
		MoveSet.Motion m = motion();
		if (m != null && m.grip() == MoveSet.Grip.ITEM) {
			return MoveSet.Grip.ITEM; // 아이템은 납도 상태에서 씀
		}
		if (!drawn()) {
			return MoveSet.Grip.NONE;
		}
		if (m != null) {
			return m.grip();
		}
		MoveSet set = HunterCombat.moveSet();
		return set == null ? MoveSet.Grip.NONE : set.idleDrawnGrip;
	}

	@Override
	public void samplePose(double time, Pose out) {
		ColliderDef def = colliderDef();
		if (def == null) {
			return;
		}
		Skeleton sk = def.skeleton;
		// 발도: 바탕(발도 대기) 위에 모션.  납도: 모션 위에 바탕(칼을 등에) — 납도 회피처럼 모션이 칼을 쥐고 있어도 칼은 등에
		Animation base = base();
		Animation anim = animation();
		boolean drawn = drawn();
		if (base != null && drawn) {
			base.sample(time / 60.0, sk, out);
		}
		if (anim != null) {
			anim.sample(time / 60.0, sk, out);
		}
		if (base != null && !drawn) {
			base.sample(time / 60.0, sk, out);
		}
		MoveSet.Grip grip = grip();
		if (grip == MoveSet.Grip.NONE) {
			return;
		}
		Transform[] model = new Transform[sk.size()];
		sk.solveModel(out, model);
		aim(sk, model, out, anim, "right_arm", grip == MoveSet.Grip.ITEM ? "held" : "grip_right");
		if (grip == MoveSet.Grip.BOTH) {
			aim(sk, model, out, anim, "left_arm", "grip_left");
		}
	}

	/**
	 * 팔(한 마디)을 칼자루 쥐는 자리로 향하게 — 팔의 기본 방향(아래)을 어깨→쥐는 자리 방향으로 돌리는 X·Z 회전.
	 * 팔 길이는 그대로라 손이 딱 맞지 않을 수 있음 (바닐라 팔은 팔꿈치가 없음).
	 */
	private static void aim(Skeleton sk, Transform[] model, Pose out, Animation anim, String armName, String gripName) {
		int arm = sk.find(armName);
		int grip = sk.find(gripName);
		if (arm < 0 || grip < 0 || (anim != null && anim.animates(armName))) {
			return;
		}
		int parent = sk.parent(arm);
		Vec3 target = model[grip].apply(sk.pivot(grip));
		Vec3 shoulder = model[arm].apply(sk.pivot(arm));
		Transform parentT = parent < 0 ? Transform.IDENTITY : model[parent];
		Vec3 d = parentT.inverse().rotate(target.subtract(shoulder));
		double len = d.length();
		if (len < 1.0E-6) {
			return;
		}
		d = d.scale(1.0 / len);
		// (0,-1,0) 을 X 로 a, Z 로 c 돌리면 (cos a · sin c, -cos a · cos c, -sin a)
		double a = Math.asin(Math.max(-1.0, Math.min(1.0, -d.z)));
		double c = Math.atan2(d.x, -d.y);
		// Skeleton 은 X 를 Blockbench 반대 부호로 씀
		out.rotate(arm, -Math.toDegrees(a), 0.0, Math.toDegrees(c));
	}

	/** 렌더: 이 본을 스켈레톤 자세로 덮어써야 하는지 (애니메이션이 건드리거나 손으로 칼을 쥐는 팔) */
	public boolean drives(String bone) {
		ColliderDef def = colliderDef();
		Animation anim = animation();
		Animation base = base();
		if (def == null) {
			return false;
		}
		Skeleton sk = def.skeleton;
		int b = sk.find(bone);
		for (int i = b; i >= 0; i = sk.parent(i)) {
			String name = sk.boneName(i);
			if ((anim != null && anim.animates(name)) || (base != null && base.animates(name))) {
				return true;
			}
		}
		MoveSet.Grip grip = grip();
		return (bone.equals("right_arm") && grip != MoveSet.Grip.NONE) || (bone.equals("left_arm") && grip == MoveSet.Grip.BOTH);
	}

	@Override
	public boolean hitboxActive(String hitboxId, double time) {
		return attackInstance(time) != 0L;
	}

	@Override
	public long attackInstance(double time) {
		if (preview != null) {
			MoveSet.Motion m = motion();
			return m != null && m.hitIndex(time) >= 0 ? 1L : 0L;
		}
		return combat.instance(time);
	}

	@Override
	public boolean hurtboxesActive(double time) {
		return !combat.invulnerable(time);
	}

	@Override
	public boolean parryActive(double time) {
		return combat.parry(time);
	}

	@Override
	public boolean hitOncePerAttack() {
		return true;
	}

	@Override
	public boolean canHit(ColliderOwner target) {
		return !(target instanceof HunterBody);
	}
}
