package minecrafttransportsimulator.entities.instances;

import minecrafttransportsimulator.baseclasses.RotationMatrix;
import minecrafttransportsimulator.baseclasses.TransformationMatrix;
import minecrafttransportsimulator.entities.components.AEntityF_Multipart;
import minecrafttransportsimulator.items.components.AItemPart;
import minecrafttransportsimulator.items.instances.ItemPartGun;
import minecrafttransportsimulator.jsondefs.JSONConfigLanguage;
import minecrafttransportsimulator.jsondefs.JSONConfigLanguage.LanguageEntry;
import minecrafttransportsimulator.jsondefs.JSONPartDefinition;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperNBT;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.InterfaceManager;
import minecrafttransportsimulator.packets.instances.PacketPartSeat;
import minecrafttransportsimulator.packets.instances.PacketPlayerChatMessage;
import minecrafttransportsimulator.packloading.PackParser;

public final class PartSeat extends APart{
	public boolean canControlGuns;
	public ItemPartGun activeGun;
	public int gunIndex;
	
	public PartSeat(AEntityF_Multipart<?> entityOn, IWrapperPlayer placingPlayer, JSONPartDefinition placementDefinition, IWrapperNBT data, APart parentPart){
		super(entityOn, placingPlayer, placementDefinition, data, parentPart);
		this.activeGun = PackParser.getItem(data.getString("activeGunPackID"), data.getString("activeGunSystemName"), data.getString("activeGunSubName"));
	}
	
	@Override
	public boolean interact(IWrapperPlayer player){
		//See if we can interact with the seats of this vehicle.
		//This can happen if the vehicle is not locked, or we're already inside a locked vehicle.
		if(isActive){
			if(!entityOn.locked || entityOn.equals(player.getEntityRiding())){
				IWrapperEntity riderForSeat = entityOn.locationRiderMap.get(placementOffset);
				if(riderForSeat != null){
					//We already have a rider for this seat.  If it's not us, mark the seat as taken.
					//If it's an entity that can be leashed, dismount the entity and leash it.
					if(riderForSeat instanceof IWrapperPlayer){
						if(!player.equals(riderForSeat)){
							player.sendPacket(new PacketPlayerChatMessage(player, JSONConfigLanguage.INTERACT_VEHICLE_SEATTAKEN));
						}
					}else if(!riderForSeat.leashTo(player)){
						//Can't leash up this entity, so mark the seat as taken.
						player.sendPacket(new PacketPlayerChatMessage(player, JSONConfigLanguage.INTERACT_VEHICLE_SEATTAKEN));
					}
				}else{
					//Seat is free.  Either mount this seat, or if we have a leashed animal, set it in that seat.
					IWrapperEntity leashedEntity = player.getLeashedEntity();
					if(leashedEntity != null){
						entityOn.addRider(leashedEntity, placementOffset);
					}else{
						//Didn't find an animal.  Just mount the player.
						//Don't mount them if they are sneaking, however.  This will confuse MC.
						if(!player.isSneaking()){
							//Check if the rider is riding something before adding them.
							//If they aren't riding us our the entity we are on, we need to remove them.
							if(player.getEntityRiding() != null && !entityOn.equals(player.getEntityRiding())){
								player.getEntityRiding().removeRider(player);
							}
							entityOn.addRider(player, placementOffset);
							//If this seat can control a gun, and isn't controlling one, set it now.
							//This prevents the need to select a gun when initially mounting.
							//Only do this if we don't allow for no gun selection.
							//If we do have an active gun, validate that it's still correct.
							if(activeGun == null){
								if(!placementDefinition.canDisableGun){
									setNextActiveGun();
									InterfaceManager.packetInterface.sendToAllClients(new PacketPartSeat(this));
								}
							}else{
								for(AItemPart partItem : entityOn.partsByItem.keySet()){
									if(partItem.definition.gun != null){
										for(APart part : entityOn.partsByItem.get(partItem)){
											if(player.equals(((PartGun) part).getGunController())){
												if(partItem.equals(activeGun)){
													return true;
												}
											}
										}
									}
								}
								
								//Invalid active gun detected.  Select a new one.
								activeGun = null;
								setNextActiveGun();
								InterfaceManager.packetInterface.sendToAllClients(new PacketPartSeat(this));
							}
						}
					}
				}
			}else{
				player.sendPacket(new PacketPlayerChatMessage(player, JSONConfigLanguage.INTERACT_VEHICLE_LOCKED));
			}
		}
		return true;
    }
	
	@Override
	public LanguageEntry checkForRemoval(){
		if(entityOn.locationRiderMap.get(placementOffset) != null){
			return JSONConfigLanguage.INTERACT_VEHICLE_SEATTAKEN;
		}else{
			return super.checkForRemoval();
		}
	}
	
	/**
	 *  Like {@link #getInterpolatedOrientation(TransformationMatrix, double)}, just for
	 *  the rider.  This is to allow for the fact the rider won't turn in the
	 *  seat when the seat turns via animations: only their rendered body will rotate.
	 *  In a nutshell, this get's the riders orientation assuming a non-rotated seat.
	 */
	public void getRiderInterpolatedOrientation(RotationMatrix store, double partialTicks){
		store.interploate(prevZeroReferenceOrientation, zeroReferenceOrientation, partialTicks);
	}
	
	/**
	 * Sets the next active gun for this seat.  Active guns are queried by checking guns to
	 * see if this rider can control them.  If so, then the active gun is set to that gun type.
	 */
	public void setNextActiveGun(){
		//If we don't have an active gun, just get the next possible unit.
		if(activeGun == null){
			IWrapperEntity rider = entityOn.locationRiderMap.get(placementOffset);
			for(AItemPart partItem : entityOn.partsByItem.keySet()){
				if(partItem instanceof ItemPartGun){
					for(APart part : entityOn.partsByItem.get(partItem)){
						if(rider.equals(((PartGun) part).getGunController())){
							activeGun = (ItemPartGun) partItem;
							gunIndex = 0;
							return;
						}
					}
				}
			}
		}else{
			//If we didn't find an active gun, try to get another one.
			//This will be our first gun, unless we had an active gun and we can disable our gun.
			//In this case, we will just set our active gun to null.
			activeGun = getNextActiveGun();
		}
	}
	
	/**
	 * Helper method to get the next active gun in the gun listings.
	 */
	private ItemPartGun getNextActiveGun(){
		IWrapperEntity rider = entityOn.locationRiderMap.get(placementOffset);
		boolean pastActiveGun = false;
		ItemPartGun firstPossibleGun = null;
		
		//Iterate over all the gun types, attempting to get the type after our selected type.
		for(AItemPart partItem : entityOn.partsByItem.keySet()){
			if(partItem instanceof ItemPartGun){
				for(APart part : entityOn.partsByItem.get(partItem)){
					
					//Can the player control this gun, or is it for another seat?
					if(rider.equals(((PartGun) part).getGunController())){
						//If we already found our active gun in our gun list, we use the next entry as our next gun.
						if(pastActiveGun){
							return (ItemPartGun) partItem;
						}else{
							//Add the first possible gun in case we go all the way around.
							if(firstPossibleGun == null){
								firstPossibleGun = (ItemPartGun) partItem;
							}
							//If the gun type is the same as the active gun, check if it's set to fireSolo.
							//If we, we didn't group it and need to go to the next active gun with that type.
							if(partItem.equals(activeGun)){
								if(part.definition.gun.fireSolo){
									if(entityOn.partsByItem.get(partItem).size() <= ++gunIndex){
										gunIndex = 0;
										pastActiveGun = true;
									}else{
										return (ItemPartGun) partItem;
									}
								}else{
									pastActiveGun = true;
								}
							}
							break;
						}
					}
				}
			}
		}
		
		//Got down here.  Either we don't have a gun, or we need the first.
		//If our current gun is active, and we have the first, and we can disable guns,
		//return null.  This will make the guns inactive this cycle.
		return placementDefinition.canDisableGun && activeGun != null ? null : firstPossibleGun;
	}
	
	@Override
	public void update(){
		super.update();
		if(!canControlGuns && (activeGun != null || placementDefinition.canDisableGun)){
			canControlGuns = true;
		}
	}
	
	@Override
	public void remove(){
		super.remove();
		IWrapperEntity rider = entityOn.locationRiderMap.get(placementOffset);
		if(rider != null){
			entityOn.removeRider(rider);
		}
	}
	
	@Override
	public double getRawVariableValue(String variable, float partialTicks){
		double value = super.getRawVariableValue(variable, partialTicks);
		if(!Double.isNaN(value)){
			return value;
		}
		
		IWrapperEntity riderForSeat = entityOn.locationRiderMap.get(placementOffset);
		switch(variable){
			case("seat_occupied"): return riderForSeat != null ? 1 : 0;
			case("seat_occupied_client"): return InterfaceManager.clientInterface.getClientPlayer().equals(riderForSeat) ? 1 : 0;
			case("seat_rider_yaw"): return riderForSeat != null ? riderForSeat.getYaw() : 0;
			case("seat_rider_pitch"): return riderForSeat != null ? riderForSeat.getPitch() : 0;
		}
		
		return Double.NaN;
	}
	
	@Override
	public IWrapperNBT save(IWrapperNBT data){
		super.save(data);
		if(activeGun != null){
			data.setString("activeGunPackID", activeGun.definition.packID);
			data.setString("activeGunSystemName", activeGun.definition.systemName);
			data.setString("activeGunSubName", activeGun.subName);
		}
		return data;
	}
}
