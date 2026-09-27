package kr.huntcraft.entity;

import kr.huntcraft.HuntCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/** 엔티티 등록. 시험 엔티티는 저장하지 않음 (월드를 다시 열면 사라짐). */
public final class HuntEntities {
	public static final EntityType<TrainingDummyEntity> TRAINING_DUMMY = register("training_dummy",
			EntityType.Builder.of(TrainingDummyEntity::new, MobCategory.MISC).sized(0.8F, 2.0F).noSave().clientTrackingRange(10));
	public static final EntityType<SwingRodEntity> SWING_ROD = register("swing_rod",
			EntityType.Builder.of(SwingRodEntity::new, MobCategory.MISC).sized(0.3F, 1.2F).noSave().clientTrackingRange(10));

	/**
	 * 보스 (종류 하나로 모든 보스 — 무엇인지는 defs/monsters 의 정의가 정함: 모델 · 판정 · 행동 · 크기).
	 * 지형 충돌 상자 크기는 정의의 size (MonsterEntity.getDefaultDimensions) — 여기 크기는 정의를 받기 전 임시값
	 */
	public static final EntityType<kr.huntcraft.monster.MonsterEntity> BOSS = register("boss",
			EntityType.Builder.of(kr.huntcraft.monster.MonsterEntity::new, MobCategory.MONSTER).sized(2.0F, 2.0F).clientTrackingRange(16));

	/** 보스가 던지는 잔해 (저장 안 함) */
	public static final EntityType<RubbleEntity> RUBBLE = register("rubble",
			EntityType.Builder.<RubbleEntity>of(RubbleEntity::new, MobCategory.MISC).sized(1.0F, 1.0F).noSave().clientTrackingRange(10)
					.updateInterval(1));

	private HuntEntities() {}

	public static void init() {
		net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry.register(BOSS,
				net.minecraft.world.entity.Mob.createMobAttributes()
						.add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, 2800.0)
						.add(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE, 1.0));
	}

	private static <T extends net.minecraft.world.entity.Entity> EntityType<T> register(String name, EntityType.Builder<T> builder) {
		ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, HuntCraft.id(name));
		return Registry.register(BuiltInRegistries.ENTITY_TYPE, key, builder.build(key));
	}
}
