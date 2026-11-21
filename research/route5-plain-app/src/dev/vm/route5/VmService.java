package dev.vm.route5;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.telecom.TelecomManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Route 5: only public APIs + normal/runtime permissions.
 * ring -> wait 4s -> acceptRingingCall -> speakerphone on -> play greeting out loud (mic picks it up)
 * -> record mic (VOICE_RECOGNITION) until call ends / 60s -> endCall.
 */
public class VmService extends Service {
    static final String TAG = "VM5";
    static final int RING_MS = 4000, MAX_SECS = 60;
    static final int SRC = MediaRecorder.AudioSource.VOICE_RECOGNITION;

    final Handler main = new Handler(Looper.getMainLooper());
    TelephonyManager tm;
    TelecomManager telecom;
    AudioManager am;
    volatile int callState = TelephonyManager.CALL_STATE_IDLE;
    volatile boolean ours = false;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("vm", "Voicemail", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "vm").setContentTitle("VM Route5 armed")
                .setSmallIcon(android.R.drawable.sym_call_missed).setOngoing(true).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        tm = getSystemService(TelephonyManager.class);
        telecom = getSystemService(TelecomManager.class);
        am = getSystemService(AudioManager.class);
        // Dual SIM: the default TelephonyManager only reports the default subscription.
        android.telephony.SubscriptionManager sm = getSystemService(android.telephony.SubscriptionManager.class);
        java.util.List<android.telephony.SubscriptionInfo> subs = sm.getActiveSubscriptionInfoList();
        if (subs == null || subs.isEmpty()) {
            tm.registerTelephonyCallback(getMainExecutor(), new CallCb());
        } else {
            for (android.telephony.SubscriptionInfo s : subs) {
                tm.createForSubscriptionId(s.getSubscriptionId()).registerTelephonyCallback(getMainExecutor(), new CallCb());
                log("listening on sub " + s.getSubscriptionId() + " slot " + s.getSimSlotIndex());
            }
        }
        // Backup ring detection #1: the classic PHONE_STATE broadcast.
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(android.content.Context c, Intent i) {
                String st = i.getStringExtra(TelephonyManager.EXTRA_STATE);
                log("PHONE_STATE broadcast: " + st);
                onState(TelephonyManager.EXTRA_STATE_RINGING.equals(st) ? TelephonyManager.CALL_STATE_RINGING
                        : TelephonyManager.EXTRA_STATE_OFFHOOK.equals(st) ? TelephonyManager.CALL_STATE_OFFHOOK
                        : TelephonyManager.CALL_STATE_IDLE, "broadcast");
            }
        }, new android.content.IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), RECEIVER_EXPORTED);
        // Backup ring detection #2: audio mode (1 = RINGTONE, 2 = IN_CALL, 0 = NORMAL).
        am.addOnModeChangedListener(getMainExecutor(), mode -> {
            log("audio mode -> " + mode);
            if (mode == AudioManager.MODE_RINGTONE) onState(TelephonyManager.CALL_STATE_RINGING, "audiomode");
            else if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) onState(TelephonyManager.CALL_STATE_OFFHOOK, "audiomode");
            else if (mode == AudioManager.MODE_NORMAL) onState(TelephonyManager.CALL_STATE_IDLE, "audiomode");
        });
        log("armed");
    }

    class CallCb extends TelephonyCallback implements TelephonyCallback.CallStateListener {
        @Override public void onCallStateChanged(int state) { onState(state, "callback"); }
    }

    boolean vmRunning = false;
    synchronized void onState(int state, String via) {
        {
            int prev = callState;
            if (state == prev) return;
            callState = state;
            log("call state " + prev + " -> " + state + " via " + via);
            boolean dialer = getSystemService(android.app.role.RoleManager.class).isRoleHeld(android.app.role.RoleManager.ROLE_DIALER);
            if (dialer) { log("default dialer -> InCallSvc handles the call"); return; }
            if (state == TelephonyManager.CALL_STATE_RINGING) {
                main.postDelayed(() -> {
                    if (callState != TelephonyManager.CALL_STATE_RINGING) return;
                    ours = true;
                    telecom.acceptRingingCall();
                    log("acceptRingingCall");
                }, RING_MS);
            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK && ours && !vmRunning) {
                vmRunning = true;
                new Thread(VmService.this::runVoicemail, "vm").start();
            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                ours = false;
                vmRunning = false;
            }
        }
    }

    void runVoicemail() {
        int origVol = am.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
        try {
            Thread.sleep(1200);
            speaker(true);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0);
            log("speaker=" + am.isSpeakerphoneOn() + " commDev=" + desc(am.getCommunicationDevice())
                    + " vol=" + am.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
            playGreeting();
            record();
        } catch (Throwable t) {
            log("voicemail failed " + Log.getStackTraceString(t));
        } finally {
            if (callState != TelephonyManager.CALL_STATE_IDLE) {
                boolean ok = telecom.endCall();
                log("endCall=" + ok);
            }
            speaker(false);
            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, origVol, 0);
        }
    }

    void speaker(boolean on) {
        try {
            if (on) {
                for (AudioDeviceInfo d : am.getAvailableCommunicationDevices())
                    if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) log("setCommunicationDevice(speaker)=" + am.setCommunicationDevice(d));
            } else {
                am.clearCommunicationDevice();
            }
            am.setSpeakerphoneOn(on);
        } catch (Throwable t) {
            log("speaker(" + on + ") failed " + t);
        }
    }

    void playGreeting() throws Exception {
        final Object done = new Object();
        MediaPlayer mp = new MediaPlayer();
        mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        mp.setDataSource(getResources().openRawResourceFd(R.raw.greeting));
        mp.setOnCompletionListener(p -> { synchronized (done) { done.notifyAll(); } });
        mp.prepare();
        mp.start();
        log("greeting playing, routed=" + desc(mp.getRoutedDevice()));
        synchronized (done) { done.wait(15000); }
        mp.release();
        log("greeting done");
    }

    void record() throws Exception {
        int rate = 16000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(SRC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min * 4, 32000));
        String name = "vm5-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".pcm";
        File f = new File(getExternalFilesDir(null), name);
        rec.startRecording();
        log("recording state=" + rec.getRecordingState() + " silenced=" + (rec.getActiveRecordingConfiguration() != null && rec.getActiveRecordingConfiguration().isClientSilenced()) + " -> " + f);
        byte[] buf = new byte[3200];
        long end = System.currentTimeMillis() + MAX_SECS * 1000L, bytes = 0;
        double peak = 0;
        try (FileOutputStream out = new FileOutputStream(f)) {
            while (System.currentTimeMillis() < end && callState == TelephonyManager.CALL_STATE_OFFHOOK) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) { log("read err " + n); break; }
                out.write(buf, 0, n);
                bytes += n;
                for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
            }
        }
        rec.stop();
        rec.release();
        log(String.format(Locale.US, "recorded %.1fs peak=%.1f dBFS file=%s", bytes / (2.0 * rate),
                20 * Math.log10(Math.max(peak, 1) / 32768.0), f));
    }

    /** Log to logcat AND a file (logcat wraps fast on this phone). */
    void log(String m) {
        Log.i(TAG, m);
        try (java.io.FileWriter w = new java.io.FileWriter(new File(getExternalFilesDir(null), "log.txt"), true)) {
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + " " + m + "\n");
        } catch (Exception ignored) {}
    }

    static String desc(AudioDeviceInfo d) { return d == null ? "null" : d.getType() + ":" + d.getProductName(); }

    @Override public IBinder onBind(Intent i) { return null; }
}
