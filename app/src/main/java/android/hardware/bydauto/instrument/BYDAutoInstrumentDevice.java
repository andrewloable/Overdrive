package android.hardware.bydauto.instrument;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;
import java.util.ArrayList;
import java.util.List;

public class BYDAutoInstrumentDevice extends AbsBYDAutoDevice {
    public static final int HP = 2;
    public static final int KW = 1;
    public static final int POWER_UNIT = 0;
    private static BYDAutoInstrumentDevice sInstance;
    private final List<AbsBYDAutoInstrumentListener> listeners = new ArrayList<>();

    protected BYDAutoInstrumentDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoInstrumentDevice getInstance(Context context) {
        BYDAutoInstrumentDevice bYDAutoInstrumentDevice;
        synchronized (BYDAutoInstrumentDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoInstrumentDevice(context);
            }
            bYDAutoInstrumentDevice = sInstance;
        }
        return bYDAutoInstrumentDevice;
    }

    public double getLast50KmPowerConsume() {
        return 0.0d;
    }

    public int getMileageUnit() {
        return 1;
    }

    public int getOutCarTemperature() {
        return 25;
    }

    public int getSafetyBeltStatus(int i) {
        return 0;
    }

    public int getType() {
        return 0;
    }

    public int getUnit(int i) {
        return 1;
    }

    /**
     * Gets the external charging power in KW.
     * This is the actual charging power from the charger.
     * @return Charging power in KW (positive = charging, negative = discharging)
     */
    public double getExternalChargingPower() {
        return 0.0d;
    }

    public void registerListener(AbsBYDAutoInstrumentListener absBYDAutoInstrumentListener) {
        if (absBYDAutoInstrumentListener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(absBYDAutoInstrumentListener)) {
                    this.listeners.add(absBYDAutoInstrumentListener);
                }
            }
        }
    }
}
