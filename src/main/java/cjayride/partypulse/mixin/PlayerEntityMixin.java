package cjayride.partypulse.mixin;

import cjayride.partypulse.PartyPulse;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * PlayerEntity overrides applyDamage without calling super, so PvP damage
 * needs its own capture. Same approach as LivingEntityMixin: read the
 * requested health change at the setHealth call site, with a RETURN
 * fallback for hits fully absorbed by absorption hearts.
 */
@Mixin(value = PlayerEntity.class, priority = 2000)
public class PlayerEntityMixin {

    @Unique
    private float partyPulse$absorptionBefore;
    @Unique
    private boolean partyPulse$recorded;
    @Unique
    private DamageSource partyPulse$currentSource;

    @Inject(method = "applyDamage", at = @At("HEAD"))
    private void partyPulse$beforeApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        partyPulse$absorptionBefore = self.getAbsorptionAmount();
        partyPulse$recorded = false;
        partyPulse$currentSource = source;
    }

    @ModifyArg(
            method = "applyDamage",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;setHealth(F)V"
            )
    )
    private float partyPulse$captureHealthDamage(float newHealth) {
        PlayerEntity self = (PlayerEntity) (Object) this;
        float healthDamage = self.getHealth() - newHealth;
        float absorbed = partyPulse$absorptionBefore - self.getAbsorptionAmount();
        partyPulse$recorded = true;
        PartyPulse.recordEffectiveDamage(self, partyPulse$currentSource, healthDamage + Math.max(0.0f, absorbed));
        return newHealth;
    }

    @Inject(method = "applyDamage", at = @At("RETURN"))
    private void partyPulse$afterApplyDamage(DamageSource source, float amount, CallbackInfo ci) {
        if (partyPulse$recorded) return;
        PlayerEntity self = (PlayerEntity) (Object) this;
        float absorbed = partyPulse$absorptionBefore - self.getAbsorptionAmount();
        if (absorbed > 0.0f) {
            PartyPulse.recordEffectiveDamage(self, source, absorbed);
        }
    }
}
