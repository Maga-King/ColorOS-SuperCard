"""Offline PAY startup regression using the production client, receiver and retry loop.

Fake Android time/IPC makes the 30-second backoff deterministic. A missing APK
stops each attempted startup before ProcessBuilder, so this never invokes su.
Android permission enforcement and real Binder startup require device validation.
"""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "android.os.SystemClock": "public class SystemClock {public static long now; public static long elapsedRealtime(){return now;}}",
    "android.os.Looper": "public class Looper {static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return MAIN;}}",
    "android.os.HandlerThread": "public class HandlerThread {public HandlerThread(String n){} public void start(){} public Looper getLooper(){return Looper.getMainLooper();}}",
    "android.os.Handler": """
import java.util.*;
public class Handler {
 record Job(Runnable action,long at,long order){}
 static long order; static final PriorityQueue<Job> jobs=new PriorityQueue<>(Comparator.comparingLong(Job::at).thenComparingLong(Job::order));
 public Handler(Looper l){} public void post(Runnable r){postDelayed(r,0);}
 public void postDelayed(Runnable r,long delay){jobs.add(new Job(r,SystemClock.now+delay,order++));}
 public void removeCallbacks(Runnable r){jobs.removeIf(j->j.action()==r);}
 public static void until(long time){int count=0; while(!jobs.isEmpty()&&jobs.peek().at()<=time){
  if(++count>10000)throw new AssertionError("retry loop did not yield"); Job j=jobs.remove(); SystemClock.now=j.at(); j.action().run();
 } SystemClock.now=time;}
}
""",
    "android.os.IBinder": """
public interface IBinder {interface DeathRecipient {void binderDied();}
 boolean isBinderAlive(); void linkToDeath(DeathRecipient r,int f); boolean transact(int c,Parcel d,Parcel r,int f);}
""",
    "android.os.Binder": "public class Binder implements IBinder {public boolean isBinderAlive(){return true;} public void linkToDeath(DeathRecipient r,int f){} public boolean transact(int c,Parcel d,Parcel r,int f){return false;}}",
    "android.os.Bundle": "public class Bundle {public IBinder binder; public IBinder getBinder(String k){return binder;}}",
    "android.os.Parcel": """
public class Parcel {public static Parcel obtain(){return new Parcel();} public void recycle(){} public void readException(){}
 public void writeInterfaceToken(String s){} public void writeString(String s){} public void writeStrongBinder(IBinder b){}
 public void writeInt(int i){} public void writeTypedObject(Object o,int f){} public int readInt(){return -1;} public IBinder readStrongBinder(){return null;}}
""",
    "android.content.Intent": """
public class Intent {String action; boolean demand; public Intent(){} public Intent(String a){action=a;}
 public String getAction(){return action;} public Intent setClassName(String p,String c){return this;}
 public Intent putExtra(String k,boolean v){demand=v;return this;} public boolean getBooleanExtra(String k,boolean d){return demand;}
 public android.os.Bundle getExtras(){return null;} public String getStringExtra(String k){return null;}}
""",
    "android.content.IntentFilter": "public class IntentFilter {public IntentFilter(String a){}}",
    "android.content.ComponentName": "public class ComponentName {}",
    "android.content.ServiceConnection": """
public interface ServiceConnection {void onServiceConnected(ComponentName n,android.os.IBinder b); void onServiceDisconnected(ComponentName n);
 default void onBindingDied(ComponentName n){} default void onNullBinding(ComponentName n){}}
""",
    "android.content.BroadcastReceiver": """
public abstract class BroadcastReceiver {public static int completions; public abstract void onReceive(Context c,Intent i);
 public int getSentFromUid(){return 0;} public PendingResult goAsync(){return new PendingResult();}
 public static class PendingResult {public void finish(){completions++;}}}
""",
    "android.content.pm.ApplicationInfo": "public class ApplicationInfo {public String sourceDir;}",
    "android.content.Context": """
public class Context {public static final int RECEIVER_EXPORTED=2,BIND_AUTO_CREATE=1,BIND_IMPORTANT=64;
 public static final java.util.List<Long> attempts=new java.util.ArrayList<>(); public static int broadcasts,bindings;
 public String getPackageName(){return "dev.local.supercardhost";} public Context getApplicationContext(){return this;}
 public android.content.pm.ApplicationInfo getApplicationInfo(){attempts.add(android.os.SystemClock.now);return new android.content.pm.ApplicationInfo();}
 public java.io.File getFilesDir(){throw new AssertionError("missing APK should stop before filesystem writes");}
 public void registerReceiver(BroadcastReceiver r,IntentFilter f,String p,Object h,int flags){}
 public boolean bindService(Intent i,ServiceConnection c,int flags){bindings++;return true;} public void unbindService(ServiceConnection c){}
 public void sendBroadcast(Intent i){broadcasts++;new dev.local.supercardhost.RootDisplayStartReceiver().onReceive(new Context(),i);}
}
""",
    "android.app.Application": "public class Application extends android.content.Context {}",
    "android.hardware.display.DisplayManager": "public class DisplayManager {public static final int VIRTUAL_DISPLAY_FLAG_SECURE=4;}",
    "android.hardware.display.VirtualDisplay": "public class VirtualDisplay {}",
    "android.hardware.display.VirtualDisplayConfig": """
public class VirtualDisplayConfig {public static class Builder {public Builder(String n,int w,int h,int d){}
 public Builder setSurface(android.view.Surface s){return this;} public Builder setFlags(int f){return this;}
 public VirtualDisplayConfig build(){return new VirtualDisplayConfig();}}}
""",
    "android.view.Surface": "public class Surface {}",
    "android.util.Log": "public class Log {public static void i(String t,String m){} public static void w(String t,String m){} public static void w(String t,String m,Throwable e){} public static void e(String t,String m,Throwable e){}}",
    "dev.local.supercardhost.PluginRuntime": "public class PluginRuntime {public static void setPaymentSecure(boolean s){}}",
    "dev.local.supercardhost.TestPayStartup": """
import android.app.Application;
import android.content.*;
import android.os.*;
public class TestPayStartup {
 static int checks;
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
 static void count(int expected,String message){check(Context.attempts.size()==expected,message+": "+Context.attempts);}
 public static void main(String[] args){
  Context app=new Context();
  AutoVirtualDisplayManager.start(app,null);
  Handler.until(12000);
  count(5,"five initial startup failures");
  Handler.until(13000);
  AutoVirtualDisplayManager.start(app,null);
  Handler.until(13000);
  count(5,"routine startup respects background backoff");
  if(args[0].equals("background")){
   Handler.until(41999);count(5,"background retry waits thirty seconds");
   Handler.until(42000);count(6,"routine requests must not postpone the retry deadline");
  }else{
   RootDisplayClient.install(new Application());
   int bindings=Context.bindings;
   RootDisplayClient.requestBackendForPayment();
   for(int i=0;i<20;i++)RootDisplayClient.requestBackendForPayment();
   Handler.until(13000);
   check(Context.bindings==bindings,"PAY demand must reach an already bound starter");
   check(Context.broadcasts==1,"repeated PAY clicks must not spam demand broadcasts");
   count(5,"PAY demand still respects three-second startup debounce");
   Handler.until(14000);
   AutoVirtualDisplayManager.start(app,null);
   Handler.until(14999);count(5,"routine request must not discard the pending PAY demand");
   Handler.until(15000);count(6,"PAY demand must attempt before its ten-second UI deadline");
   check(Context.attempts.get(5)==15000L,"debounced PAY retry must not slide with repeated requests");
   check(BroadcastReceiver.completions==1,"privileged request must finish asynchronously");
   Handler.until(18000);count(6,"PAY demand is consumed after one attempt");
   Handler.until(44999);count(6,"failed PAY attempt returns to background backoff");
   Handler.until(45000);count(7,"background retries continue after PAY failure");
  }
  System.out.println("PAY startup "+args[0]+": "+checks+" checks passed (offline fake Android).");
 }
}
""",
}


def main():
    java_home = os.environ.get("JAVA_HOME")
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
    if not javac or not java:
        raise RuntimeError("A JDK is required for the offline startup test")
    with tempfile.TemporaryDirectory(prefix="supercard-pay-startup-") as temp:
        directory = Path(temp)
        files = []
        for name, source in SOURCES.items():
            path = directory.joinpath(*name.split(".")).with_suffix(".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + source, encoding="utf-8")
            files.append(str(path))
        production = ROOT / "app/src/main/java/dev/local/supercardhost"
        files += [str(production / (name + ".java")) for name in (
            "AutoVirtualDisplayManager", "RootDisplayClient", "RootDisplayStartReceiver")]
        subprocess.run([javac, "-encoding", "UTF-8", "-d", str(directory), *files], check=True)
        for scenario in ("background", "payment"):
            subprocess.run([java, "-cp", str(directory), "dev.local.supercardhost.TestPayStartup", scenario], check=True)


if __name__ == "__main__":
    main()
