package cjayride.partypulse.mixin;

import cjayride.partypulse.PartyPulse;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures effective (post-mitigation) damage at the moment applyDamage
 * requests the victim's health change. Reading the requested value at the
 * setHealth call site - instead of measuring the health delta afterwards -
 * also works for entities that override setHealth without applying it,
 * such as target dummies.
 *
 * Absorption-consumed damage is recorded via the RETURN fallback so hits
 * fully eaten by absorption hearts (which skip setHealth) still count.
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public class LivingEntityMixin {

    @Unique
    private float partyPulse$absorptionBefore;
    @Unique
    private boolean partyPulse$recorded;
    @Unique
    private DamageSource partyPulse$currentSource;

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void partyPulse$beforeApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        partyPulse$absorptionBefore = self.getAbsorptionAmount();
        partyPulse$recorded = false;
        partyPulse$currentSource = source;
    }

    @ModifyArg(
            method = "applyDamage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/LivingEntity;setHealth(F)V"
            )
    )
    private float partyPulse$captureHealthDamage(float newHealth) {
        LivingEntity self = (LivingEntity) (Object) this;
        float healthDamage = self.getHealth() - newHealth;
        float absorbed = partyPulse$absorptionBefore - self.getAbsorptionAmount();
        partyPulse$recorded = true;
        PartyPulse.recordEffectiveDamage(self, partyPulse$currentSource, healthDamage + Math.max(0.0f, absorbed));
        return newHealth;
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void partyPulse$afterApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        if (partyPulse$recorded) return;
        LivingEntity self = (LivingEntity) (Object) this;
        float absorbed = partyPulse$absorptionBefore - self.getAbsorptionAmount();
        if (absorbed > 0.0f) {
            PartyPulse.recordEffectiveDamage(self, source, absorbed);
        }
    }
}
