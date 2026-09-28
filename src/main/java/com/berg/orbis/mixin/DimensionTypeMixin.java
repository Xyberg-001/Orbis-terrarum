package com.berg.orbis.mixin;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.OrbisConfig;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clouds at a height that suits the world's place. A dimension type's cloud
 * height is a fixed number in JSON, but real cities sit anywhere from sea
 * level to 3 600 m; one fixed layer either floats through Kathmandu's streets
 * or hangs invisibly high over Bergen. So the value is replaced by one
 * computed per world (about 800 m above the ground at the origin). It is
 * substituted whenever the dimension type's attributes are read, which
 * includes the registry data the server sends at login, so vanilla clients
 * get it too.
 */
@Mixin(DimensionType.class)
public abstract class DimensionTypeMixin {

    private static volatile EnvironmentAttributeMap orbis$cachedSource;
    private static volatile float orbis$cachedValue = Float.NaN;
    private static volatile EnvironmentAttributeMap orbis$cachedResult;

    @Inject(method = "attributes", at = @At("RETURN"), cancellable = true)
    private void orbis$cloudHeightForThisWorld(CallbackInfoReturnable<EnvironmentAttributeMap> cir) {
        DimensionType self = (DimensionType) (Object) this;
        if (self.minY() != OrbisConfig.DIMENSION_MIN_Y) return; // ours: the floor is fixed, the height is per world
        Float cloud = OrbisMod.cloudHeightOverride();
        if (cloud == null) return;
        EnvironmentAttributeMap source = cir.getReturnValue();
        EnvironmentAttributeMap result = orbis$cachedResult;
        if (result == null || orbis$cachedSource != source || orbis$cachedValue != cloud) {
            result = EnvironmentAttributeMap.builder().putAll(source).set(EnvironmentAttributes.CLOUD_HEIGHT, cloud).build();
            orbis$cachedSource = source;
            orbis$cachedValue = cloud;
            orbis$cachedResult = result;
        }
        cir.setReturnValue(result);
    }
}
