import android.content.AttributionSource;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Spike: full on-device voicemail flow in ONE shell-uid process (app_process), so the audio-mode
 * token stays alive for the whole call:
 *   answer -> mute mic -> MODE_CALL_SCREENING -> greeting into uplink (in_call_music)
 *   -> record caller (VOICE_DOWNLINK) until hang-up / max secs -> end call -> restore.
 *
 * Usage: app_process / Voicemail <greeting.wav> <out.pcm> [maxSecs=60] [audioMode=4] [answer=1]
 */
public class Voicemail {
    static final String PKG = "com.android.shell";
    static final Binder MODE_TOKEN = new Binder();
    static Object origCallVol;

    // Local-playback (earpiece) silencing of the caller, OnePlus/QTI HAL keys found in libaudiocorehal.qti.so
    static final String[] RX_MUTE_ON = {
            "OPLUS_VC_DOWNLINK_MUTE_MODE=enable",
            "device_mute=true;direction=rx",
            "voice_translation_rx_mute=true",
    };
    static final String[] RX_MUTE_OFF = {
            "OPLUS_VC_DOWNLINK_MUTE_MODE=disable",
            "device_mute=false;direction=rx",
            "voice_translation_rx_mute=false",
    };

    static Object setParam(String kv) {
        try { return Class.forName("android.media.AudioSystem").getMethod("setParameters", String.class).invoke(null, kv); }
        catch (Throwable t) { return "err " + t; }
    }
    static Object getParam(String k) {
        try { return Class.forName("android.media.AudioSystem").getMethod("getParameters", String.class).invoke(null, k); }
        catch (Throwable t) { return "err " + t; }
    }

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        String greeting = args[0], outPath = args[1];
        int maxSecs = args.length > 2 ? Integer.parseInt(args[2]) : 60;
        int audioMode = args.length > 3 ? Integer.parseInt(args[3]) : 4;
        boolean doAnswer = args.length <= 4 || args[4].equals("1");

        Object telecom = service("telecom", "com.android.internal.telecom.ITelecomService$Stub");
        Object audio = service("audio", "android.media.IAudioService$Stub");
        try {
            if (doAnswer) {
                call(telecom, "acceptRingingCall");
                log("answered");
                for (int i = 0; i < 40 && !isInCallOffhook(audio); i++) Thread.sleep(100);
            }
            log("audio mode=" + call(audio, "getMode"));
            call(audio, "setMicrophoneMute", true);
            log("mic muted=" + call(audio, "isMicrophoneMuted"));
            if (audioMode >= 0) {
                call(audio, "setMode", audioMode);
                Thread.sleep(250);
                log("audio mode now=" + call(audio, "getMode"));
            }
            // Silence the caller on the phone itself (earpiece/speaker): STREAM_VOICE_CALL -> 0.
            // Index 0 is only allowed for MODIFY_PHONE_STATE holders (shell has it).
            origCallVol = call(audio, "getStreamVolume", 0);
            call(audio, "setStreamVolume", 0, 0, 0);
            log("call volume " + origCallVol + " -> " + call(audio, "getStreamVolume", 0)
                    + " (min=" + call(audio, "getStreamMinVolume", 0) + ")");
            call(audio, "adjustStreamVolume", 0, -100 /* ADJUST_MUTE */, 0);
            log("voice stream muted=" + call(audio, "isStreamMute", 0));
            for (String kv : RX_MUTE_ON) log("hal " + kv + " -> " + setParam(kv));
            log("hal state: " + getParam("OPLUS_GET_VC_DOWNLINK_MUTE_STATE"));

            long t0 = System.currentTimeMillis();
            playGreeting(greeting);
            log("greeting done in " + (System.currentTimeMillis() - t0) + "ms");

            record(outPath, maxSecs, telecom);
        } finally {
            try { call(telecom, "endCall"); log("endCall"); } catch (Throwable t) { log("endCall failed " + t); }
            for (String kv : RX_MUTE_OFF) setParam(kv);
            call(audio, "adjustStreamVolume", 0, 100 /* ADJUST_UNMUTE */, 0);
            if (origCallVol instanceof Integer) call(audio, "setStreamVolume", 0, (Integer) origCallVol, 0);
            if (audioMode >= 0) call(audio, "setMode", 0);
            call(audio, "setMicrophoneMute", false);
            log("call volume restored=" + call(audio, "getStreamVolume", 0));
            log("restored: mic muted=" + call(audio, "isMicrophoneMuted") + " mode=" + call(audio, "getMode"));
        }
        System.exit(0);
    }

    static boolean isInCallOffhook(Object audio) {
        Object m = call(audio, "getMode");
        return m instanceof Integer && (Integer) m == 2;
    }

    // ---------- greeting: USAGE_VOICE_COMMUNICATION + preferred device TELEPHONY -> in_call_music ----------
    static void playGreeting(String path) throws Exception {
        byte[] wav = readAll(path);
        int rate = le32(wav, 24), ch = le16(wav, 22), off = findData(wav), len = wav.length - off;
        AudioFormat fmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(AudioTrack.getMinBufferSize(rate, fmt.getChannelMask(), AudioFormat.ENCODING_PCM_16BIT) * 4, 48000))
                .build();
        AudioDeviceInfo tx = device(2 /* outputs */, AudioDeviceInfo.TYPE_TELEPHONY);
        log("telephony tx=" + (tx == null ? "NOT FOUND" : tx.getId()) + " preferred=" + (tx != null && track.setPreferredDevice(tx)));
        track.play();
        for (int w = 0; w < len; ) {
            int n = track.write(wav, off + w, Math.min(8192, len - w));
            if (n < 0) { log("write err " + n); break; }
            w += n;
        }
        long frames = len / (2L * ch);
        while (track.getPlaybackHeadPosition() < frames - 10 && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) Thread.sleep(40);
        Thread.sleep(150);
        log("greeting routed to " + (track.getRoutedDevice() == null ? "null" : track.getRoutedDevice().getType()));
        track.stop();
        track.release();
    }

    // ---------- record caller: VOICE_DOWNLINK, raw PCM 16k mono, until call ends or maxSecs ----------
    static void record(String outPath, int maxSecs, Object telecom) throws Exception {
        int rate = 16000;
        int src = MediaRecorder.AudioSource.VOICE_DOWNLINK;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord.Builder()
                .setContext(new ShellContext())
                .setAudioSource(src)
                .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 4, 32000))
                .build();
        log("recorder state=" + rec.getState());
        rec.startRecording();
        log("recording state=" + rec.getRecordingState());
        byte[] buf = new byte[3200]; // 100 ms
        long end = System.currentTimeMillis() + maxSecs * 1000L, bytes = 0, lastCheck = 0;
        double peak = 0;
        try (FileOutputStream out = new FileOutputStream(outPath)) {
            while (System.currentTimeMillis() < end) {
                int n = rec.read(buf, 0, buf.length);
                if (n < 0) { log("read err " + n); break; }
                out.write(buf, 0, n);
                bytes += n;
                for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
                long now = System.currentTimeMillis();
                if (now - lastCheck > 500) {
                    lastCheck = now;
                    Object inCall = call(telecom, "isInCall");
                    if (Boolean.FALSE.equals(inCall)) { log("caller hung up"); break; }
                }
            }
        }
        rec.stop();
        rec.release();
        log(String.format("recorded %.1fs, peak=%.1f dBFS -> %s", bytes / (2.0 * rate), 20 * Math.log10(Math.max(peak, 1) / 32768.0), outPath));
    }

    /** Minimal Context so AudioRecord attributes the capture to com.android.shell (uid 2000). */
    static class ShellContext extends ContextWrapper {
        ShellContext() { super(null); }
        @Override public String getPackageName() { return PKG; }
        @Override public String getOpPackageName() { return PKG; }
        @Override public String getAttributionTag() { return null; }
        @Override public AttributionSource getAttributionSource() {
            return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName(PKG).build();
        }
        @Override public int getDeviceId() { return 0; }
        @Override public android.content.Context getApplicationContext() { return this; }
    }

    // ---------- reflection helpers ----------
    static Object service(String name, String stub) throws Exception {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        return Class.forName(stub).getMethod("asInterface", IBinder.class).invoke(null, b);
    }

    /** Call a binder method by name, filling int/boolean from vals and String/IBinder params with shell defaults. */
    static Object call(Object svc, String name, Object... vals) {
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];
            int vi = 0;
            boolean firstString = true;
            for (int i = 0; i < p.length; i++) {
                if (p[i] == int.class) a[i] = vi < vals.length && vals[vi] instanceof Integer ? vals[vi++] : 0;
                else if (p[i] == boolean.class) a[i] = vi < vals.length && vals[vi] instanceof Boolean ? vals[vi++] : false;
                else if (p[i] == String.class) { a[i] = firstString ? PKG : null; firstString = false; }
                else if (IBinder.class.isAssignableFrom(p[i])) a[i] = MODE_TOKEN;
            }
            try {
                return m.invoke(svc, a);
            } catch (Throwable t) {
                return "err:" + (t.getCause() != null ? t.getCause() : t);
            }
        }
        return "n/a:" + name;
    }

    static AudioDeviceInfo device(int flags, int type) {
        try {
            Method m = android.media.AudioManager.class.getDeclaredMethod("getDevicesStatic", int.class);
            m.setAccessible(true);
            for (AudioDeviceInfo d : (AudioDeviceInfo[]) m.invoke(null, flags)) if (d.getType() == type) return d;
        } catch (Throwable t) { log("getDevicesStatic failed " + t); }
        return null;
    }

    static int findData(byte[] w) {
        for (int i = 12; i < w.length - 8; ) {
            int sz = le32(w, i + 4);
            if (new String(w, i, 4).equals("data")) return i + 8;
            i += 8 + sz;
        }
        return 44;
    }
    static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }
    static byte[] readAll(String p) throws Exception {
        try (InputStream in = new FileInputStream(p)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            for (int n; (n = in.read(b)) > 0; ) o.write(b, 0, n);
            return o.toByteArray();
        }
    }
    static void log(String s) { System.out.println("[vm] " + s); }
}
