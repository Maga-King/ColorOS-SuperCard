import android.os.Looper;
import android.net.Uri;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the actual packaged Job retry gate on Android; never starts an import or writes a Memory. */
public final class MemoryRetryProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> jobClass = Class.forName("dev.local.supercardhost.MemoryImportBridge$Job");
        Constructor<?> ctor = jobClass.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object job = ctor.newInstance("11111111-1111-1111-1111-111111111111", 101,
                "com.android.systemui", "probe", new ArrayList<>(List.of(Uri.parse("content://probe/image"))),
                new ArrayList<>(List.of(101)), null, new AtomicBoolean(true), 1);
        Method gate = jobClass.getDeclaredMethod("canRetryBeforeWrite"); gate.setAccessible(true);
        check(gate, job, false, "pending");
        set(job, "complete", true); set(job, "status", "native_error");
        check(gate, job, true, "confirmed pre-write failure");
        set(job, "writeAttempted", true);
        check(gate, job, false, "write attempted without an acknowledgement");
        set(job, "writeAttempted", false); set(job, "nativeDone", true);
        check(gate, job, false, "native completed");
        set(job, "nativeDone", false);
        Field idsField = jobClass.getDeclaredField("ids"); idsField.setAccessible(true);
        Set ids = (Set) idsField.get(job); ids.add("real-id");
        check(gate, job, false, "memory ID observed"); ids.clear();
        set(job, "nativeId", "native-id");
        check(gate, job, false, "native ID observed before validation");
        set(job, "nativeId", null);
        set(job, "attempt", 2);
        check(gate, job, true, "second confirmed pre-write failure");
        set(job, "attempt", 3);
        check(gate, job, false, "third attempt is terminal");
        set(job, "attempt", 1);
        for (String state : List.of("saved", "timeout", "provider_error", "copy_unconfirmed", "native_rejected")) {
            set(job, "status", state); check(gate, job, false, state);
        }
        System.out.println("PASS: 13 packaged retry-gate cases; no imports or memory writes performed");
        System.exit(0);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void check(Method method, Object target, boolean expected, String name) throws Exception {
        if (!Boolean.valueOf(expected).equals(method.invoke(target))) throw new AssertionError(name);
    }
}
