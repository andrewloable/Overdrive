package net.bladewatch.app.daemon.proxy;

/**
 * Auto-generated encrypted string constants.
 * DO NOT EDIT - regenerate with: python generate_safe_enc.py
 * 
 * Source: secrets.json
 * Decryption: Safe.s() (AES-256-CBC, pure Java)
 */
public final class Enc {
    private Enc() {} // No instantiation

    // ==================== PATHS ====================

    /** /data/system/sentry_daemon.log */
    public static final String SENTRY_LOG_SYSTEM = Safe.s("9tdDgaIWuXyXxqP8qmKWMPvAEj729chJmgA4XiF9VOo=");

    /** /data/local/tmp/sentry_daemon.log */
    public static final String SENTRY_LOG_TMP = Safe.s("ZHx6IP38aGV/Q7iMCCcxz9TTr71BkwVSU1UO8CyXRy7yB1SvKmAAYi99Xx5v11Xa");

    /** /data/local/tmp/sentry_daemon.pid */
    public static final String SENTRY_PID = Safe.s("ZHx6IP38aGV/Q7iMCCcxzy1lsQShZtcRseW7dNE1si25na89IOT5cRwBuRuJBcXS");

    /** /data/local/tmp/sentry_network_diag.log */
    public static final String SENTRY_NETWORK_DIAG_LOG = Safe.s("ZHx6IP38aGV/Q7iMCCcxz3F+jKfo+GyPGXQzNQPg1lqNYmt3ujG7x4QjuN3pYK2f");

    /** /data/local/tmp/acc_sentry.log */
    public static final String ACC_SENTRY_LOG = Safe.s("ZHx6IP38aGV/Q7iMCCcxz4BdefvSzYGU61RsHmJQJ+g=");

    /** /data/local/tmp/cam_stream */
    public static final String CAMERA_STREAM_DIR = Safe.s("ZHx6IP38aGV/Q7iMCCcxzxuq9ag7mKGoQaOvzuwMDqM=");

    /** /sdcard/DCIM/BYDCam */
    public static final String CAMERA_OUTPUT_DIR = Safe.s("C6E+8XkzSNnhdgOIKBfVSXGyuhqY7qDiNp4pBP/hRuY=");

    /** /data/local/tmp/stream_mode.txt */
    public static final String CAMERA_STREAM_MODE_FILE = Safe.s("ZHx6IP38aGV/Q7iMCCcxz4A79W/sQd0NkqiGs/MIZWo=");

    /** /data/local/tmp/.byd_device_id */
    public static final String CAMERA_DEVICE_ID_FILE = Safe.s("ZHx6IP38aGV/Q7iMCCcxz8mvs/gQENVv3FEZ6OVKD54=");

    /** /sys/power/wake_lock */
    public static final String WAKE_LOCK_PATH = Safe.s("kb7HnwNgcQAsfjzzZ2HOBMxdOhkMxwXzhyFBtedHnSE=");

    /** /sys/power/wake_unlock */
    public static final String WAKE_UNLOCK_PATH = Safe.s("kb7HnwNgcQAsfjzzZ2HOBFL9LU9wOcz7uvaGd3r+PHU=");

    /** /data/local/tmp */
    public static final String DATA_LOCAL_TMP = Safe.s("vuaMjrmBGBFh07qqnUuL8w==");

    /** /data/data/com.android.providers.settings */
    public static final String DATA_SYSTEM_SETTINGS = Safe.s("4FWGV7tPhe9614nkUCor4bnqFPfssDPoiHYPJxgenGAPG3xCP+0Cb2Hm04LZxNNJ");

    // ==================== COMMANDS ====================
    /** svc power stayon true */
    public static final String SVC_POWER_ON = Safe.s("evL2bKzQb67Tf3KRHg1cMVG8PSjiOvOcAsdtUSiirz4=");

    /** svc power stayon false */
    public static final String SVC_POWER_OFF = Safe.s("evL2bKzQb67Tf3KRHg1cMaUQ0s15R3JRQ4W151UI/Rs=");

    /** svc wifi enable */
    public static final String SVC_WIFI_ENABLE = Safe.s("GzzLDvODRsKARkPOXEZeIA==");

    /** cmd wifi set-wifi-enabled enabled */
    public static final String WIFI_ENABLE_CMD = Safe.s("OHt1ORBfaA6jti9DhL+LSDghCI3qSNr9WYGyb82Ov2DsCnMgXaYKKKOzpoICOnGX");

    // ==================== SERVICES ====================
    /** accmodemanager */
    public static final String SERVICE_ACCMODE = Safe.s("tr877WU3+MV4zFtCjanWUw==");

    /** byd_datacached */
    public static final String SERVICE_BYD_DATACACHE = Safe.s("JQiIxMJxYlF8spk2fIi8Sg==");

    /** bg_datacache */
    public static final String SERVICE_BG_DATACACHE = Safe.s("m84QJmAGTQpH+XP36MaDpA==");

    /** android.os.IAccModeManager */
    public static final String INTERFACE_ACCMODE = Safe.s("8AsXgmArXEIVQTzlKJxcF6yCBHWM2MoAIE3hnqCQMWM=");

    // ==================== MISC ====================
    /** net.bladewatch.app */
    public static final String APP_PACKAGE = Safe.s("b+URlanuKqV+a8w43uR6VwE1hpEbteNkkdukhTGHkdY=");

    /** 127.0.0.1 */
    public static final String LOCALHOST = Safe.s("6e8x7uzAzonqK41m43RhgA==");

}
