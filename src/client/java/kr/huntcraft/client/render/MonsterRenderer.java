package kr.huntcraft.client.render;

import java.util.HashMap;
import java.util.Map;

import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.model.DefaultedEntityGeoModel;
import com.geckolib.model.GeoModel;
import com.geckolib.renderer.GeoEntityRenderer;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.Pose;
import kr.huntcraft.collision.Skeleton;
import kr.huntcraft.monster.MonsterDef;
import kr.huntcraft.monster.MonsterEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * 보스 렌더 — 메시 · 텍스처는 GeckoLib, 본 자세는 판정(서버)과 같은 계산 (MonsterEntity.samplePose).
 * GeckoLib 애니메이션 컨트롤러는 쓰지 않고, 렌더 직전에 본 스냅샷을 자세 값으로 덮어써서 화면 = 판정 도형.
 * 어느 모델 · 텍스처인지는 개체의 정의(defs/monsters 의 model · texture) — 보스를 늘려도 이 코드는 그대로.
 *
 * 값 변환 (GeckoLib 이 Bedrock 키프레임을 읽을 때와 같은 규칙 — GeckoLib 5 ActorBoneAnimationKeyframe · BoneSnapshot 바이트코드로 확인):
 *   회전: 읽을 때 (-rx, -ry, rz) 라디안으로 바꿈 → 같은 값을 넣음
 *   위치: 읽을 때는 그대로 (x, y, z) 픽셀, 그릴 때 GeckoLib 이 x 를 뒤집음 → Blockbench 값 그대로 넣음
 */
public class MonsterRenderer extends GeoEntityRenderer<MonsterEntity, LivingEntityRenderState> {
	/** 본 이름 → [rx, ry, rz, x, y, z] (Blockbench 표기) */
	@SuppressWarnings("unchecked")
	private static final DataTicket<Map<String, double[]>> POSE =
			DataTicket.create("huntcraft_pose", (Class<Map<String, double[]>>) (Class<?>) Map.class);
	private static final DataTicket<String> MODEL = DataTicket.create("huntcraft_model", String.class);
	private static final DataTicket<String> TEXTURE = DataTicket.create("huntcraft_texture", String.class);
	/** 소멸 진행 (0 = 그대로 · 1 = 사라짐) — 투명하게 그림 */
	private static final DataTicket<Float> DISSOLVE = DataTicket.create("huntcraft_dissolve", Float.class);

	public MonsterRenderer(EntityRendererProvider.Context ctx) {
		super(ctx, new DefModel());
	}

	/** 정의별 모델 (모델 이름마다 GeckoLib 기본 경로 규칙을 그대로 씀) */
	private static final class DefModel extends GeoModel<MonsterEntity> {
		private final Map<String, DefaultedEntityGeoModel<MonsterEntity>> byModel = new HashMap<>();

		private DefaultedEntityGeoModel<MonsterEntity> of(String model) {
			return byModel.computeIfAbsent(model == null || model.isBlank() ? "iron_golem" : model,
					m -> new DefaultedEntityGeoModel<>(HuntCraft.id(m)));
		}

		@Override
		public Identifier getModelResource(GeoRenderState state) {
			return of(state.getGeckolibData(MODEL)).getModelResource(state);
		}

		@Override
		public Identifier getTextureResource(GeoRenderState state) {
			String tex = state.getGeckolibData(TEXTURE);
			return tex != null ? Identifier.parse(tex) : of(state.getGeckolibData(MODEL)).getTextureResource(state);
		}

		@Override
		public Identifier getAnimationResource(MonsterEntity animatable) {
			MonsterDef d = animatable.def();
			return of(d == null ? null : d.model).getAnimationResource(animatable);
		}

		@Override
		public void addAdditionalStateData(MonsterEntity animatable, Object relatedObject, GeoRenderState state) {
			super.addAdditionalStateData(animatable, relatedObject, state);
			MonsterDef d = animatable.def();
			if (d != null) {
				state.addGeckolibData(MODEL, d.model);
				// 전원이 꺼져 있으면 눈 불이 꺼진 텍스처
				state.addGeckolibData(TEXTURE, animatable.powered() ? d.texture : d.textureOff);
				state.addGeckolibData(DISSOLVE, animatable.dissolve());
			}
		}
	}

	@Override
	public void captureDefaultRenderState(MonsterEntity entity, Void relatedObject, LivingEntityRenderState state, float partialTick) {
		super.captureDefaultRenderState(entity, relatedObject, state, partialTick);
		ColliderDef def = entity.colliderDef();
		if (def == null) {
			return;
		}
		// 정의의 배율 = 판정 도형 배율
		this.scaleWidth = (float) def.scale;
		this.scaleHeight = (float) def.scale;
		Skeleton sk = def.skeleton;
		Pose pose = new Pose(sk.size());
		entity.samplePose(entity.animTime(partialTick), pose);
		Map<String, double[]> bones = new HashMap<>();
		for (int i = 0; i < sk.size(); i++) {
			double[] r = pose.rotation(i);
			double[] p = pose.offset(i);
			bones.put(sk.boneName(i), new double[] {r[0], r[1], r[2], p[0], p[1], p[2]});
		}
		state.addGeckolibData(POSE, bones);
	}

	@Override
	public void adjustModelBonesForRender(RenderPassInfo<LivingEntityRenderState> info, BoneSnapshots snapshots) {
		Map<String, double[]> bones = info.renderState().getGeckolibData(POSE);
		if (bones == null) {
			return;
		}
		for (Map.Entry<String, double[]> e : bones.entrySet()) {
			double[] v = e.getValue();
			snapshots.ifPresent(e.getKey(), s -> {
				s.setRotation((float) Math.toRadians(-v[0]), (float) Math.toRadians(-v[1]), (float) Math.toRadians(v[2]));
				s.setTranslation((float) v[3], (float) v[4], (float) v[5]); // X 는 GeckoLib 이 그릴 때 뒤집음 (BoneSnapshot.translate)
			});
		}
	}

	/** 소멸 중: 투명도 = 1 − 진행 */
	@Override
	public int getRenderColor(MonsterEntity entity, Void relatedObject, float partialTick) {
		float d = entity.dissolve();
		int base = super.getRenderColor(entity, relatedObject, partialTick);
		if (d <= 0.0F) {
			return base;
		}
		int a = Math.max(0, Math.min(255, Math.round(255 * (1.0F - d))));
		return (a << 24) | (base & 0xFFFFFF);
	}

	/** 소멸 중에는 반투명 렌더 (평소에는 GeckoLib 기본) */
	@Override
	public net.minecraft.client.renderer.rendertype.RenderType getRenderType(LivingEntityRenderState state, Identifier texture) {
		Float d = state.getGeckolibData(DISSOLVE);
		if (d != null && d > 0.0F) {
			return net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texture);
		}
		return super.getRenderType(state, texture);
	}

	@Override
	protected float calculateYRot(MonsterEntity entity, float yBodyRot, float partialTick) {
		// 판정 루트와 같은 몸 방향 (보간된 yRot)
		return Mth.rotLerp(partialTick, entity.yRotO, entity.getYRot());
	}
}
