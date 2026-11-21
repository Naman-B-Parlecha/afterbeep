import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

/**
 * Spike: inject a PCM WAV into the active cellular call's uplink.
 * Runs as the shell uid via app_process (holds CALL_AUDIO_INTERCEPTION / MODIFY_PHONE_STATE).
 *
 * Usage: app_process / Inject <file.wav> [mode]
 *   mode = assistant (default): AudioAttributes system usage USAGE_CALL_ASSISTANT (17),
 *          which the audio policy routes to in_call_music -> telephony_tx.
 */
public class Inject {
    private static final int USAGE_CALL_ASSISTANT = 17; // @SystemApi AudioAttributes constant

    public static void main(String[] args) throws Exception {
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        if (args[0].equals("unmute")) { setMicMute(audioService(), false); System.exit(0); }
        String path = args[0];
        String mode = args.length > 1 ? args[1] : "assistant";
        int audioMode = args.length > 2 ? Integer.parseInt(args[2]) : -1; // 4 = MODE_CALL_SCREENING
        boolean mute = args.length > 3 && args[3].equals("1");
        Object audioSvc = audioService();
        if (mute) setMicMute(audioSvc, true);
        log("audio mode before=" + invoke(audioSvc, "getMode"));
        if (audioMode >= 0) {
            setMode(audioSvc, audioMode);
            Thread.sleep(300);
            log("audio mode after setMode(" + audioMode + ")=" + invoke(audioSvc, "getMode"));
        }
        try {
            log("isPstnCallAudioInterceptable=" + invoke(audioSvc, "isPstnCallAudioInterceptable"));
        } catch (Throwable t) {
            log("isPstnCallAudioInterceptable failed: " + t);
        }

        byte[] wav = readAll(path);
        int rate = le32(wav, 24);
        int channels = le16(wav, 22);
        int dataOff = findData(wav);
        int dataLen = wav.length - dataOff;
        log("wav rate=" + rate + " ch=" + channels + " bytes=" + dataLen + " mode=" + mode);

        AudioAttributes.Builder ab = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH);
        if (mode.equals("assistant")) {
            Method setSystemUsage = AudioAttributes.Builder.class.getMethod("setSystemUsage", int.class);
            setSystemUsage.invoke(ab, USAGE_CALL_ASSISTANT);
        } else if (mode.equals("media")) {
            ab.setUsage(AudioAttributes.USAGE_MEDIA);
        } else { // "telephony": what AudioManager.getCallUplinkInjectionAudioTrack does
            ab.setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION);
        }
        AudioAttributes attrs = ab.build();
        log("attrs=" + attrs);

        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO)
                .build();
        int minBuf = AudioTrack.getMinBufferSize(rate, fmt.getChannelMask(), AudioFormat.ENCODING_PCM_16BIT);

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuf * 4, 48000))
                .build();
        log("track state=" + track.getState() + " routedTo=" + describe(track));
        if (!mode.equals("assistant")) {
            android.media.AudioDeviceInfo tx = telephonyTx();
            log("telephony tx device=" + (tx == null ? "NOT FOUND" : tx.getId() + ":" + tx.getType()));
            if (tx != null) log("setPreferredDevice=" + track.setPreferredDevice(tx));
        }

        track.play();
        int written = 0;
        while (written < dataLen) {
            int n = track.write(wav, dataOff + written, Math.min(8192, dataLen - written));
            if (n < 0) { log("write error " + n); break; }
            written += n;
            if (written == n) log("playing, routedTo=" + describe(track));
        }
        // let the buffer drain
        long frames = dataLen / (2L * channels);
        while (track.getPlaybackHeadPosition() < frames - 10) {
            Thread.sleep(50);
            if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) break;
        }
        Thread.sleep(300);
        track.stop();
        track.release();
        log("done, wrote " + written + " bytes; mode=" + invoke(audioSvc, "getMode"));
        System.exit(0);
    }

    /** Find the TYPE_TELEPHONY output device without a Context (hidden AudioManager.getDevicesStatic). */
    private static android.media.AudioDeviceInfo telephonyTx() {
        try {
            Method m = android.media.AudioManager.class.getDeclaredMethod("getDevicesStatic", int.class);
            m.setAccessible(true);
            android.media.AudioDeviceInfo[] devs = (android.media.AudioDeviceInfo[]) m.invoke(null, 2 /* GET_DEVICES_OUTPUTS */);
            for (android.media.AudioDeviceInfo d : devs) {
                if (d.getType() == android.media.AudioDeviceInfo.TYPE_TELEPHONY) return d;
            }
        } catch (Throwable t) {
            log("getDevicesStatic failed: " + t);
        }
        return null;
    }

    private static void setMicMute(Object svc, boolean on) {
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals("setMicrophoneMute")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];
            for (int i = 0; i < p.length; i++) {
                if (p[i] == boolean.class) a[i] = on;
                else if (p[i] == int.class) a[i] = 0; // userId
                else if (p[i] == String.class) a[i] = (i == 1 ? "com.android.shell" : null);
            }
            try {
                m.invoke(svc, a);
                log("setMicrophoneMute(" + on + ") ok " + java.util.Arrays.toString(p) + " -> isMicrophoneMuted=" + invoke(svc, "isMicrophoneMuted"));
            } catch (Throwable t) {
                log("setMicrophoneMute failed: " + (t.getCause() != null ? t.getCause() : t));
            }
            return;
        }
    }

    private static final android.os.Binder MODE_TOKEN = new android.os.Binder();

    private static Object audioService() throws Exception {
        Class<?> sm = Class.forName("android.os.ServiceManager");
        android.os.IBinder b = (android.os.IBinder) sm.getMethod("getService", String.class).invoke(null, "audio");
        return Class.forName("android.media.IAudioService$Stub").getMethod("asInterface", android.os.IBinder.class).invoke(null, b);
    }

    private static Object invoke(Object o, String name) {
        try {
            for (Method m : o.getClass().getMethods())
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m.invoke(o);
            return "n/a";
        } catch (Throwable t) {
            return "err:" + (t.getCause() != null ? t.getCause() : t);
        }
    }

    private static void setMode(Object svc, int mode) {
        for (Method m : svc.getClass().getMethods()) {
            if (!m.getName().equals("setMode")) continue;
            Class<?>[] p = m.getParameterTypes();
            Object[] a = new Object[p.length];
            for (int i = 0; i < p.length; i++) {
                if (p[i] == int.class) a[i] = mode;
                else if (p[i] == String.class) a[i] = "com.android.shell";
                else if (android.os.IBinder.class.isAssignableFrom(p[i])) a[i] = MODE_TOKEN;
            }
            try {
                m.invoke(svc, a);
                log("setMode invoked " + java.util.Arrays.toString(p));
            } catch (Throwable t) {
                log("setMode failed: " + (t.getCause() != null ? t.getCause() : t));
            }
            return;
        }
        log("setMode not found");
    }

    private static String describe(AudioTrack t) {
        try {
            android.media.AudioDeviceInfo d = t.getRoutedDevice();
            return d == null ? "null" : (d.getType() + ":" + d.getProductName());
        } catch (Throwable e) {
            return "?" + e;
        }
    }

    private static int findData(byte[] w) {
        for (int i = 12; i < w.length - 8; ) {
            String id = new String(w, i, 4);
            int sz = le32(w, i + 4);
            if (id.equals("data")) return i + 8;
            i += 8 + sz;
        }
        return 44;
    }

    private static int le16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static int le32(byte[] b, int o) { return le16(b, o) | le16(b, o + 2) << 16; }

    private static byte[] readAll(String p) throws Exception {
        try (InputStream in = new FileInputStream(p)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static void log(String s) { System.out.println("[inject] " + s); }
}
