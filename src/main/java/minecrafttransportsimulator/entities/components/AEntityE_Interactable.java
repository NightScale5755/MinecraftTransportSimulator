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

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

import minecrafttransportsimulator.baseclasses.AnimationSwitchbox;
import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.ColorRGB;
import minecrafttransportsimulator.baseclasses.Damage;
import minecrafttransportsimulator.baseclasses.Point3D;
import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.items.instances.ItemInstrument;
import minecrafttransportsimulator.jsondefs.AJSONInteractableEntity;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;
import minecrafttransportsimulator.jsondefs.JSONCollisionBox;
import minecrafttransportsimulator.jsondefs.JSONCollisionGroup;
import minecrafttransportsimulator.jsondefs.JSONInstrument.JSONInstrumentComponent;
import minecrafttransportsimulator.jsondefs.JSONInstrumentDefinition;
import minecrafttransportsimulator.mcinterface.AWrapperWorld;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketEntityRiderChange;
import minecrafttransportsimulator.packets.instances.PacketEntityVariableIncrement;
import minecrafttransportsimulator.packets.instances.PacketPlayerChatMessage;
import minecrafttransportsimulator.packloading.PackParser;
import minecrafttransportsimulator.rendering.RenderInstrument;
import minecrafttransportsimulator.rendering.RenderableObject;
import minecrafttransportsimulator.rendering.RenderInstrument.InstrumentSwitchbox;
import minecrafttransportsimulator.systems.ConfigSystem;

/**Base entity class containing riders and their positions on this entity.  Used for
 * entities that need to keep track of riders and their locations.  This also contains
 * various collision box lists for collision, as riders cannot interact and start riding
 * entities without collision boxes to click.
 * 
 * @author don_bruce
 */
public abstract class AEntityE_Interactable<JSONDefinition extends AJSONInteractableEntity> extends AEntityD_Definable<JSONDefinition>{
    /**Static helper matrix for transforming instrument positions.**/
    private static final TransformationMatrix instrumentTransform = new TransformationMatrix();
    private static final RotationMatrix INSTRUMENT_ROTATION_INVERSION = new RotationMatrix().setToAxisAngle(0, 1, 0, 180);
    
	/**List of boxes generated from JSON.  These are stored here as objects to prevent the need
	 * to create them every time we want to parse out hitboxes.  This allows parsing them into sub-sets,
	 * and querying the entire list to find the hitbox for a given group and index.**/
	public final List<List<BoundingBox>> definitionCollisionBoxes = new ArrayList<List<BoundingBox>>();
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
	
	/**List of bounding boxes that should be used for bullet collisions with this entity.
	 * These can't be clicked by players, and can't be collided with.**/
	public final Set<BoundingBox> bulletCollisionBoxes = new HashSet<BoundingBox>();
	
	/**Box that encompasses all boxes on this entity.  This can be used as a pre-check for collision operations
	 * to check a single large box rather than multiple small ones to save processing power.**/
	public final BoundingBox encompassingBox = new BoundingBox(new Point3D(), new Point3D(), 0, 0, 0, false);
	
	/**Set of entities that this entity collided with this tick.  Any entity that is in this set 
	 * should NOT do collision checks with this entity, or infinite loops will occur.
	 * This set should be cleared after all collisions have been checked.**/
	public final Set<AEntityE_Interactable<?>> collidedEntities = new HashSet<AEntityE_Interactable<?>>();
	
	/**List of all possible locations for riders on this entity.  For the actual riders in these positions,
	 * see the map.  This list is only used to allow for querying of valid locations for placing riders.
	 * This should be populated prior to trying to load riders, so ideally this will be populated during construction.
	 * Note that these values are shared as keys in the rider map, so if you change them, you will no longer have
	 * hash equality in the keys.  If you need to interface with the map with a new Point3d object, you should do equality
	 * checks on this list to find the "same" point and use that in map operations to ensure hash-matching of the map.
	 **/
	public final Set<Point3D> ridableLocations = new HashSet<Point3D>();
	
	/**List of locations where rider were last save.  This is used to re-populate riders on reloads.
	 * It can be assumed that riders will be re-added in the same order the location list was saved.
	 **/
	public final List<Point3D> savedRiderLocations = new ArrayList<Point3D>();
	
	/**Maps relative position locations to riders riding at those positions.  Only one rider
	 * may be present per position.  Positions should be modified via mutable modification to
	 * avoid modifying this map.  The only modifications should be done when a rider is 
	 * mounting/dismounting this entity and we don't want to track them anymore.
	 * While you are free to read this map, all modifications should be through the method calls in this class.
	 **/
	public final BiMap<Point3D, IWrapperEntity> locationRiderMap = HashBiMap.create();
	
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
		//Load saved rider positions.  We don't have riders here yet (as those get created later), 
		//so just make the locations for the moment so they are ready when riders are created.
		this.savedRiderLocations.addAll(data.getPoint3ds("savedRiderLocations"));
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
								ItemInstrument instrument = PackParser.getItem(instrumentPackID, instrumentSystemName);
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
						ItemInstrument instrument = PackParser.getItem(instrumentPackID, instrumentSystemName);
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
				List<BoundingBox> boxes = new ArrayList<BoundingBox>();
				for(JSONCollisionBox boxDef : groupDef.collisions){
					boxes.add(new BoundingBox(boxDef, groupDef));
				}
				definitionCollisionBoxes.add(boxes);
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
	public double getMass(){
		return 100*locationRiderMap.values().size();
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
    	bulletCollisionBoxes.clear();
    	
    	if(definition.collisionGroups != null){
			for(int i=0; i<definition.collisionGroups.size(); ++i){
				JSONCollisionGroup groupDef = definition.collisionGroups.get(i);
				List<BoundingBox> collisionBoxes = definitionCollisionBoxes.get(i);
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
					if(groupDef.isForBullets) {
						bulletCollisionBoxes.addAll(collisionBoxes);
					}else {
						if(!groupDef.isInterior && !ConfigSystem.settings.general.noclipVehicles.value){
							blockCollisionBoxes.addAll(collisionBoxes);
						}
						entityCollisionBoxes.addAll(collisionBoxes);	
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
    	for(BoundingBox box : bulletCollisionBoxes){
    		encompassingBox.widthRadius = (float) Math.max(encompassingBox.widthRadius, Math.abs(box.globalCenter.x - position.x + box.widthRadius));
    		encompassingBox.heightRadius = (float) Math.max(encompassingBox.heightRadius, Math.abs(box.globalCenter.y - position.y + box.heightRadius));
    		encompassingBox.depthRadius = (float) Math.max(encompassingBox.depthRadius, Math.abs(box.globalCenter.z - position.z + box.depthRadius));
    	}
    	encompassingBox.updateToEntity(this, null);
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
	 *  Called to update the passed-in rider.  This gets called after the update loop,
	 *  as the entity needs to move to its new position before we can know where the
	 *  riders of said entity will be.
	 */
	public void updateRider(IWrapperEntity rider){
		//Update entity position and motion.
		if(rider.isValid()){
			rider.setPosition(locationRiderMap.inverse().get(rider), false);
			rider.setVelocity(motion);
		}else{
			//Remove invalid rider.
			removeRider(rider);
		}
	}
	
	/**
	 *  Called to add a rider to this entity.  Passed-in point is the point they
	 *  should try to ride.  If this isn't possible, return false.  Otherwise,
	 *  return true.  Call this ONLY on the server!  Packets are sent to clients
	 *  for syncing so calling this on clients will result in Bad Stuff.
	 *  If we are re-loading a rider from saved data, pass-in null as the position
	 *  
	 */
	public boolean addRider(IWrapperEntity rider, Point3D riderLocation){
		if(riderLocation == null){
			if(savedRiderLocations.isEmpty()){
				return false;
			}else{
				riderLocation = savedRiderLocations.get(0);
			}
		}
		
		//Need to find the actual point reference for this to ensure hash equality.
		for(Point3D location : ridableLocations){
			if(riderLocation.equals(location)){
				riderLocation = location;
				break;
			}
		}
		
		//Remove the existing location, if we have one.
		savedRiderLocations.remove(riderLocation);
		if(locationRiderMap.containsKey(riderLocation)){
			//We already have a rider in this location.
			return false;
		}else{
			//If this rider wasn't riding this vehicle before, adjust their yaw to 0.
			//This ensures their orientation will be aligned with the entity when first mounting.
			//If we are riding this entity, clear out the location before we change it.
			if(!locationRiderMap.containsValue(rider)){
				rider.setYaw(0);
			}else{
				locationRiderMap.inverse().remove(rider);
			}
			
			//Add rider to map, and send out packet if required.
			locationRiderMap.put(riderLocation, rider);
			if(!world.isClient()){
				rider.setRiding(this);
				InterfaceManager.packetInterface.sendToAllClients(new PacketEntityRiderChange(this, rider, riderLocation));
			}
			return true;
		}
	}
	
	/**
	 *  Called to remove the passed-in rider from this entity.
	 *  Passed-in iterator is optional, but MUST be included if this is called inside a loop
	 *  that's iterating over {@link #ridersToLocations} or you will get a CME!
	 */
	public void removeRider(IWrapperEntity rider){
		if(locationRiderMap.containsValue(rider)){
			locationRiderMap.inverse().remove(rider);
			if(!world.isClient()){
				rider.setRiding(null);
				InterfaceManager.packetInterface.sendToAllClients(new PacketEntityRiderChange(this, rider, null));
			}
		}
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
    public void renderBoundingBoxes(TransformationMatrix transform){
        for(BoundingBox box : interactionBoxes){
            box.renderWireframe(this, transform, null, null);
        }
        for(BoundingBox box : bulletCollisionBoxes){
            box.renderWireframe(this, transform, null, null);
        }
    }
    
    @Override
    protected void renderModel(TransformationMatrix transform, boolean blendingEnabled, float partialTicks){
        super.renderModel(transform, blendingEnabled, partialTicks);
        
        //Renders all instruments on the entity.  Uses the instrument's render code.
        //We only apply the appropriate translation and rotation.
        //Normalization is required here, as otherwise the normals get scaled with the
        //scaling operations, and shading gets applied funny.
        if(definition.instruments != null){
            world.beginProfiling("Instruments", false);
            for(int i=0; i<definition.instruments.size(); ++i){
                ItemInstrument instrument = instruments.get(i);
                if(instrument != null){
                    JSONInstrumentDefinition packInstrument = definition.instruments.get(i);
                    
                    //Translate and rotate to standard position.
                    //Note that instruments with rotation of Y=0 face backwards, which is opposite of normal rendering.
                    //To compensate, we rotate them 180 here.
                    instrumentTransform.set(transform);
                    instrumentTransform.applyTranslation(packInstrument.pos);
                    instrumentTransform.applyRotation(packInstrument.rot);
                    instrumentTransform.applyRotation(INSTRUMENT_ROTATION_INVERSION);
                    
                    //Do transforms if required and render if allowed.
                    AnimationSwitchbox switchbox = instrumentSlotSwitchboxes.get(packInstrument);
                    if(switchbox == null || switchbox.runSwitchbox(partialTicks, false)){
                        if(switchbox != null){
                            instrumentTransform.multiply(switchbox.netMatrix);
                        }
                        //Instruments render with 1 unit being 1 pixel, not 1 block, so scale by 1/16.
                        instrumentTransform.applyScaling(1/16F, 1/16F, 1/16F);
                        RenderInstrument.drawInstrument(this, instrumentTransform, i, false, blendingEnabled, partialTicks);
                    }
                }
            }
            world.endProfiling();
        }
    }
	
	@Override
	public IWrapperNBT save(IWrapperNBT data){
		super.save(data);
		data.setPoint3ds("savedRiderLocations", locationRiderMap.keySet());
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
