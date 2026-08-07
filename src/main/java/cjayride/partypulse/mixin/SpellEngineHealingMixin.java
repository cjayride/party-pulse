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
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;

/**
 * Optional Spell Engine hook (skipped by PartyPulseMixinPlugin when the mod
 * is absent). Captures effective healing and attributes it to the caster.
 */
@Mixin(value = SpellHelper.class, remap = false)
public abstract class SpellEngineHealingMixin {

	@Redirect(
			method = "performImpact",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/LivingEntity;heal(F)V",
					remap = true
			),
			require = 0
	)
	private static void partyPulse$captureHealingDone(
			LivingEntity healedEntity,
			float requestedAmount,
			World world,
			LivingEntity caster,
			Entity target,
			SpellInfo spellInfo,
			Spell.Impact impact,
			SpellHelper.ImpactContext context,
			Collection<ServerPlayerEntity> trackers
	) {
		// Mark so LivingEntityMixin does not also credit the healed player.
		PartyPulse.markHealAttributed();
		try {
			float healthBefore = healedEntity.getHealth();
			healedEntity.heal(requestedAmount);
			float healingDone = healedEntity.getHealth() - healthBefore;

			if (healingDone <= 0.0f || !(caster instanceof ServerPlayerEntity healer)) return;

			PartyPulse.recordHealing(healer, healingDone, healer.getWorld().getTime());
		} finally {
			PartyPulse.clearHealAttributed();
		}
	}
}
