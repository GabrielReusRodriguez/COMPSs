package integratedtoolkit.types;

public class CacheLevelConfig {

	private long size = (long)0.0d; //In MB
	private String location="";
	private float speedWeight = 1.0f;
	private float powerWeight = 1.0f;

	
	public CacheLevelConfig(long size, String location, float speedWeight,
			float powerWeight) {
		super();
		this.size = size;
		this.location = location;
		this.speedWeight = speedWeight;
		this.powerWeight = powerWeight;
	}
	
	public long getSize() {
		return size;
	}
	public void setSize(long size) {
		this.size = size;
	}
	public String getLocation() {
		return location;
	}
	public void setLocation(String location) {
		this.location = location;
	}
	public float getSpeedWeight() {
		return speedWeight;
	}
	public void setSpeedWeight(float speedWeight) {
		this.speedWeight = speedWeight;
	}
	public float getPowerWeight() {
		return powerWeight;
	}
	public void setPowerWeight(float powerWeight) {
		this.powerWeight = powerWeight;
	}
	
	
}
