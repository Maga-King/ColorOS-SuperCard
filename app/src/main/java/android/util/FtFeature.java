package android.util;

/** No vendor hardware/service capability is advertised until implemented. */
public final class FtFeature {
    public static final int FEATURE_CURVED_SCREEN_MASK = 0;
    public static boolean isFeatureSupport(int feature) { return false; }
    public static boolean isFeatureSupport(String feature) { return false; }
    public static String getFeatureAttribute(String feature, String attribute, String fallback) { return fallback; }
}
