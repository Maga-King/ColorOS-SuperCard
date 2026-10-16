"""Offline behavioral tests of the production PAY settings/routing with fake Android services.

Runs no device commands. The fakes model users, package installation, Intent/options and the
SDK context cache; ROM IPC, libxposed interception and payment UI still need device testing.
"""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCES = {
    "android.os.Bundle": """
import java.util.*;
public class Bundle extends HashMap<String,Object> {
 public Bundle() {} public Bundle(Bundle b) {super(b);}
 public void putInt(String k,int v){put(k,v);} public int getInt(String k,int d){return containsKey(k)?(Integer)get(k):d;}
 public int getInt(String k){return getInt(k,0);}
 public void putBoolean(String k,boolean v){put(k,v);} public boolean getBoolean(String k){return Boolean.TRUE.equals(get(k));}
 public void putIntArray(String k,int[] v){put(k,v);} public int[] getIntArray(String k){return (int[])get(k);}
 public void putBundle(String k,Bundle v){put(k,v);} public Bundle getBundle(String k){return (Bundle)get(k);}
}
""",
    "android.os.UserHandle": """
public class UserHandle {public final int id; public UserHandle(int id){this.id=id;}
 public static UserHandle getUserHandleForUid(int uid){return new UserHandle(uid/100000);}
 public int getIdentifier(){return id;}}
""",
    "android.os.UserManager": """
public class UserManager {public static class Info {public int id; Info(int id){this.id=id;}}
 public java.util.List<Info> getUsers(){return java.util.List.of(new Info(0),new Info(10),new Info(991),new Info(998));}}
""",
    "android.os.Looper": "public class Looper {public static Looper getMainLooper(){return new Looper();}}",
    "android.os.Handler": "public class Handler {public Handler(Looper l){} public void post(Runnable r){r.run();}}",
    "android.os.SystemClock": "public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}",
    "android.content.ContentResolver": "public class ContentResolver {public final int user; public ContentResolver(int u){user=u;}}",
    "android.content.Context": """
import android.os.*; import android.content.pm.*;
public class Context {
 public final int user; public Context(){this(0);} public Context(int u){user=u;}
 public Context createContextAsUser(UserHandle u,int flags){if(u.id!=0)throw new IllegalStateException("SystemUI not installed in clone");return this;}
 public Context createPackageContextAsUser(String pkg,int flags,UserHandle u){if(!pkg.equals("android"))throw new IllegalArgumentException();return new Context(u.id);}
 public Context getApplicationContext(){return this;}
 public ContentResolver getContentResolver(){return new ContentResolver(user);}
 public PackageManager getPackageManager(){return new PackageManager(user);}
 public <T> T getSystemService(Class<T> t){return t.cast(new UserManager());}
 public static Intent launched; public static Bundle options; public static int launchUser=-1, launches;
 public void startActivity(Intent i){startActivity(i,null);}
 public void startActivity(Intent i,Bundle b){startActivityAsUser(i,b,new UserHandle(user));}
 public void startActivityAsUser(Intent i,Bundle b,UserHandle u){launched=i;options=b;launchUser=u.id;launches++;}
}
""",
    "android.content.ContextWrapper": """
import android.os.*; import android.content.pm.*;
public class ContextWrapper extends Context {
 final Context base; public ContextWrapper(Context c){base=c;}
 public Context getApplicationContext(){return base.getApplicationContext();}
 public ContentResolver getContentResolver(){return base.getContentResolver();}
 public PackageManager getPackageManager(){return base.getPackageManager();}
 public void startActivity(Intent i,Bundle b){base.startActivity(i,b);}
}
""",
    "android.content.pm.ApplicationInfo": "public class ApplicationInfo {public static final int FLAG_INSTALLED=0x800000; public int flags=FLAG_INSTALLED,uid; public boolean enabled=true;}",
    "android.content.pm.PackageManager": """
public class PackageManager {public static final java.util.Map<String,ApplicationInfo> apps=new java.util.HashMap<>();
 final int user; public PackageManager(int u){user=u;}
 public ApplicationInfo getApplicationInfo(String p,int flags) throws Exception {
  ApplicationInfo info=apps.get(user+":"+p); if(info==null)throw new Exception("not installed");return info;
 }
 public static void install(int u,String p){ApplicationInfo i=new ApplicationInfo();i.uid=u*100000+12345;apps.put(u+":"+p,i);}
}
""",
    "android.content.ComponentName": "public class ComponentName {final String pkg; public ComponentName(String p,String c){pkg=p;} public String getPackageName(){return pkg;}}",
    "android.content.Intent": """
import android.net.Uri;
public class Intent {
 public static final String ACTION_MAIN="android.intent.action.MAIN";
 String action,pkg; ComponentName component; Uri data; public int flags,oplusFlags;
 public java.util.Map<String,Object> extras=new java.util.HashMap<>();
 public Intent(){} public Intent(String a){action=a;}
 public Intent(Intent i){action=i.action;pkg=i.pkg;component=i.component;data=i.data;flags=i.flags;oplusFlags=i.oplusFlags;extras.putAll(i.extras);}
 public String getAction(){return action;} public String getPackage(){return pkg;} public ComponentName getComponent(){return component;}
 public Uri getData(){return data;}
 public Intent setPackage(String p){pkg=p;return this;} public Intent setComponent(ComponentName c){component=c;return this;}
 public Intent setData(Uri u){data=u;return this;}
}
""",
    "android.net.Uri": """
public class Uri {final java.net.URI uri; Uri(String s){uri=java.net.URI.create(s);} public static Uri parse(String s){return new Uri(s);}
 public String getScheme(){return uri.getScheme();} public String getHost(){return uri.getHost();} public String getPath(){return uri.getPath();}
 public String getQueryParameter(String key){if(uri.getRawQuery()!=null) for(String p:uri.getRawQuery().split("&")){
  String[] v=p.split("=",2);if(v[0].equals(key))return v.length==2?v[1]:"";}return null;}}
""",
    "android.provider.Settings": """
import android.content.ContentResolver;
public class Settings {public static class Secure {
 public static final java.util.Map<String,Integer> values=new java.util.HashMap<>(); public static boolean writable=true;
 public static int getInt(ContentResolver c,String k,int d){return values.getOrDefault(c.user+":"+k,d);}
 public static boolean putInt(ContentResolver c,String k,int v){if(!writable)return false;values.put(c.user+":"+k,v);return true;}
}}
""",
    "android.app.Application": "public class Application extends android.content.Context {}",
    "android.app.ActivityOptions": """
public class ActivityOptions {int id;public static ActivityOptions makeBasic(){return new ActivityOptions();}
 public void setLaunchDisplayId(int i){id=i;} public android.os.Bundle toBundle(){android.os.Bundle b=new android.os.Bundle();b.putInt("display",id);return b;}}
""",
    "android.util.Log": "public class Log {public static void i(String t,String m){} public static void w(String t,String m,Throwable e){}}",
    "android.widget.Toast": "public class Toast {public static final int LENGTH_LONG=1;public static Toast makeText(android.content.Context c,String s,int l){return new Toast();} public void show(){}}",
    "com.oplus.content.OplusIntent": """
public class OplusIntent {public static final int OPLUS_FLAG_MULTI_APP_SKIP_CHOOSER=2048;
 public static int getOplusFlags(android.content.Intent i){return i.oplusFlags;}
 public static void setOplusFlags(android.content.Intent i,int f){i.oplusFlags=f;}}
""",
    "com.oplus.multiapp.OplusMultiAppManager": """
public class OplusMultiAppManager {
 public static boolean legacy, fail; public static OplusMultiAppManager getInstance(){return new OplusMultiAppManager();}
 public java.util.List<Integer> getMultiAppUids(String p){if(legacy||fail)throw new UnsupportedOperationException();return java.util.List.of(12345,99112345,99812345);}
 public android.os.UserHandle getMultiAppUserHandle(){if(fail)throw new UnsupportedOperationException();return new android.os.UserHandle(998);}
 public boolean isMultiApp(int id,String p){if(fail)throw new UnsupportedOperationException();return id==991||id==998;}
}
""",
    "io.github.libxposed.api.XposedModule": """
import java.lang.reflect.*; import java.util.*;
public class XposedModule {
 public interface Hook {Object intercept(Chain c)throws Throwable;}
 public static class Chain {
  final Method m;final Object target;final Object[] args;
  Chain(Method m,Object t,Object[] a){this.m=m;target=t;args=a;}
  public Object getArg(int n){return args[n];} public Object proceed()throws Throwable{return proceed(args);}
  public Object proceed(Object[] a)throws Throwable{try{return m.invoke(target,a);}catch(InvocationTargetException e){throw e.getCause();}}
 }
 final Map<Method,Hook> hooks=new HashMap<>();
 public class Builder {final Method m;Builder(Method m){this.m=m;}public void intercept(Hook h){hooks.put(m,h);}}
 public Builder hook(Method m){return new Builder(m);}
 public Object call(Method m,Object target,Object... args)throws Throwable{return hooks.get(m).intercept(new Chain(m,target,args));}
}
""",
    "dev.local.supercardhost.PayRuntimeBridge": """
final class PayRuntimeBridge {static int display=10;static long generation=1;
 static int getActiveDisplayId(){return display;}static int getWeChatRideDisplayId(){return display;}
 static long getLaunchGeneration(){return generation;}
 static int userForFunction(android.content.Context c,String f){return PayUserSettings.resolve(c,f);}
 static int userForApp(android.content.Context c,String a){return PayUserSettings.resolve(c,a.equals("wechat")?"wx_pay":"alipay_pay");}}
""",
    "com.vivo.card.cards.cardpay.virtual.PayCardSDKLauncher": """
import android.content.Context;
public class PayCardSDKLauncher {public static final PayCardSDKLauncher INSTANCE=new PayCardSDKLauncher();
 public static Context wxBusApi,captured;public static String function;public static int providerUser;
 public boolean launchWeChatPay(Context c,String f){captured=c;function=f;return true;}
 public static void launchWeChatBusCode$lambda$6(Context c){if(wxBusApi==null)wxBusApi=c;providerUser=wxBusApi.getContentResolver().user;}
 public boolean launchWeChatBusCodeOnMain(Context c){launchWeChatBusCode$lambda$6(c);return true;}}
""",
    "com.vivo.card.cards.cardpay.virtual.PayCardIntentBuilder": """
public class PayCardIntentBuilder {public static final PayCardIntentBuilder INSTANCE=new PayCardIntentBuilder();
 public android.content.Intent buildPaymentIntent(android.content.Context c,String f){return new android.content.Intent().setData(
  android.net.Uri.parse("alipays://platformapi/startapp?appId=20000056"));}}
""",
    "com.vivo.card.cards.cardpay.switcher.SwitcherUtil": """
public class SwitcherUtil {public static int calls;
 public static void openPayCardScene(android.content.Context c,String f,boolean report,int type){calls++;}}
""",
}


def main():
    java_home = Path(os.environ["JAVA_HOME"])
    with tempfile.TemporaryDirectory(prefix="supercard-pay-users-") as temp:
        directory = Path(temp)
        files = []
        for name, source in SOURCES.items():
            path = directory.joinpath(*name.split(".")).with_suffix(".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("package " + name.rsplit(".", 1)[0] + ";\n" + source, encoding="utf-8")
            files.append(str(path))
        production = ROOT / "app/src/main/java/dev/local/supercardhost"
        files += [str(production / (name + ".java")) for name in ("PayUserSettings", "PayUserRouting")]
        files.append(str(ROOT / "tools/TestPayUsers.java"))
        subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(directory), *files], check=True)
        subprocess.run([str(java_home / "bin/java"), "-cp", str(directory), "dev.local.supercardhost.TestPayUsers"], check=True)


if __name__ == "__main__":
    main()
