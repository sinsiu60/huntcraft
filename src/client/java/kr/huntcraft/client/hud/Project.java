package kr.huntcraft.client.hud;

import kr.huntcraft.client.camera.ShoulderCamera;
import kr.huntcraft.client.config.ClientConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

/**
 * 월드 좌표 → HUD(GUI) 좌표. 카메라 위치 · 각도와 세로 시야각으로 직접 계산 (숄더뷰는 시야각을 camera.fov 로 고정).
 * 화면 흔들림 · 시야각 효과(달리기 FOV 등)는 무시해서 1~2px 어긋날 수 있음.
 */
public final class Project {
	private Project() {}

	/** [x, y] GUI 좌표, 카메라 뒤면 null */
	public static float[] toScreen(Minecraft mc, Vec3 world, int guiWidth, int guiHeight) {
		Camera cam = mc.gameRenderer.mainCamera();
		if (!cam.isInitialized()) {
			return null;
		}
		Vec3 rel = world.subtract(cam.position());
		double yaw = Math.toRadians(cam.yRot());
		double pitch = Math.toRadians(cam.xRot());
		Vec3 forward = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
		Vec3 right = new Vec3(-Math.cos(yaw), 0.0, -Math.sin(yaw));
		Vec3 up = right.cross(forward);
		double z = rel.dot(forward);
		if (z < 0.05) {
			return null;
		}
		double fov = ShoulderCamera.active(mc) ? ClientConfig.get().camera.fov : cam.getFov();
		double f = (guiHeight / 2.0) / Math.tan(Math.toRadians(fov) / 2.0);
		return new float[] {(float) (guiWidth / 2.0 + rel.dot(right) / z * f), (float) (guiHeight / 2.0 - rel.dot(up) / z * f)};
	}
}
