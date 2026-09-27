package kr.huntcraft.client.mixin;

import kr.huntcraft.client.sound.BossMusic;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 목적: 보스 음악이 도는 동안 바닐라 배경 음악을 멈추고 새로 시작하지 않게 (두 곡이 겹치지 않음) */
@Mixin(MusicManager.class)
public abstract class MusicManagerBossMixin {
	@Inject(method = "tick", at = @At("HEAD"), cancellable = true)
	private void huntcraft$bossMusic(CallbackInfo ci) {
		if (BossMusic.suppressing()) {
			((MusicManager) (Object) this).stopPlaying();
			ci.cancel();
		}
	}
}
