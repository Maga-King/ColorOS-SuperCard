package android.os;

/** Plugin-local OriginOS UI compatibility profile; does not modify device properties. */
public final class FtBuild {
    public static String getOsName() { return "OriginOS"; }
    public static String getOsVersion() { return "17.0"; }
    public static String getFirstOsVersion() { return "17.0"; }
    public static float getRomVersion() { return 17.0f; }
    public static String getProductName() { return Build.MODEL; }
    public static boolean isOverSeas() { return false; }
}
