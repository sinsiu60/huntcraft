package kr.huntcraft.mixin;

import kr.huntcraft.core.vanilla.VanillaPace;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 목적: 청크 틱(자연 스폰 · 무작위 틱 = 작물 성장 · 불 번짐 · 잎 부패 · 눈/비 쌓임 · 번개)을 3틱에 1번만 돌려 1초 20틱 속도로.
 * 청크 변경 전송(broadcastChangedChunks)은 이 메서드 밖이라 매 틱 그대로 나갑니다.
 */
@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCachePaceMixin {
	@Shadow
	@Final
	ServerLevel level;

	@Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("HEAD"), cancellable = true)
	private void huntcraft$chunkPace(ProfilerFiller profiler, long timeDelta, CallbackInfo ci) {
		if (!VanillaPace.runs(this.level)) {
			ci.cancel();
		}
	}
}
