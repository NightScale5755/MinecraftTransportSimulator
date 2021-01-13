package minecrafttransportsimulator.jsondefs;

import minecrafttransportsimulator.baseclasses.Point3d;
import minecrafttransportsimulator.packloading.JSONParser.JSONRequired;

public class JSONParticleObject{
	@JSONRequired
	public String type;
	public String color;
	public String toColor;
	public float transparency;
	public float toTransparency;
	public float scale;
	public float toScale;
	public Point3d pos;
	@Deprecated
	public float velocity;
	public Point3d velocityVector;
	public int quantity;
	public int duration;
}
