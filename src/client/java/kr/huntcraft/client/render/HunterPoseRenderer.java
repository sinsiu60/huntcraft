package kr.huntcraft.client.render;

import java.util.HashMap;
import java.util.Map;

import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Skeleton;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.combat.HunterBody;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 헌터 스켈레톤 자세 → 바닐라 플레이어 모델 파트. 판정(서버)과 같은 스켈레톤 계산 결과를 그대로 옮겨서
 * 화면의 팔 · 칼과 판정 도형이 정확히 겹칩니다.
 *
 * 좌표 변환 (바닐라 렌더러는 모델을 Y축 180° - 몸 방향으로 돌리고 scale(-1,-1,1), 24픽셀 내린 공간에서 그림):
 *   바닐라 모델 좌표 = (-16x, 24 - 16y, 16z)   (x,y,z = 스켈레톤 모델 공간, 블록)
 *   파트 위치 = 변환된 본 pivot,  파트 회전 = F·R·F (F = Z축 180°) → 쿼터니언 (-x, -y, z, w) 을 ZYX 오일러로
 * 애니메이션이 건드리는 본(또는 그 조상)과 칼자루를 쥐는 팔만 덮어씀 — 나머지(예: 대기 중 다리)는 바닐라 걷기 동작 그대로.
 */
public final class HunterPoseRenderer {
	private static final String[] BONES = {"head", "body", "right_arm", "left_arm", "right_leg", "left_leg"};
	/** 엔티티 id → 이번 프레임 보간 비율 (렌더 상태에는 없어서 extract 때 저장) */
	private static final Map<Integer, Float> PARTIAL = new HashMap<>();
	/** 엔티티 id → 몸 기준 옆 이동 비율 (-1 왼쪽 ~ 1 오른쪽) · 앞뒤 비율 — 게걸음 다리 */
	private static final Map<Integer, float[]> STRAFE = new HashMap<>();

	private HunterPoseRenderer() {}

	/** AvatarRendererMixin: 몸 방향을 판정과 같은 값(보간된 yRot)으로 — 등에 멘 칼도 같은 루트를 쓰므로 헌터면 항상 */
	public static void extract(Avatar entity, AvatarRenderState state, float partial) {
		HunterBody body = ClientHunters.body(entity.getId());
		if (body == null || body.colliderDef() == null) {
			PARTIAL.remove(entity.getId());
			return;
		}
		PARTIAL.put(entity.getId(), partial);
		// 몸 방향 기준 이동 방향 (옆 성분이 크면 게걸음 다리)
		Vec3 v = entity.getDeltaMovement();
		double hv = Math.hypot(v.x, v.z);
		if (hv > 1.0E-3) {
			double rad = Math.toRadians(entity.getYRot());
			double fwd = (-Math.sin(rad) * v.x + Math.cos(rad) * v.z) / hv;
			double right = (-Math.cos(rad) * v.x - Math.sin(rad) * v.z) / hv;
			STRAFE.put(entity.getId(), new float[] {(float) right, (float) fwd});
		} else {
			STRAFE.remove(entity.getId());
		}
		state.bodyRot = Mth.rotLerp(partial, entity.yRotO, entity.getYRot());
		if (body.animation() != null) {
			state.yRot = 0.0F;
			state.xRot = 0.0F;
		}
	}

	/** PlayerModelMixin: 바닐라 setupAnim 뒤에 자세를 덮어씀 (판정과 같은 HunterBody.samplePose) */
	public static void pose(PlayerModel model, AvatarRenderState state) {
		Float partial = PARTIAL.get(state.id);
		HunterBody body = partial == null ? null : ClientHunters.body(state.id);
		ColliderDef def = body == null ? null : body.colliderDef();
		if (def == null) {
			return;
		}
		Skeleton sk = def.skeleton;
		Pose pose = new Pose(sk.size());
		body.samplePose(body.animTime(partial), pose);
		Transform[] bones = new Transform[sk.size()];
		sk.solveModel(pose, bones);
		ModelPart[] parts = {model.head, model.body, model.rightArm, model.leftArm, model.rightLeg, model.leftLeg};
		for (int i = 0; i < BONES.length; i++) {
			int bone = sk.find(BONES[i]);
			if (bone >= 0 && body.drives(BONES[i])) {
				apply(parts[i], bones[bone], sk.pivot(bone));
			}
		}
		strafeLegs(model, state, body);
	}

	/**
	 * 게걸음 (락온 중 옆 이동) — 실제 옆걸음처럼: 한 걸음 = 벌리기 + 모으기.
	 *   벌리기 (위상 0~π): 가는 쪽 다리가 살짝 들리며 옆으로 나가 디딤 (발끝은 가는 쪽으로), 몸무게는 뒤 다리에
	 *   모으기 (π~2π): 뒤 다리가 들려서 따라와 붙음, 몸무게는 앞 다리로 옮겨 감
	 * 위상은 바닐라 걷기와 같은 이동 거리 기준이라 속도에 맞춰 발이 미끄러지지 않음. 앞뒤 섞인 대각선은 바닐라 앞뒤 흔들기와 섞음.
	 * 값은 ClientConfig.strafe. 다리를 애니메이션이 쥐고 있으면 (공격 · 구르기) 그대로.
	 */
	private static void strafeLegs(PlayerModel model, AvatarRenderState state, HunterBody body) {
		float[] s = STRAFE.get(state.id);
		if (s == null || body.drives("right_leg") || body.drives("left_leg")) {
			return;
		}
		kr.huntcraft.client.config.ClientConfig.Strafe cfg = kr.huntcraft.client.config.ClientConfig.get().strafe;
		float side = Mth.clamp(s[0], -1.0F, 1.0F);
		float amount = Math.abs(side);
		if (amount < cfg.minSideShare) {
			return;
		}
		float dir = Math.signum(side);
		float amp = Math.min(1.0F, state.walkAnimationSpeed * cfg.speedToAmplitude) * amount;
		float phase = state.walkAnimationPos * cfg.stride;
		float spread = 0.5F - 0.5F * Mth.cos(phase);           // 0 모음 → 1 벌림 → 0
		float liftLead = Math.max(0.0F, Mth.sin(phase));       // 벌리는 동안 앞 다리 들림
		float liftTrail = Math.max(0.0F, -Mth.sin(phase));     // 모으는 동안 뒤 다리 들림
		ModelPart lead = dir > 0 ? model.rightLeg : model.leftLeg;
		ModelPart trail = dir > 0 ? model.leftLeg : model.rightLeg;
		// 바깥쪽 = 오른다리 +z, 왼다리 −z
		float leadOut = dir > 0 ? 1.0F : -1.0F;
		// 앞뒤 흔들기는 옆 비율만큼 줄이고, 들리는 다리는 무릎을 드는 만큼 앞으로
		lead.xRot = lead.xRot * (1.0F - amount) - (float) cfg.lift * liftLead * amp;
		trail.xRot = trail.xRot * (1.0F - amount) - (float) cfg.lift * liftTrail * amp;
		lead.zRot = leadOut * (float) cfg.stepOut * spread * amp;
		trail.zRot = -leadOut * (float) (cfg.stepOut * cfg.trailShare) * spread * amp;
		// 가는 쪽으로 발끝을 조금 돌림 (디딜 때만)
		lead.yRot = leadOut * (float) cfg.toeOut * spread * amp;
		trail.yRot = 0.0F;
		// 몸무게 옮기기: 앞 다리를 드는 동안 뒤 다리 쪽으로 · 모으는 동안 가는 쪽으로 기움
		model.body.zRot += dir * (float) cfg.sway * (liftTrail - liftLead) * amp;
		model.head.zRot -= dir * (float) cfg.sway * 0.5F * (liftTrail - liftLead) * amp;
	}

	private static void apply(ModelPart part, Transform bone, Vec3 pivot) {
		Vec3 p = bone.apply(pivot);
		part.x = (float) (-16.0 * p.x);
		part.y = (float) (24.0 - 16.0 * p.y);
		part.z = (float) (16.0 * p.z);
		Quaterniond q = bone.rot();
		Vector3f e = new Quaternionf((float) -q.x, (float) -q.y, (float) q.z, (float) q.w).getEulerAnglesZYX(new Vector3f());
		part.xRot = e.x;
		part.yRot = e.y;
		part.zRot = e.z;
	}
}
