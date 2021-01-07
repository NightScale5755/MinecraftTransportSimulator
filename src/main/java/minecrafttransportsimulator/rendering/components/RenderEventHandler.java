package minecrafttransportsimulator.rendering.components;

import java.awt.Color;

import org.lwjgl.opengl.GL11;

import minecrafttransportsimulator.baseclasses.BoundingBox;
import minecrafttransportsimulator.baseclasses.FluidTank;
import minecrafttransportsimulator.baseclasses.Point3d;
import minecrafttransportsimulator.guis.components.AGUIBase;
import minecrafttransportsimulator.guis.components.AGUIBase.TextPosition;
import minecrafttransportsimulator.guis.instances.GUIHUD;
import minecrafttransportsimulator.jsondefs.JSONAnimationDefinition;
import minecrafttransportsimulator.jsondefs.JSONCameraObject;
import minecrafttransportsimulator.mcinterface.IWrapperEntity;
import minecrafttransportsimulator.mcinterface.IWrapperPlayer;
import minecrafttransportsimulator.mcinterface.MasterLoader;
import minecrafttransportsimulator.systems.ConfigSystem;
import minecrafttransportsimulator.vehicles.main.AEntityBase;
import minecrafttransportsimulator.vehicles.main.EntityPlayerGun;
import minecrafttransportsimulator.vehicles.main.EntityVehicleF_Physics;
import minecrafttransportsimulator.vehicles.parts.APart;
import minecrafttransportsimulator.vehicles.parts.PartInteractable;
import minecrafttransportsimulator.vehicles.parts.PartSeat;

/**This class is responsible for handling events sent from the rendering interface.  These events are used to
 * do things like render the vehicle HUD, adjust the camera to the vehicle orientation, or change the
 * camera position based on states.
 *
 * @author don_bruce
 */
public class RenderEventHandler{
	private static int zoomLevel;
	private static boolean enableCustomCameras;
	private static boolean runningCustomCameras;
	private static int customCameraIndex;
	private static float currentFOV;
	private static String customCameraOverlay;
	private static AGUIBase currentGUI;
	
	/**
	 *  Resets the overlay GUI by nulling it out.  This will cause it to re-create itself next tick.
	 *  Useful if something on it has changed and you need it to re-create itself.
	 */
	public static void resetGUI(){
		currentGUI = null;
	}
	
	/**
	 *  Adjusts the camera zoom, zooming in or out depending on the flag.
	 */
	public static void changeCameraZoom(boolean zoomIn){
		if(zoomIn && zoomLevel > 0){
			zoomLevel -= 2;
		}else if(!zoomIn){
			zoomLevel += 2;
		}
	}
    
    /**
     * Adjusts roll, pitch, and zoom for camera.
     * Roll and pitch only gets updated when in first-person as we use OpenGL transforms.
     * For external rotations, we just let the entity adjust the player's pitch and yaw.
     * This is because first-person view is for direct control, while third-person is for passive control.
     * If we do any overriding logic to the camera pitch and yaw, return true.
     */
    public static boolean onCameraSetup(IWrapperPlayer player, float partialTicks){
		AEntityBase ridingEntity = player.getEntityRiding();
		EntityVehicleF_Physics vehicle = ridingEntity instanceof EntityVehicleF_Physics ? (EntityVehicleF_Physics) ridingEntity : null;
		PartSeat sittingSeat = vehicle != null ? (PartSeat) vehicle.getPartAtLocation(vehicle.locationRiderMap.inverse().get(player)) : null;
		EntityPlayerGun playerGunEntity = EntityPlayerGun.playerClientGuns.get(player.getUUID());
		
    	if(MasterLoader.clientInterface.inFirstPerson()){
    		//If we are sneaking and holding a gun, enable custom cameras.
    		if(playerGunEntity != null && playerGunEntity.gun != null && sittingSeat == null){
    			enableCustomCameras = player.isSneaking();
    			customCameraIndex = 0;
    		}
			//Do custom camera, or do normal rendering.
			if(enableCustomCameras){
		    	//Get cameras from vehicle or hand-held gun.
		    	//We check active cameras until we find one that we can use.
				runningCustomCameras = true;
		    	int camerasChecked = 0;
		    	JSONCameraObject camera = null;
		    	IAnimationProvider provider = null;
		    	
				if(vehicle != null){
					if(vehicle.definition.rendering.cameraObjects != null){
						for(JSONCameraObject testCamera : vehicle.definition.rendering.cameraObjects){
							if(isCameraActive(testCamera, vehicle, partialTicks)){
								if(camerasChecked++ == customCameraIndex){
									camera = testCamera;
									provider = vehicle;
									break;
								}
							}
						}
					}
					for(APart part : vehicle.parts){
						if(part.definition.rendering != null && part.definition.rendering.cameraObjects != null){
							for(JSONCameraObject testCamera : part.definition.rendering.cameraObjects){
								if(isCameraActive(testCamera, part, partialTicks)){
									if(camerasChecked++ == customCameraIndex){
										camera = testCamera;
										provider = part;
										break;
									}
								}
							}
						}
						if(camera != null){
							break;
						}
					}
				}else if(playerGunEntity != null && playerGunEntity.gun != null){
					if(playerGunEntity.gunItem.definition.rendering != null && playerGunEntity.gunItem.definition.rendering.cameraObjects != null){
						for(JSONCameraObject testCamera : playerGunEntity.gunItem.definition.rendering.cameraObjects){
							if(isCameraActive(testCamera, playerGunEntity, partialTicks)){
								if(camerasChecked++ == customCameraIndex){
									camera = testCamera;
									provider = playerGunEntity;
									break;
								}
							}
						}
					}
				}
				
				//If we found a camera, use it.  If not, turn off custom cameras and go back to first-person mode.
				if(camera != null){
					//Need to orient our custom camera.  Custom cameras do viewpoint rendering.
					//This means everything happens in the opposite order of model creation.
					//As this is the case, all rotations are applied as ZXY, not YXZ.
					//This also means all signs are inverted for all operations.
					//Finally, it means that rotation operations do NOT affect the matrix origin.
					
					//Set current overlay for future calls.
					customCameraOverlay = camera.overlay != null ? camera.overlay + ".png" : null;
        			
					//Set variables for camera position and rotation.
					Point3d cameraPosition = new Point3d(0, 0, 0);
					Point3d cameraRotation = new Point3d(0, 0, 0);
					
					//Apply transforms.
					//These happen in-order to ensure proper rendering sequencing.
					if(camera.animations != null){
						boolean inhibitAnimations = false;
        				for(JSONAnimationDefinition animation : camera.animations){
        					double variableValue= provider.getAnimationSystem().getAnimatedVariableValue(provider, animation, 0, null, partialTicks);
        					if(animation.animationType.equals("inhibitor")){
        						if(variableValue >= animation.clampMin && variableValue <= animation.clampMax){
        							inhibitAnimations = true;
        						}
        					}else if(animation.animationType.equals("activator")){
        						if(variableValue >= animation.clampMin && variableValue <= animation.clampMax){
        							inhibitAnimations = false;
        						}
        					}else if(!inhibitAnimations){
        						if(animation.animationType.equals("rotation")){
            						if(variableValue != 0){
            							Point3d rotationAmount = animation.axis.copy().normalize().multiply(variableValue);
            							Point3d rotationOffset = camera.pos.copy().subtract(animation.centerPoint);
            							if(!rotationOffset.isZero()){
            								cameraPosition.subtract(rotationOffset).add(rotationOffset.rotateFine(rotationAmount));
            							}
            							cameraRotation.add(rotationAmount);
            						}
            					}else if(animation.animationType.equals("translation")){
            						if(variableValue != 0){
            							Point3d translationAmount = animation.axis.copy().normalize().multiply(variableValue).rotateFine(cameraRotation);
            							cameraPosition.add(translationAmount);
            						}
            					}
        					}
        				}
        			}
					
    				//Now that the transformed camera is ready, add the camera offset position and rotation.
    				//This may be for a part, in which case we need to offset by the part's position/rotation as well.
					cameraPosition.add(camera.pos);
    				if(camera.rot != null){
    					cameraRotation.add(camera.rot);
    				}
    				if(provider instanceof APart){
    					APart part = (APart) provider;
	    				if(part != null){
	    					cameraPosition.rotateFine(part.totalRotation).add(part.totalOffset);
						}
    				}
    				
    				//Camera position is set.
    				//If we are on a vehicle, do vehicle alignment.  Otherwise, just align to the entity.
    				AEntityBase providerEntity = vehicle != null ? vehicle : playerGunEntity;
    				Point3d providerSmoothedRotation = providerEntity.prevAngles.copy().add(providerEntity.angles.copy().subtract(providerEntity.prevAngles).multiply(partialTicks));
					cameraPosition.rotateFine(providerSmoothedRotation);
					cameraRotation.add(providerSmoothedRotation);
    				if(vehicle != null){
						//Camera is positioned and rotated to match the entity.  Do OpenGL transforms to set it.
						//Get the distance from the vehicle's center point to the rendered player to get a 0,0,0 starting point.
	        			//Need to take into account the player's eye height.  This is where the camera is, but not where the player is positioned.
						Point3d vehiclePositionDelta = vehicle.position.copy().subtract(vehicle.prevPosition).multiply(partialTicks).add(vehicle.prevPosition);
						vehiclePositionDelta.subtract(player.getRenderedPosition(partialTicks).add(0, player.getEyeHeight(), 0));
						cameraPosition.add(vehiclePositionDelta);
    				}
					
					//Rotate by 180 to get the forwards-facing orientation; MC does everything backwards.
            		GL11.glRotated(180, 0, 1, 0);
					
					//Now apply our actual offsets.
					GL11.glRotated(-cameraRotation.z, 0, 0, 1);
					GL11.glRotated(-cameraRotation.x, 1, 0, 0);
        			GL11.glRotated(-cameraRotation.y, 0, 1, 0);
        			GL11.glTranslated(-cameraPosition.x, -cameraPosition.y, -cameraPosition.z);
            		
            		//If the camera has an FOV override, apply it.
            		if(camera.fovOverride != 0){
            			if(currentFOV == 0){
            				currentFOV = MasterLoader.clientInterface.getFOV();
            			}
            			MasterLoader.clientInterface.setFOV(camera.fovOverride);
            		}
        			
        			//Return true to signal that we overrode the camera movement.
        			return true;
				}
			}else if(sittingSeat != null){
            	//Get yaw delta between entity and player from-180 to 180.
            	double playerYawDelta = (360 + (vehicle.angles.y - player.getHeadYaw())%360)%360;
            	if(playerYawDelta > 180){
            		playerYawDelta-=360;
            	}
            	
            	//Get the angles from -180 to 180 for use by the component system for calculating roll and pitch angles.
            	double pitchAngle = vehicle.prevAngles.x + (vehicle.angles.x - vehicle.prevAngles.x)*partialTicks;
            	double rollAngle = vehicle.prevAngles.z + (vehicle.angles.z - vehicle.prevAngles.z)*partialTicks;
            	while(pitchAngle > 180){pitchAngle -= 360;}
    			while(pitchAngle < -180){pitchAngle += 360;}
    			while(rollAngle > 180){rollAngle -= 360;}
    			while(rollAngle < -180){rollAngle += 360;}
            	
            	//Get the component of the pitch and roll that should be applied based on the yaw delta.
            	//This is based on where the player is looking.  If the player is looking straight forwards, then we want 100% of the
            	//pitch to be applied as pitch.  But, if they are looking to the side, then we need to apply that as roll, not pitch.
            	double rollRollComponent = Math.cos(Math.toRadians(playerYawDelta))*rollAngle;
            	double pitchRollComponent = -Math.sin(Math.toRadians(playerYawDelta))*pitchAngle;
            	GL11.glRotated(rollRollComponent + pitchRollComponent, 0, 0, 1);
			}
			
			//We wern't running a custom camera.  Set runninv variable to false.
			enableCustomCameras = false;
			runningCustomCameras = false;
    	}else if(MasterLoader.clientInterface.inThirdPerson()){
    		//If we were running a custom camera, and hit the switch key, increment our camera index.
    		//We then go back to first-person to render the proper camera.
    		//If we weren't running a custom camera, try running one.  This will become active when we
    		//go back into first-person mode.  This only has an effect if we are in a vehicle.
    		if(runningCustomCameras){
    			++customCameraIndex;
    			MasterLoader.clientInterface.toggleFirstPerson();
    		}else if(vehicle != null){
    			enableCustomCameras = true;
        		customCameraIndex = 0;
    		}
    		if(sittingSeat != null){
    			GL11.glTranslated(-sittingSeat.totalOffset.x, 0F, -zoomLevel);
    		}
        }else{
        	//Assuming inverted third-person mode.
        	//If we get here, and don't have any custom cameras, stay here.
        	//If we do have custom cameras, use them instead.
        	if(vehicle != null){
	        	if(vehicle.definition.rendering.cameraObjects != null){
	        		MasterLoader.clientInterface.toggleFirstPerson();
				}else{
					for(APart part : vehicle.parts){
						if(part.definition.rendering != null && part.definition.rendering.cameraObjects != null){
							MasterLoader.clientInterface.toggleFirstPerson();
							break;
						}
					}
				}
	        	if(sittingSeat != null){
	        		GL11.glTranslated(-sittingSeat.totalOffset.x, 0F, zoomLevel);
	        	}
        	}else if(playerGunEntity != null && playerGunEntity.gun != null && player.isSneaking()){
        		if(playerGunEntity.gunItem.definition.rendering != null && playerGunEntity.gunItem.definition.rendering.cameraObjects != null){
        			MasterLoader.clientInterface.toggleFirstPerson();
        		}
        	}
		}
		customCameraOverlay = null;
		if(currentFOV != 0){
			MasterLoader.clientInterface.setFOV(currentFOV);
			currentFOV = 0; 
		}
		return false;
    }
    
    private static boolean isCameraActive(JSONCameraObject camera, IAnimationProvider provider, float partialTicks){
		if(camera.animations != null){
			for(JSONAnimationDefinition animation : camera.animations){
				if(animation.animationType.equals("visibility")){
					double value = provider.getAnimationSystem().getAnimatedVariableValue(provider, animation, 0, null, partialTicks);
					if(value < animation.clampMin || value > animation.clampMax){
						return false;
					}
				}
			}
		}
		return true;
    }
    
    /**
     * Returns true if the HUD components such as the hotbar and cross-hairs should be disabled.
     * This should be done for custom cameras with overlays.
     */
    public static boolean disableHUDComponents(){
    	return customCameraOverlay != null;
    }
    
    /**
     * Renders an overlay GUI, or other overlay components like the fluid in a tank if we are mousing-over a vehicle.
     * Also responsible for rendering overlays on custom cameras.  If we need to render a GUI,
     * it should be returned.  Otherwise, return null.
     */
    public static AGUIBase onOverlayRender(int screenWidth, int screenHeight, float partialTicks, AEntityBase mousedOverEntity, Point3d mousedOverPoint){
    	IWrapperPlayer player = MasterLoader.clientInterface.getClientPlayer();
    	AEntityBase ridingEntity = player.getEntityRiding();
    	if(MasterLoader.clientInterface.inFirstPerson() && ridingEntity == null){
			if(mousedOverEntity instanceof EntityVehicleF_Physics){
				EntityVehicleF_Physics vehicle = (EntityVehicleF_Physics) mousedOverEntity;
				for(BoundingBox box : vehicle.interactionBoxes){
					if(box.isPointInside(mousedOverPoint)){
						APart part = vehicle.getPartAtLocation(box.localCenter);
						if(part instanceof PartInteractable){
							FluidTank tank = ((PartInteractable) part).tank;
							if(tank != null){
								String tankText = tank.getFluid().isEmpty() ? "EMPTY" : tank.getFluid().toUpperCase() + " : " + tank.getFluidLevel() + "/" + tank.getMaxLevel();
								MasterLoader.guiInterface.drawBasicText(tankText, screenWidth/2 + 4, screenHeight/2, Color.WHITE, TextPosition.LEFT_ALIGNED, 0);
								break;
							}
						}
					}
				}
			}
			currentGUI = null;
		}
    	if(customCameraOverlay != null){
			MasterLoader.renderInterface.bindTexture(customCameraOverlay);
			MasterLoader.renderInterface.setBlendState(true, false);
			MasterLoader.guiInterface.renderSheetTexture(0, 0, screenWidth, screenHeight, 0.0F, 0.0F, 1.0F, 1.0F, 1, 1);
			MasterLoader.renderInterface.setBlendState(false, false);
			currentGUI = null;
		}else if(MasterLoader.clientInterface.inFirstPerson() ? ConfigSystem.configObject.clientRendering.renderHUD_1P.value : ConfigSystem.configObject.clientRendering.renderHUD_3P.value){
			if(ridingEntity instanceof EntityVehicleF_Physics){
				for(IWrapperEntity rider : ridingEntity.locationRiderMap.values()){
					if(rider.equals(player)){
						PartSeat seat = (PartSeat) ((EntityVehicleF_Physics) ridingEntity).getPartAtLocation(ridingEntity.locationRiderMap.inverse().get(rider));
						//If the seat is controlling a gun, render a text line for it.
						if(seat.activeGun != null && !MasterLoader.clientInterface.isChatOpen()){
							String gunNumberText = seat.activeGun.definition.gun.fireSolo ? " [" + (seat.gunIndex + 1) + "]" : "";
							MasterLoader.guiInterface.drawBasicText("Active Gun:", screenWidth, 0, Color.WHITE, TextPosition.RIGHT_ALIGNED, 0);
							MasterLoader.guiInterface.drawBasicText(seat.activeGun.getItemName() + gunNumberText, screenWidth, 8, Color.WHITE, TextPosition.RIGHT_ALIGNED, 0);
						}
						
						//If the seat is a controller, render the HUD.
						if(seat.vehicleDefinition.isController){
							if(currentGUI == null){
								currentGUI = new GUIHUD((EntityVehicleF_Physics) ridingEntity);
							}
							return currentGUI;
						}
					}
				}
			}
			currentGUI = null;
		}else{
			currentGUI = null;
		}
    	
    	//Return the current GUI to render as an overlay.
    	return currentGUI;
    }
}
