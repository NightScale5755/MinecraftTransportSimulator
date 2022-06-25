package minecrafttransportsimulator.entities.components;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import minecrafttransportsimulator.baseclasses.AnimationSwitchbox;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.ColorRGB;
import minecrafttransportsimulator.baseclasses.Damage;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.items.instances.ItemInstrument;
import minecrafttransportsimulator.jsondefs.AJSONInteractableEntity;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONInstrument.JSONInstrumentComponent;
import minecrafttransportsimulator.jsondefs.JSONInstrumentDefinition;
import minecrafttransportsimulator.jsondefs.JSONVariableModifier;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketEntityRiderChange;
import minecrafttransportsimulator.packets.instances.PacketEntityVariableIncrement;
import minecrafttransportsimulator.packets.instances.PacketPlayerChatMessage;
import minecrafttransportsimulator.rendering.components.DurationDelayClock;
import minecrafttransportsimulator.rendering.components.RenderableObject;
import minecrafttransportsimulator.rendering.instances.RenderInstrument.InstrumentSwitchbox;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.systems.PackParserSystem;

/**Base entity class containing riders and their positions on this entity.  Used for
 * entities that need to keep track of riders and their locations.  This also contains
 * various collision box lists for collision, as riders cannot interact and start riding
 * entities without collision boxes to click.
 * 
 * @author don_bruce
 */
public abstract class AEntityE_Interactable<JSONDefinition extends AJSONInteractableEntity> extends AEntityD_Definable<JSONDefinition>{
	
	/**List of boxes generated from JSON.  These are stored here as objects since they may not be
	 * added to their respective maps if they aren't active.**/
	public final Map<JSONCollisionGroup, Set<BoundingBox>> definitionCollisionBoxes = new HashMap<JSONCollisionGroup, Set<BoundingBox>>();
	private final Map<JSONCollisionGroup, AnimationSwitchbox> collisionSwitchboxes = new HashMap<JSONCollisionGroup, AnimationSwitchbox>();
	
	/**List of bounding boxes that should be used to check collision of this entity with blocks.**/
	public final Set<BoundingBox> blockCollisionBoxes = new HashSet<BoundingBox>();
	
	/**List of bounding boxes that should be used for collision of other entities with this entity.
	 * This includes {@link #blockCollisionBoxes}, but may include others.**/
	public final Set<BoundingBox> entityCollisionBoxes = new HashSet<BoundingBox>();
	
	/**List of bounding boxes that should be used for interaction of other entities with this entity.
	 * This includes all {@link #entityCollisionBoxes}, but may include others, most likely being the
	 * core {@link #boundingBox} for this entity.**/
	public final Set<BoundingBox> interactionBoxes = new HashSet<BoundingBox>();
	
	/**Box that encompasses all boxes on this entity.  This can be used as a pre-check for collision operations
	 * to check a single large box rather than multiple small ones to save processing power.**/
	public final BoundingBox encompassingBox = new BoundingBox(new Point3D(), new Point3D(), 0, 0, 0, false);
	
	/**Set of entities that this entity collided with this tick.  Any entity that is in this set 
	 * should NOT do collision checks with this entity, or infinite loops will occur.
	 * This set should be cleared after all collisions have been checked.**/
	public final Set<AEntityE_Interactable<?>> collidedEntities = new HashSet<AEntityE_Interactable<?>>();
	
	/**The entity that is currently riding this entity.  There is only one rider per entity, though one can
	 * make a multipart entity where each part has a rider to allow for effectively multiple riders per entity.
	 **/
	public IWrapperEntity rider;
	
	/**List of instruments based on their slot in the JSON.  Note that this list is created on first construction
	 * and will contain null elements for any instrument that isn't present in that slot.
	 * Do NOT modify this list directly.  Instead, use the add/remove methods in this class.
	 * This ensures proper animation component creation.
	 **/
	public final List<ItemInstrument> instruments = new ArrayList<ItemInstrument>();
	
	/**Similar to {@link #instruments}, except this is the renderable bits for them.  There's one entry for each component, 
	 * with text being a null entry as text components render via the text rendering system.*/
	public final List<List<RenderableObject>> instrumentRenderables = new ArrayList<List<RenderableObject>>();
	
	/**Maps instrument components to their respective switchboxes.**/
	public final Map<JSONInstrumentComponent, InstrumentSwitchbox> instrumentComponentSwitchboxes = new LinkedHashMap<JSONInstrumentComponent, InstrumentSwitchbox>();
	
	/**Maps instrument slot transforms to their respective switchboxes.**/
	public final Map<JSONInstrumentDefinition, AnimationSwitchbox> instrumentSlotSwitchboxes = new LinkedHashMap<JSONInstrumentDefinition, AnimationSwitchbox>();
	
	/**Maps variable modifers to their respective switchboxes.**/
	public final Map<JSONVariableModifier, VariableModifierSwitchbox> variableModiferSwitchboxes = new LinkedHashMap<JSONVariableModifier, VariableModifierSwitchbox>();
	
	/**Locked state.  Locked entities should not be able to be interacted with except by entities riding them,
	 * their owners, or OP players (server admins).
	 **/
	public boolean locked;
	
	/**The ID of the owner of this entity. If this is null, it can be assumed that there is no owner.
	 * UUIDs are set at creation time of an entity, and will never change, even on world re-loads.
	 **/
	public final UUID ownerUUID;
	
	/**The amount of damage on this entity.  This value is not necessarily used on all entities, but is put here
	 * as damage is something that a good number of entities will have and that the base entity should track.
	 **/
	@DerivedValue
	public double damageAmount;
	public static final String DAMAGE_VARIABLE = "damage";
	
	public AEntityE_Interactable(AWrapperWorld world, IWrapperPlayer placingPlayer, IWrapperNBT data){
		super(world, placingPlayer, data);
		this.locked = data.getBoolean("locked");
		this.ownerUUID = placingPlayer != null ? placingPlayer.getID() : data.getUUID("ownerUUID");
		
		//Load instruments.  If we are new, create the default ones.
		if(definition.instruments != null){
			//Need to init lists.
			for(int i=0; i<definition.instruments.size(); ++i){
				instruments.add(null);
				instrumentRenderables.add(null);
			}
			if(newlyCreated){
				for(JSONInstrumentDefinition packInstrument : definition.instruments){
					if(packInstrument.defaultInstrument != null){
						try{
							String instrumentPackID = packInstrument.defaultInstrument.substring(0, packInstrument.defaultInstrument.indexOf(':'));
							String instrumentSystemName = packInstrument.defaultInstrument.substring(packInstrument.defaultInstrument.indexOf(':') + 1);
							try{
								ItemInstrument instrument = PackParserSystem.getItem(instrumentPackID, instrumentSystemName);
								if(instrument != null){
									addInstrument(instrument, definition.instruments.indexOf(packInstrument));
									continue;
								}
							}catch(NullPointerException e){
								placingPlayer.sendPacket(new PacketPlayerChatMessage(placingPlayer, "Attempted to add defaultInstrument: " + instrumentPackID + ":" + instrumentSystemName + " to: " + definition.packID + ":" + definition.systemName + " but that instrument doesn't exist in the pack item registry."));
							}
						}catch(IndexOutOfBoundsException e){
							placingPlayer.sendPacket(new PacketPlayerChatMessage(placingPlayer, "Could not parse defaultInstrument definition: " + packInstrument.defaultInstrument + ".  Format should be \"packId:instrumentName\""));
						}
					}
				}
			}else{
				for(int i = 0; i<definition.instruments.size(); ++i){
					String instrumentPackID = data.getString("instrument" + i + "_packID");
					String instrumentSystemName = data.getString("instrument" + i + "_systemName");
					if(!instrumentPackID.isEmpty()){
						ItemInstrument instrument = PackParserSystem.getItem(instrumentPackID, instrumentSystemName);
						//Check to prevent loading of faulty instruments due to updates.
						if(instrument != null){
							addInstrument(instrument, i);
						}
					}
				}
			}
		}
	}
	
	@Override
	protected void initializeDefinition(){
		super.initializeDefinition();
		//Create collision boxes.
		if(definition.collisionGroups != null){
			definitionCollisionBoxes.clear();
			collisionSwitchboxes.clear();
			for(JSONCollisionGroup groupDef : definition.collisionGroups){
				Set<BoundingBox> boxes = new HashSet<BoundingBox>();
				for(JSONCollisionBox boxDef : groupDef.collisions){
					boxes.add(new BoundingBox(boxDef, groupDef));
				}
				definitionCollisionBoxes.put(groupDef, boxes);
				if(groupDef.animations != null || groupDef.applyAfter != null){
					List<JSONAnimationDefinition> animations = new ArrayList<JSONAnimationDefinition>();
					if(groupDef.animations != null){
						animations.addAll(groupDef.animations);
					}
					collisionSwitchboxes.put(groupDef, new AnimationSwitchbox(this, animations, groupDef.applyAfter));
				}
			}
		}
		//Update collision boxes as they might have changed.
		updateCollisionBoxes();
		
		//Create instrument lists and animation clocks.
		if(definition.instruments != null){
			//Check for existing instruments and save them.  Then make new ones based on JSON.
			List<ItemInstrument> oldInstruments = new ArrayList<ItemInstrument>();
			oldInstruments.addAll(instruments);
			instruments.clear();
			instrumentRenderables.clear();
			instrumentSlotSwitchboxes.clear();
			for(int i=0; i<definition.instruments.size(); ++i){
				instruments.add(null);
				instrumentRenderables.add(null);
				if(i < oldInstruments.size()){
					ItemInstrument oldInstrument = oldInstruments.get(i);
					if(oldInstrument != null){
						addInstrument(oldInstrument, i);
					}
				}
			}
			
			//Old instruments added, make animation definitions.
			for(JSONInstrumentDefinition packInstrument : definition.instruments){
				if(packInstrument.animations != null){
					List<JSONAnimationDefinition> animations = new ArrayList<JSONAnimationDefinition>();
					if(packInstrument.animations != null){
						animations.addAll(packInstrument.animations);
					}
					instrumentSlotSwitchboxes.put(packInstrument, new AnimationSwitchbox(this, animations, packInstrument.applyAfter));
				}
			}
		}
		
		//Add variable modifiers.
		if(definition.variableModifiers != null){
			variableModiferSwitchboxes.clear();
			for(JSONVariableModifier modifier : definition.variableModifiers){
				if(modifier.animations != null){
					variableModiferSwitchboxes.put(modifier,  new VariableModifierSwitchbox(this, modifier.animations));
				}
			}
			
		}
	}
	
	@Override
	public void update(){
		super.update();
		
		world.beginProfiling("EntityE_Level", true);
		//Update damage value
		damageAmount = getVariable(DAMAGE_VARIABLE);
		world.endProfiling();
	}
	
	@Override
    public void remove(){
        super.remove();
        if(rider != null) {
            removeRider();
        }
    }
	
	@Override
	public double getMass(){
		return rider != null ? 100 : 0;
	}
	
	@Override
	public double getRawVariableValue(String variable, float partialTicks){
		switch(variable){
			case("damage_percent"): return damageAmount/definition.general.health;
		}
		
		//Not a towing variable, check others.
		return super.getRawVariableValue(variable, partialTicks);
	}
	
	/**
   	 *  Updates the position of all collision boxes, and sets them in their appropriate maps based on their
   	 *  properties, and animation state (if applicable). 
   	 */
    protected void updateCollisionBoxes(){
    	blockCollisionBoxes.clear();
    	entityCollisionBoxes.clear();
    	interactionBoxes.clear();
    	
    	if(definition.collisionGroups != null){
			for(JSONCollisionGroup groupDef : definition.collisionGroups){
				Set<BoundingBox> collisionBoxes = definitionCollisionBoxes.get(groupDef);
				if(collisionBoxes == null){
					//This can only happen if we hotloaded the definition due to devMode.
					//Flag us as needing a reset, and then bail to prevent further collision checks.
					animationsInitialized = false;
					return;
				}
				if(groupDef.health == 0 || getVariable("collision_" + (definition.collisionGroups.indexOf(groupDef) + 1) + "_damage") < groupDef.health){
					AnimationSwitchbox switchBox = this.collisionSwitchboxes.get(groupDef);
					if(switchBox != null){
						if(switchBox.runSwitchbox(0, false)){
							for(BoundingBox box : collisionBoxes){
								box.globalCenter.set(box.localCenter).transform(switchBox.netMatrix);
								box.updateToEntity(this, box.globalCenter);
							}
						}else{
							//Don't let these boxes get added to the list.
							continue;
						}
					}else{
						for(BoundingBox box : collisionBoxes){
							box.updateToEntity(this, null);
						}
					}
					entityCollisionBoxes.addAll(collisionBoxes);
					if(!groupDef.isInterior && !ConfigSystem.settings.general.noclipVehicles.value){
						blockCollisionBoxes.addAll(collisionBoxes);
					}
				}
			}
    	}
    	interactionBoxes.addAll(entityCollisionBoxes);
    	
    	//Now get the encompassing box.
    	encompassingBox.widthRadius = 0;
    	encompassingBox.heightRadius = 0;
    	encompassingBox.depthRadius = 0;
    	for(BoundingBox box : interactionBoxes){
    		encompassingBox.widthRadius = (float) Math.max(encompassingBox.widthRadius, Math.abs(box.globalCenter.x - position.x + box.widthRadius));
    		encompassingBox.heightRadius = (float) Math.max(encompassingBox.heightRadius, Math.abs(box.globalCenter.y - position.y + box.heightRadius));
    		encompassingBox.depthRadius = (float) Math.max(encompassingBox.depthRadius, Math.abs(box.globalCenter.z - position.z + box.depthRadius));
    	}
    	encompassingBox.updateToEntity(this, null);
    }
    
    /**
	 * Called to update the variable modifiers for this entity.
	 * By default, this will get any variables that {@link #getVariable(String)}
	 * returns, but can be extended to do other variables specific to the entity.
	 */
	protected void updateVariableModifiers(){
		if(definition.variableModifiers != null){
			for(JSONVariableModifier modifier : definition.variableModifiers){
				setVariable(modifier.variable, adjustVariable(modifier, (float) getVariable(modifier.variable)));
			}
		}
	}
	
	 /**
	 * Helper method for variable modification.
	 */
	protected float adjustVariable(JSONVariableModifier modifier, float currentValue){
		float modifiedValue = modifier.setValue != 0 ? modifier.setValue : currentValue + modifier.addValue;
		VariableModifierSwitchbox switchbox = variableModiferSwitchboxes.get(modifier);
		if(switchbox != null){
			switchbox.modifiedValue = modifiedValue;
			if(switchbox.runSwitchbox(0, true)){
				modifiedValue = switchbox.modifiedValue;
			}else{
				return currentValue;
			}
		}
		if(modifier.minValue != 0 || modifier.maxValue != 0){
			if(modifiedValue < modifier.minValue){
				return modifier.minValue;
			}else if(modifiedValue > modifier.maxValue){
				return modifier.maxValue;
			}
		}
		return modifiedValue;
	}
	
	/**
	 *  Custom variable modifier switchbox class.
	 */
	private static class VariableModifierSwitchbox extends AnimationSwitchbox{
		private float modifiedValue = 0;
		
		private VariableModifierSwitchbox(AEntityD_Definable<?> entity, List<JSONAnimationDefinition> animations){
			super(entity, animations, null);
		}
		
		@Override
		public void runTranslation(DurationDelayClock clock, float partialTicks){
			if(clock.animation.axis.x != 0){
				modifiedValue *= entity.getAnimatedVariableValue(clock, clock.animation.axis.x, partialTicks);
			}else if(clock.animation.axis.y != 0){
				modifiedValue += entity.getAnimatedVariableValue(clock, clock.animation.axis.y, partialTicks);
			}else{
				modifiedValue = (float) (entity.getAnimatedVariableValue(clock, clock.animation.axis.z, partialTicks));
			}
		}
	}
	
	@Override
	public void doPostUpdateLogic(){
		super.doPostUpdateLogic();
		if(changesPosition()){
			//Update collision boxes to new position.
			world.beginProfiling("CollisionBoxUpdates", true);
			updateCollisionBoxes();
			world.endProfiling();
			
			//Move all entities that are touching this entity.
			if(!entityCollisionBoxes.isEmpty()){
				world.beginProfiling("MoveAlongEntities", true);
				encompassingBox.heightRadius += 1.0;
				List<IWrapperEntity> nearbyEntities = world.getEntitiesWithin(encompassingBox);
				encompassingBox.heightRadius -= 1.0;
	    		for(IWrapperEntity entity : nearbyEntities){
	    			//Only move Vanilla entities not riding things.  We don't want to move other things as we handle our inter-entity movement in each class.
	    			if(entity.getEntityRiding() == null && (!(entity instanceof IWrapperPlayer) || !((IWrapperPlayer) entity).isSpectator())){
	    				//Check each box individually.  Need to do this to know which delta to apply.
	    				BoundingBox entityBounds = entity.getBounds();
	    				entityBounds.heightRadius += 0.25;
	    				for(BoundingBox box : entityCollisionBoxes){
	        				if(entityBounds.intersects(box)){
								//If the entity is within 0.5 units of the top of the box, we can move them.
								//If not, they are just colliding and not on top of the entity and we should leave them be.
								double entityBottomDelta = box.globalCenter.y + box.heightRadius - (entityBounds.globalCenter.y - entityBounds.heightRadius + 0.25F);
								if(entityBottomDelta >= -0.5 && entityBottomDelta <= 0.5){
									//Only move the entity if it's going slow or in the delta.  Don't move if it's going fast as they might have jumped.
									Point3D entityVelocity = entity.getVelocity();
									if(entityVelocity.y < 0 || entityVelocity.y < entityBottomDelta){
										//Get how much the entity moved the collision box the entity collided with so we know how much to move the entity.
										//This lets entities "move along" with entities when touching a collision box.
										Point3D entityPositionVector = entity.getPosition().copy().subtract(position);
										Point3D startingAngles = entityPositionVector.copy().getAngles(true);
										Point3D entityPositionDelta = entityPositionVector.copy();
										entityPositionDelta.rotate(orientation).reOrigin(prevOrientation);
										Point3D entityAngleDelta = entityPositionDelta.copy().getAngles(true).subtract(startingAngles);
										
										entityPositionDelta.add(position).subtract(prevPosition);
										entityPositionDelta.subtract(entityPositionVector).add(0, entityBottomDelta, 0);
										entity.setPosition(entityPositionDelta.add(entity.getPosition()), true);
										entity.setYaw(entity.getYaw() + entityAngleDelta.y);
										entity.setBodyYaw(entity.getBodyYaw() + entityAngleDelta.y);
										break;
									}
								}
	        				}
	    				}
	    			}
	    		}
	    		world.endProfiling();
			}
		}
	}
	
	/**
   	 *  Returns a collection of BoundingBoxes that make up this entity's collision bounds.
   	 */
    public Collection<BoundingBox> getCollisionBoxes(){
    	return entityCollisionBoxes;
    }
    
    /**
   	 *  Returns a collection of BoundingBoxes that make up this entity's interaction bounds.
   	 */
    public Collection<BoundingBox> getInteractionBoxes(){
    	return interactionBoxes;
    }
	
	/**
	 *  Called to update the rider on this entity.  This gets called after the update loop,
	 *  as the entity needs to move to its new position before we can know where the
	 *  riders of said entity will be.  The calling function will assure that the rider
	 *  is non-null at this point, so null checks are not required in this function.
	 *  However, if the rider is removed, false is returned, and further processing should halt.
	 */
	public boolean updateRider(){
		//Update entity position and motion.
		if(rider.isValid()){
			rider.setPosition(position, false);
			rider.setVelocity(motion);
			return true;
		}else{
			//Remove invalid rider.
			removeRider();
			return false;
		}
	}
	
	/**
	 *  Called to set the rider for this entity.  If this isn't possible because
	 *  there is already a rider, or we shouldn't accept riders, return false.
	 *  Otherwise, return true.  Call this ONLY on the server!  Packets are sent to clients
	 *  for syncing so calling this on clients will result in Bad Stuff.
	 *  If the rider needs to face forward when they are added, set the boolean to true.
	 *  Note: this will only set them to face forwards on the tick they mount.
	 *  It won't block them from turning to a different orientation later.
	 *  
	 */
	public boolean setRider(IWrapperEntity newRider, boolean facesForwards){
		if(rider != null){
		    return false;
		}else {
		    rider = newRider;
            if(facesForwards){
                rider.setYaw(0);
                rider.setPitch(0);
            }
            if(!world.isClient()){
                rider.setRiding(this);
                InterfaceManager.packetInterface.sendToAllClients(new PacketEntityRiderChange(this, rider, facesForwards));
            }
            return true;
		}
	}
	
	/**
	 *  Called to remove the rider that is currently riding this entity.
	 */
	public void removeRider(){
	    if(!world.isClient()){
            rider.setRiding(null);
            InterfaceManager.packetInterface.sendToAllClients(new PacketEntityRiderChange(this, rider));
        }
	    rider = null;
	}
	
	/**
   	 *  Adds the instrument to the specified slot.
   	 */
    public void addInstrument(ItemInstrument instrument, int slot){
    	instruments.set(slot, instrument);
    	List<RenderableObject> renderables = new ArrayList<RenderableObject>();
    	for(JSONInstrumentComponent component : instrument.definition.components){
    		if(component.textObject != null){
    			renderables.add(null);
    		}else{
    			renderables.add(new RenderableObject("instrument", null, new ColorRGB(), FloatBuffer.allocate(6*8), false));
    		}
    		if(component.animations != null){
    			instrumentComponentSwitchboxes.put(component, new InstrumentSwitchbox(this, component));
    		}
		}
    	instrumentRenderables.set(slot, renderables);
    }
    
    /**
   	 *  Removes the instrument from the specified slot.
   	 */
    public void removeIntrument(int slot){
    	ItemInstrument removedInstrument = instruments.set(slot, null);
		if(removedInstrument != null){
			for(JSONInstrumentComponent component : removedInstrument.definition.components){
				instrumentComponentSwitchboxes.remove(component);
			}
			instrumentRenderables.set(slot, null);
		}
    }
	
	/**
	 *  Returns the owner state of the passed-in player, relative to this entity.
	 *  Takes into account player OP status and {@link #ownerUUID}, if set.
	 */
	public PlayerOwnerState getOwnerState(IWrapperPlayer player){
		boolean canPlayerEdit = player.isOP() || ownerUUID == null || player.getID().equals(ownerUUID);
		return player.isOP() ? PlayerOwnerState.ADMIN : (canPlayerEdit ? PlayerOwnerState.OWNER : PlayerOwnerState.USER);
	}
	
	/**
	 *  Called when the entity is attacked.
	 *  This should ONLY be called on the server; clients will sync via packets.
	 *  If calling this method in a loop, make sure to check if this entity is valid.
	 *  as this function may be called multiple times in a single tick for multiple damage 
	 *  applications, which means one of those may have made this entity invalid.
	 */
	public void attack(Damage damage){
		if(!damage.isWater){ 
			if(definition.collisionGroups != null){
				for(JSONCollisionGroup groupDef : definition.collisionGroups){
					Set<BoundingBox> collisionBoxes = definitionCollisionBoxes.get(groupDef);
					if(collisionBoxes.contains(damage.box)){
						if(groupDef.health != 0){
							String variableName = "collision_" + (definition.collisionGroups.indexOf(groupDef) + 1) + "_damage";
							double currentDamage = getVariable(variableName) + damage.amount;
							if(currentDamage > groupDef.health){
								double amountActuallyNeeded = damage.amount - (currentDamage - groupDef.health);
								currentDamage = groupDef.health;
								InterfaceManager.packetInterface.sendToAllClients(new PacketEntityVariableIncrement(this, variableName, amountActuallyNeeded));
							}else{
								InterfaceManager.packetInterface.sendToAllClients(new PacketEntityVariableIncrement(this, variableName, damage.amount));
							}
							setVariable(variableName, currentDamage);
							return;
						}
					}
				}
			}
			
			//Didn't hit a collision box or found one with no health defined 
			damageAmount += damage.amount;
			if(damageAmount > definition.general.health){
				double amountActuallyNeeded = damage.amount - (damageAmount - definition.general.health);
				damageAmount = definition.general.health;
				InterfaceManager.packetInterface.sendToAllClients(new PacketEntityVariableIncrement(this, DAMAGE_VARIABLE, amountActuallyNeeded));
			}else{
				InterfaceManager.packetInterface.sendToAllClients(new PacketEntityVariableIncrement(this, DAMAGE_VARIABLE, damage.amount));
			}
			setVariable(DAMAGE_VARIABLE, damageAmount);
		}
	}
	
	@Override
	public IWrapperNBT save(IWrapperNBT data){
		super.save(data);
		data.setBoolean("locked", locked);
		if(ownerUUID != null){
			data.setUUID("ownerUUID", ownerUUID);
		}
		
		if(definition.instruments != null){
			String[] instrumentsInSlots = new String[definition.instruments.size()];
			for(int i=0; i<instrumentsInSlots.length; ++i){
				ItemInstrument instrument = instruments.get(i);
				if(instrument != null){
					data.setString("instrument" + i + "_packID", instrument.definition.packID);
					data.setString("instrument" + i + "_systemName", instrument.definition.systemName);
				}
			}
		}
		return data;
	}
	
	/**
	 * Emum for easier functions for owner states.
	 */
	public static enum PlayerOwnerState{
		USER,
		OWNER,
		ADMIN;
	}
}
