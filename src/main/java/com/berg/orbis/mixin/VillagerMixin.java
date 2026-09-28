package com.berg.orbis.mixin;

import com.berg.orbis.render.SettlementBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * City residents are nitwits so that trading stays something found in real
 * villages. Vanilla babies are born unemployed, which would be a way around
 * that, so a child with a resident parent is born a nitwit and a resident
 * itself, whoever the other parent is.
 */
@Mixin(Villager.class)
public abstract class VillagerMixin {

    /**
     * A city has hundreds of residents in simulation range, and a villager's brain (sensors, point-of-interest
     * scans, path planning) is the dearest AI in the game. Residents think on every third tick only; the
     * navigation that actually moves them still runs every tick, so they walk as smoothly as before but cost a
     * third. Real villagers (untagged) are untouched.
     */
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void orbisterrarum$residentsThinkLess(CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if ((self.tickCount + self.getId()) % 3 != 0 && self.entityTags().contains(SettlementBuilder.RESIDENT_TAG)) ci.cancel();
    }

    @Inject(method = "getBreedOffspring(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/AgeableMob;)Lnet/minecraft/world/entity/npc/villager/Villager;",
            at = @At("RETURN"))
    private void orbisterrarum$residentChild(ServerLevel level, AgeableMob partner, CallbackInfoReturnable<Villager> cir) {
        Villager child = cir.getReturnValue();
        if (child == null) return;
        Villager self = (Villager) (Object) this;
        boolean residentParent = self.entityTags().contains(SettlementBuilder.RESIDENT_TAG)
                || (partner != null && partner.entityTags().contains(SettlementBuilder.RESIDENT_TAG));
        if (!residentParent) return;
        child.setVillagerData(child.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.NITWIT));
        child.setVillagerDataFinalized(true);
        child.addTag(SettlementBuilder.RESIDENT_TAG);
    }
}
