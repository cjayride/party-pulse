package cjayride.partypulse.mixin;

import cjayride.partypulse.PartyPulse;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Optional Spell Engine hook. Uses string targets and Object placeholders so
 * the mixin class can load even when Spell Engine is absent; the mixin plugin
 * skips applying it in that case.
 */
@Mixin(targets = "net.spell_engine.internals.SpellHelper", remap = false)
public abstract class SpellEngineHealingMixin {

	@Unique
	private static final ThreadLocal<LivingEntity> partyPulse$currentCaster = new ThreadLocal<>();

	@Inject(method = "performImpact", at = @At("HEAD"), require = 0)
	private static void partyPulse$stashCaster(
			World world,
			LivingEntity caster,
			Entity target,
			Object spellInfo,
			Object impact,
			Object context,
			Collection<ServerPlayerEntity> trackers,
			CallbackInfo ci
	) {
		partyPulse$currentCaster.set(caster);
	}

	@Inject(method = "performImpact", at = @At("RETURN"), require = 0)
	private static void partyPulse$clearCaster(CallbackInfo ci) {
		partyPulse$currentCaster.remove();
	}

	@Redirect(
			method = "performImpact",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/entity/LivingEntity;heal(F)V",
					remap = true
			),
			require = 0
	)
	private static void partyPulse$captureHealingDone(LivingEntity healedEntity, float requestedAmount) {
		float healthBefore = healedEntity.getHealth();
		healedEntity.heal(requestedAmount);
		float healingDone = healedEntity.getHealth() - healthBefore;

		LivingEntity caster = partyPulse$currentCaster.get();
		if (healingDone <= 0.0f || !(caster instanceof ServerPlayerEntity healer)) return;

		PartyPulse.recordHealing(healer, healingDone, healer.getWorld().getTime());
	}
}
