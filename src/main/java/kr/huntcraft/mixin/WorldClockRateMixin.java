package kr.huntcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import kr.huntcraft.core.vanilla.VanillaPace;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 목적: 낮밤(월드 시계)을 1초 20틱 속도로. 틱마다 시계가 rate 만큼 흐르므로 읽는 rate 를 1/3 로 줄입니다.
 * 클라이언트로 보내는 rate(packNetworkState)도 같이 줄여 클라이언트의 시계 예측이 서버와 어긋나지 않게 합니다.
 * 저장되는 rate 값 자체는 바꾸지 않아 /time rate 명령과 월드 파일은 바닐라 그대로입니다.
 */
@Mixin(targets = "net.minecraft.world.clock.ServerClockManager$ClockInstance")
public abstract class WorldClockRateMixin {
	@ModifyExpressionValue(method = {"tick", "packNetworkState"},
			at = @At(value = "FIELD", target = "Lnet/minecraft/world/clock/ServerClockManager$ClockInstance;rate:F"))
	private float huntcraft$vanillaPace(float rate) {
		return rate / VanillaPace.RATIO;
	}
}
