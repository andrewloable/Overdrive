package android.hardware.bydauto.charging;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;
import java.util.ArrayList;
import java.util.List;

public class BYDAutoChargingDevice extends AbsBYDAutoDevice {

    // Charging gun state constants
    // NOTE: Per decompiled BYDCarController (ground truth from actual BYD APK):
    //   1=NONE (no gun), 2=AC, 3=DC, 4=AC_DC, 5=VTOL
    // The HAL returns these values directly from getChargingGunState().
    public static final int CHARGING_GUN_STATE_NONE = 1;
    public static final int CHARGING_GUN_STATE_CONNECTED_AC = 2;
    public static final int CHARGING_GUN_STATE_CONNECTED_DC = 3;
    public static final int CHARGING_GUN_STATE_CONNECTED_AC_DC = 4;
    public static final int CHARGING_GUN_STATE_CONNECTED_VTOL = 5;

    // Charging gun on/off state constants
    public static final int CHARGING_GUN_STATE_OFF = 0;
    public static final int CHARGING_GUN_STATE_ON = 1;

    // Charging type constants
    public static final int CHARGING_TYPE_DEFAULT = 0;
    public static final int CHARGING_TYPE_AC = 1;
    public static final int CHARGING_TYPE_GB_DC = 2;
    public static final int CHARGING_TYPE_GB_NON_DC = 3;
    public static final int CHARGING_TYPE_VTOG = 4;

    // Charging cap constants
    public static final int CHARGING_CAP_AC = 1;
    public static final int CHARGING_CAP_DC = 2;

    private static BYDAutoChargingDevice sInstance;
    private final List<AbsBYDAutoChargingListener> listeners = new ArrayList();

    private BYDAutoChargingDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoChargingDevice getInstance(Context context) {
        BYDAutoChargingDevice bYDAutoChargingDevice;
        synchronized (BYDAutoChargingDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoChargingDevice(context);
            }
            bYDAutoChargingDevice = sInstance;
        }
        return bYDAutoChargingDevice;
    }

    public int getBatteryManagementDeviceState() {
        return 0;
    }

    public int getCapState() {
        return 0;
    }

    public int getChargeStopCapacityState() {
        return 0;
    }

    public int getChargeStopSupportConfig() {
        return 0;
    }

    public int getChargeStopSwitchState() {
        return 0;
    }

    public int getChargerState() {
        return 0;
    }

    public int getChargingCapState(int i) {
        return 0;
    }

    public double getChargingCapacity() {
        return 0.0d;
    }

    public int getChargingGunState() {
        return 0;
    }

    public int getChargingMode() {
        return 0;
    }

    public int[] getChargingRestTime() {
        return new int[]{0, 0};
    }

    public int getSmartChargingState() {
        return 0;
    }

    public int getType() {
        return 1007; // TYPE_VERTICAL_TEXT constant value
    }

    public void registerListener(AbsBYDAutoChargingListener absBYDAutoChargingListener) {
        if (absBYDAutoChargingListener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(absBYDAutoChargingListener)) {
                    this.listeners.add(absBYDAutoChargingListener);
                }
            }
        }
    }
    
    public void unregisterListener(AbsBYDAutoChargingListener absBYDAutoChargingListener) {
        if (absBYDAutoChargingListener != null) {
            synchronized (this.listeners) {
                this.listeners.remove(absBYDAutoChargingListener);
            }
        }
    }
    
    public double getChargingPower() {
        return 0.0d;
    }

    public int setChargeStopCapacityState(int i) {
        return 0;
    }

    public int setChargeStopSwitchState(int i) {
        return 0;
    }
}
