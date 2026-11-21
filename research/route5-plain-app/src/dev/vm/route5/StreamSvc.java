package dev.vm.route5;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Exists only so the app qualifies for android.app.role.SYSTEM_CALL_STREAMING (grants CALL_AUDIO_INTERCEPTION).
 * The role's RequiredComponent only checks the intent-filter action + BIND_CALL_STREAMING_SERVICE permission.
 */
public class StreamSvc extends Service {
    @Override public IBinder onBind(Intent i) { return null; }
}
