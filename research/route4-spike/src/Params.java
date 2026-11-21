import java.lang.reflect.Method;

/** Spike: get/set audio HAL parameters as shell. Usage: app_process / Params get <keys> | set <kv;kv> */
public class Params {
    public static void main(String[] a) throws Exception {
        Class<?> as = Class.forName("android.media.AudioSystem");
        if (a[0].equals("get")) {
            Method m = as.getMethod("getParameters", String.class);
            for (String k : a[1].split(",")) System.out.println("[params] " + k + " -> '" + m.invoke(null, k) + "'");
        } else {
            Method m = as.getMethod("setParameters", String.class);
            System.out.println("[params] set " + a[1] + " -> " + m.invoke(null, a[1]));
        }
        System.exit(0);
    }
}
