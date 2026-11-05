package android.hardware;

/** Public SDK event fields, populated by the fake HAL rather than reflected into a runtime object. */
public final class SensorEvent {
    public Sensor sensor;
    public final float[] values;
    public long timestamp;
    public int accuracy;
    public SensorEvent(Sensor sensor, float raw, long timestamp) {
        this.sensor = sensor; this.values = new float[] { raw }; this.timestamp = timestamp;
    }
}
