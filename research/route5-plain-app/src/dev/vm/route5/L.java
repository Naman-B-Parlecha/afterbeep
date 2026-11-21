package dev.vm.route5;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Logcat + file log (logcat wraps fast on this phone). */
final class L {
    static void i(Context c, String m) {
        Log.i("VM5", m);
        try (FileWriter w = new FileWriter(new File(c.getExternalFilesDir(null), "log.txt"), true)) {
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + m + "\n");
        } catch (Exception ignored) {}
    }
}
