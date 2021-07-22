package minecrafttransportsimulator.rendering.components;

import minecrafttransportsimulator.entities.components.AEntityC_Definable;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;

/**This class represents a pseudo-animation that disables other animations.
 * This transform will disable the other animations of this
 * object if the returned value of the variable is within the min and max clamps.
 *
 * @author gyro_hero
 */
public class TransformInhibitor<AnimationEntity extends AEntityC_Definable<?>> extends ATransformDefinable<AnimationEntity>{
	
	public TransformInhibitor(JSONAnimationDefinition definition){
		super(definition);
	}
	
	@Override
	public boolean shouldInhibit(AnimationEntity entity, boolean blendingEnabled, float partialTicks){
		double value = definition.offset + entity.getAnimatedVariableValue(entity.renderAnimationClocks.get(definition), 0D, partialTicks);
		return value >= definition.clampMin && value <= definition.clampMax;
	}

	@Override
	public double applyTransform(AnimationEntity entity, boolean blendingEnabled, float partialTicks, double offset){
		//Do nothing.
		return 0;
	}
}
