package kr.huntcraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kr.huntcraft.client.combat.ClientHunters;
import kr.huntcraft.collision.ColliderDef;
import kr.huntcraft.collision.ColliderInstance;
import kr.huntcraft.collision.Skeleton;
import kr.huntcraft.collision.Transform;
import kr.huntcraft.combat.HunterBody;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * 태도 (플레이스홀더 모델) — hunter.geo.json 의 katana 본 큐브(손잡이 · 코등이 · 칼날)를 단색 상자로 그림.
 * katana 본 변환 그대로 (판정 캡슐과 같은 본 변환이라 칼날 = 판정 위치). 납도 때 등에 메는 자세는 납도 대기 애니메이션.
 * 자체 모델 · 텍스처가 생기면 GeckoLib 무기 모델로 바꿉니다 (같은 본 변환 사용).
 */
public final class KatanaRenderer {
	private static final int[] COLORS = {0xFF2A2320, 0xFF5A4A2A, 0xFFD6DCE4};
	/** 손에 든 아이템 (held_<아이템 id> 본의 큐브) — 병 몸통 · 마개 순 */
	private static final java.util.Map<String, int[]> HELD_COLORS = java.util.Map.of(
			"flask", new int[] {0xFFE0A03A, 0xFF8A6A3A});

	private KatanaRenderer() {}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(ctx -> submit(ctx.poseStack(), ctx.submitNodeCollector()));
	}

	private static void submit(PoseStack pose, SubmitNodeCollector collector) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			return;
		}
		Camera camera = mc.gameRenderer.mainCamera();
		if (!camera.isInitialized()) {
			return;
		}
		Vec3 cam = camera.position();
		float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
		collector.submitCustomGeometry(pose, RenderTypes.debugQuads(), (p, buffer) -> {
			for (HunterBody body : ClientHunters.all()) {
				ColliderDef def = body.colliderDef();
				if (def == null || body.player().isInvisible()) {
					continue;
				}
				Skeleton sk = def.skeleton;
				int katana = sk.find("katana");
				if (katana < 0) {
					continue;
				}
				Transform[] bones = ColliderInstance.solve(body, partial, null);
				if (bones == null) {
					continue;
				}
				// 발도 · 납도 모두 katana 본 (납도는 납도 대기 자세가 칼을 등에 옮겨 둠) — 판정 캡슐과 같은 변환
				Transform place = bones[katana].then(sk.rest(katana).inverse());
				int i = 0;
				for (Skeleton.Cube c : sk.cubes(katana)) {
					box(p, buffer, place, c, cam, COLORS[Math.min(i++, COLORS.length - 1)]);
				}
				// 아이템 사용 중이면 손에 든 물건 (held 본이 애니메이션으로 움직이고 오른팔이 거기로 뻗음)
				int item = body.combat().usingItem();
				java.util.List<kr.huntcraft.config.CommonConfig.ItemDef> items = kr.huntcraft.config.CommonConfig.get().items;
				if (item >= 0 && item < items.size()) {
					String id = items.get(item).id;
					int held = sk.find("held_" + id);
					int[] colors = HELD_COLORS.getOrDefault(id, new int[] {0xFFB0B0B0});
					if (held >= 0) {
						Transform hp = bones[held].then(sk.rest(held).inverse());
						int k = 0;
						for (Skeleton.Cube c : sk.cubes(held)) {
							box(p, buffer, hp, c, cam, colors[Math.min(k++, colors.length - 1)]);
						}
					}
				}
			}
		});
	}

	private static void box(PoseStack.Pose pose, VertexConsumer buffer, Transform t, Skeleton.Cube c, Vec3 cam, int color) {
		Vec3[] v = new Vec3[8];
		for (int i = 0; i < 8; i++) {
			Vec3 local = new Vec3((i & 4) != 0 ? c.max().x : c.min().x, (i & 2) != 0 ? c.max().y : c.min().y, (i & 1) != 0 ? c.max().z : c.min().z);
			v[i] = t.apply(local).subtract(cam);
		}
		int[][] faces = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};
		for (int[] f : faces) {
			// 면마다 살짝 다른 밝기 (조명 없는 단색이라 모양이 보이게)
			int shade = shade(color, f[0] == 0 && f[1] == 1 ? 0.8F : f[0] == 4 ? 0.9F : 1.0F);
			for (int k : f) {
				buffer.addVertex(pose, (float) v[k].x, (float) v[k].y, (float) v[k].z).setColor(shade);
			}
			// 뒷면도 보이게 반대 순서로 한 번 더
			for (int k = 3; k >= 0; k--) {
				buffer.addVertex(pose, (float) v[f[k]].x, (float) v[f[k]].y, (float) v[f[k]].z).setColor(shade);
			}
		}
	}

	private static int shade(int argb, float f) {
		int r = (int) (((argb >> 16) & 0xFF) * f);
		int g = (int) (((argb >> 8) & 0xFF) * f);
		int b = (int) ((argb & 0xFF) * f);
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
