package com.liskovsoft.smartyoutubetv2.common.utils;

import android.os.Build;

/**
 * Utility class for detecting RayNeo X3 Pro AR glasses hardware.
 * Uses multiple detection strategies for robustness.
 */
public class RayNeoDeviceUtil {

    private static Boolean sIsRayNeoDevice;

    /**
     * Returns true if running on a RayNeo X3 Pro device.
     * Result is cached after first call.
     */
    public static boolean isRayNeoDevice() {
        if (sIsRayNeoDevice == null) {
            sIsRayNeoDevice = detectRayNeo();
        }
        return sIsRayNeoDevice;
    }

    /**
     * Adjusts DisplayMetrics for AR legibility.
     * Specifically handles the 1280x480 SBS resolution of RayNeo X3 Pro.
     */
    public static void applyDisplayMetrics(android.util.DisplayMetrics metrics, float uiScale) {
        int widthPixels = Math.max(metrics.widthPixels, metrics.heightPixels);
        int heightPixels = Math.min(metrics.widthPixels, metrics.heightPixels);

        // Detect RayNeo X3 Pro: 1280×480 binocular display (640×480 per eye).
        if (widthPixels <= 1280 && heightPixels <= 480) {
            float density = 1.0f * uiScale;
            metrics.density = density;
            metrics.scaledDensity = density;
            metrics.densityDpi = (int) (160 * uiScale);
            // Trick Android into thinking the physical screen is only 640px wide.
            // This fixes Leanback's HorizontalGridView scroll math, which otherwise
            // assumes a 1280px screen and breaks left-to-right panning/focus.
            metrics.widthPixels = widthPixels / 2;
        } else {
            // Standard Android TV logic (MDPI = 160 DPI)
            float widthRatio = 1920f / widthPixels; // DEFAULT_WIDTH = 1920f
            float density = (2.0f / widthRatio) * uiScale; // DEFAULT_DENSITY = 2.0f
            metrics.density = density;
            metrics.scaledDensity = density;
            metrics.densityDpi = (int) (160 * density);
        }
    }

    private static boolean detectRayNeo() {
        // Strategy 1: Try the SDK's DeviceUtil (most reliable)
        try {
            Class<?> deviceUtil = Class.forName("com.ffalcon.mercury.android.sdk.util.DeviceUtil");
            Object result = deviceUtil.getMethod("isX3Device").invoke(null);
            if (result instanceof Boolean && (Boolean) result) {
                return true;
            }
        } catch (Throwable ignored) {
            // SDK not available
        }

        // Strategy 2: Check Build properties for known RayNeo identifiers
        String manufacturer = Build.MANUFACTURER != null ? Build.MANUFACTURER.toLowerCase() : "";
        String model = Build.MODEL != null ? Build.MODEL.toLowerCase() : "";
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";

        return manufacturer.contains("rayneo") || manufacturer.contains("ffalcon") ||
               brand.contains("rayneo") || brand.contains("mercury") ||
               model.contains("x3 pro") || model.contains("x3pro");
    }
}
