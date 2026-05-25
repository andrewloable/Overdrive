package android.hardware.bydauto.gearbox;

import android.content.Context;
import android.hardware.bydauto.AbsBYDAutoDevice;
import java.util.ArrayList;
import java.util.List;

public class BYDAutoGearboxDevice extends AbsBYDAutoDevice {
    public static final int GEARBOX_AUTO_MODE_P = 1;
    public static final int GEARBOX_AUTO_MODE_R = 2;
    public static final int GEARBOX_AUTO_MODE_N = 3;
    public static final int GEARBOX_AUTO_MODE_D = 4;
    public static final int GEARBOX_AUTO_MODE_M = 5;
    public static final int GEARBOX_AUTO_MODE_S = 6;

    public static final int GEARBOX_BREAK_PADAL_NOT_PRESS = 0;
    public static final int GEARBOX_BREAK_PADAL_PRESS = 1;

    private static BYDAutoGearboxDevice sInstance;
    private int currentMode = 1;
    private final List<AbsBYDAutoGearboxListener> listeners = new ArrayList<>();

    protected BYDAutoGearboxDevice(Context context) {
        super(context);
    }

    public static synchronized BYDAutoGearboxDevice getInstance(Context context) {
        BYDAutoGearboxDevice bYDAutoGearboxDevice;
        synchronized (BYDAutoGearboxDevice.class) {
            if (sInstance == null) {
                sInstance = new BYDAutoGearboxDevice(context);
            }
            bYDAutoGearboxDevice = sInstance;
        }
        return bYDAutoGearboxDevice;
    }

    public int getGearboxAutoModeType() {
        return this.currentMode;
    }

    public int getBrakePedalState() {
        return 0;
    }

    public int getType() {
        return 0;
    }

    public void registerListener(AbsBYDAutoGearboxListener absBYDAutoGearboxListener) {
        if (absBYDAutoGearboxListener != null) {
            synchronized (this.listeners) {
                if (!this.listeners.contains(absBYDAutoGearboxListener)) {
                    this.listeners.add(absBYDAutoGearboxListener);
                }
            }
        }
    }
}
