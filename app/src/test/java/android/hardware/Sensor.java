package android.hardware;

/** Host metadata for the same Sensor API consumed by SensorReporter. */
public final class Sensor {
    public static final int TYPE_LIGHT = 5, TYPE_PROXIMITY = 8;
    public static final int TYPE_AMBIENT_TEMPERATURE = 13, TYPE_RELATIVE_HUMIDITY = 12;
    public static final int REPORTING_MODE_CONTINUOUS = 0, REPORTING_MODE_ON_CHANGE = 1;
    public static final int REPORTING_MODE_ONE_SHOT = 2, REPORTING_MODE_SPECIAL_TRIGGER = 3;
    private int type;
    private int reportingMode;
    public Sensor(int type, int reportingMode) { this.type = type; this.reportingMode = reportingMode; }
    public int getType() { return type; }
    public int getReportingMode() { return reportingMode; }
    public float getMaximumRange() { return 1; }
    public float getResolution() { return 1; }
    public String getVendor() { return "Test sensor"; }
    public String getName() { return "Proximity"; }
    public String getStringType() { return "android.sensor.proximity"; }
    public int getVersion() { return 1; }
}
