package cjayride.partypulse.mixin;

import cjayride.partypulse.PartyPulse;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.spell_engine.api.spell.Spell;
import net.spell_engine.api.spell.SpellInfo;
import net.spell_engine.internals.SpellHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;

/**
 * Optional Spell Engine hook (skipped by PartyPulseMixinPlugin when the mod
 * is absent).
 * <p>
 * Does <b>not</b> {@code @Redirect} {@link LivingEntity#heal(float)} — Prominence /
 * Prominent also redirects that call for priest-set bonuses. Competing redirects
 * crash on join. Instead we stash the caster around the heal invoke; effective
 * healing is measured in {@link LivingEntityMixin} and attributed to that caster.
 */
@Mixin(value = SpellHelper.class, remap = false, priority = 500)
public abstract class SpellEngineHealingMixin {

	@Inject(
			method = "performImpact",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/LivingEntity;heal(F)V",
					remap = true
			),
			require = 0
	)
	private static void partyPulse$stashSpellHealer(
			World world,
			LivingEntity caster,
			Entity target,
			SpellInfo spellInfo,
			Spell.Impact impact,
			SpellHelper.ImpactContext context,
			Collection<ServerPlayerEntity> trackers,
			CallbackInfoReturnable<Boolean> cir
	) {
		if (caster instanceof ServerPlayerEntity healer) {
			PartyPulse.setSpellEngineHealer(healer);
		}
	}

	@Inject(
			method = "performImpact",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/LivingEntity;heal(F)V",
					shift = At.Shift.AFTER,
					remap = true
			),
			require = 0
	)
	private static void partyPulse$clearSpellHealerAfterHeal(
			World world,
			LivingEntity caster,
			Entity target,
			SpellInfo spellInfo,
			Spell.Impact impact,
			SpellHelper.ImpactContext context,
			Collection<ServerPlayerEntity> trackers,
			CallbackInfoReturnable<Boolean> cir
	) {
		PartyPulse.clearSpellEngineHealer();
	}

	@Inject(method = "performImpact", at = @At("RETURN"), require = 0)
	private static void partyPulse$clearSpellHealerOnReturn(
			World world,
			LivingEntity caster,
			Entity target,
			SpellInfo spellInfo,
			Spell.Impact impact,
			SpellHelper.ImpactContext context,
			Collection<ServerPlayerEntity> trackers,
			CallbackInfoReturnable<Boolean> cir
	) {
		PartyPulse.clearSpellEngineHealer();
	}
}
