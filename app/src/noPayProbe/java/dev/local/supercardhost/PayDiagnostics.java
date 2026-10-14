package dev.local.supercardhost;

import android.app.Application;
import io.github.libxposed.api.XposedModule;

/** Production build: no test entry, package routing or test hooks. */
final class PayDiagnostics {
    static void install(XposedModule module, Application host, ClassLoader loader) { }
    static boolean isTestPackage(String name) { return false; }
}
