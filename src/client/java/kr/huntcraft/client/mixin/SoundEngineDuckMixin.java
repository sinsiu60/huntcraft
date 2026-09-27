package kr.huntcraft.client.mixin;

import kr.huntcraft.client.sound.SoundDuck;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 목적: 더킹 — 큰 공격이 땅에 닿는 순간 다른 소리 종류의 볼륨을 잠깐 낮춤 (SoundDuck).
 * 바닐라의 종류별 gain (MusicManager 가 음악 페이드에 씀) 은 건드리지 않고 최종 볼륨에만 곱함.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineDuckMixin {
	@Inject(method = "calculateVolume(FLnet/minecraft/sounds/SoundSource;)F", at = @At("RETURN"), cancellable = true)
	private void huntcraft$duck(float volume, SoundSource source, CallbackInfoReturnable<Float> cir) {
		float f = SoundDuck.factor(source);
		if (f < 1.0F) {
			cir.setReturnValue(cir.getReturnValueF() * f);
		}
	}
}
