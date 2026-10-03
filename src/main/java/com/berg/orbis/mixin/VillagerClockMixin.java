package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.sky.LocalClock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.UpdateActivityFromSchedule;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Villagers who keep the real local clock in Orbis worlds with real daylight (the world's "villagers keep clock
 * hours" setting): their day of waking, work, meeting and sleep follows the town's real time instead of the sun,
 * which in the north rises at nine in winter and sets at eleven in summer. Other mobs, and villagers elsewhere,
 * keep Minecraft's schedule. The switch is only asked once a second per villager, as Minecraft does it.
 */
@Mixin(UpdateActivityFromSchedule.class)
public abstract class VillagerClockMixin {

    @Redirect(method = "lambda$create$1", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/Brain;updateActivityFromSchedule(Lnet/minecraft/world/attribute/EnvironmentAttributeSystem;JLnet/minecraft/world/phys/Vec3;)V"))
    private static void orbisterrarum$clockHours(Brain<?> brain, EnvironmentAttributeSystem attributes, long gameTime, Vec3 pos,
                                                 ServerLevel level, LivingEntity entity, long time) {
        if (entity instanceof Villager villager && OrbisMod.isOrbisSeaLevel(level.getSeaLevel())) {
            var model = OrbisMod.model();
            if (model != null && model.cfg().villagerClockHours && model.cfg().realDaylight) {
                if (villager.tickCount % 20 == 0) {
                    Activity want = LocalClock.villagerActivity(villager.isBaby());
                    if (!brain.getActiveActivities().contains(want)) brain.setActiveActivityIfPossible(want);
                }
                return;
            }
        }
        brain.updateActivityFromSchedule(attributes, gameTime, pos);
    }
}
