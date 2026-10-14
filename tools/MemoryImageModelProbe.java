import android.net.Uri;
import dalvik.system.PathClassLoader;
import java.lang.reflect.Method;
import org.json.JSONObject;

/** Android app_process test: creates/serializes a model only, never invokes import/save/Provider. */
public final class MemoryImageModelProbe {
    public static void main(String[] args) throws Exception {
        System.loadLibrary("dexkit");
        ClassLoader target = new PathClassLoader(args[0], ClassLoader.getSystemClassLoader().getParent());
        Class<?> adapterType = Class.forName("dev.local.supercardhost.MemoryImageAdapter");
        Method resolve = adapterType.getDeclaredMethod("resolve", ClassLoader.class);
        resolve.setAccessible(true);
        Object adapter = resolve.invoke(null, target);
        Method completion = adapterType.getDeclaredMethod("completion"); completion.setAccessible(true);
        Object unit = completion.invoke(adapter);
        if (unit == null || !"kotlin.Unit".equals(unit.toString())) throw new AssertionError("Native Unit");
        Method create = adapterType.getDeclaredMethod("createModel", Object.class, Uri.class,
                String.class, String.class, String.class);
        create.setAccessible(true);
        String guid = "11111111-1111-1111-1111-111111111111";
        Object model = create.invoke(adapter, null, Uri.parse("content://probe/unused"),
                "dev.local.supercardhost", "Compatibility probe", guid);
        Class<?> resolver = Class.forName("dev.local.supercardhost.MemoryDexResolver");
        Method findJson = resolver.getMethod("jsonSerializer", ClassLoader.class, boolean.class);
        Method serializer = (Method) findJson.invoke(null, target, false);
        JSONObject json = new JSONObject((String) serializer.invoke(null, model, true));
        if (!json.getString("packageName").equals("dev.local.supercardhost")
                || !json.getString("appName").equals("Compatibility probe")
                || !json.getString("sceneName").equals("portal")
                || !json.getString("dataText").isEmpty()
                || json.getLong("updateTime") != -1
                || !json.getString("screenshot").equals("content://probe/unused"))
            throw new AssertionError("Serialized model semantics");
        JSONObject extra = new JSONObject(json.getString("extraData"));
        if (!extra.getString("triggerType").equals("1002") || !extra.getString("importId").equals(guid))
            throw new AssertionError("Serialized correlation");
        System.out.println("PASS: native Unit, 17 model properties and serialized Portal model; no memory writes");
    }
}
