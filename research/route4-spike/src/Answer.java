import android.os.IBinder;

import java.lang.reflect.Method;

/** Spike: answer the ringing call via ITelecomService.acceptRingingCall, as the shell uid. */
public class Answer {
    public static void main(String[] args) throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        IBinder b = (IBinder) sm.getMethod("getService", String.class).invoke(null, "telecom");
        Class<?> stub = Class.forName("com.android.internal.telecom.ITelecomService$Stub");
        Object telecom = stub.getMethod("asInterface", IBinder.class).invoke(null, b);
        Method accept = null;
        for (Method m : telecom.getClass().getMethods()) {
            if (m.getName().equals("acceptRingingCall")) { accept = m; break; }
        }
        if (accept == null) throw new IllegalStateException("acceptRingingCall not found");
        Object[] a = new Object[accept.getParameterCount()];
        for (int i = 0; i < a.length; i++) {
            a[i] = accept.getParameterTypes()[i] == String.class ? "com.android.shell" : null;
        }
        accept.invoke(telecom, a);
        System.out.println("[answer] acceptRingingCall invoked (" + a.length + " args)");
        System.exit(0);
    }
}
