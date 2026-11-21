package dev.vm.route5;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.InCallService;
import android.telecom.VideoProfile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Route 6: ORDINARY installed app, no shell at runtime.
 * Holds SYSTEM_CALL_STREAMING role (granted once) -> CALL_AUDIO_INTERCEPTION, plus default-dialer role.
 *   ring 4s -> enterBackgroundAudioProcessing (private answer, MODE_CALL_SCREENING)
 *   -> greeting via AudioManager.getCallUplinkInjectionAudioTrack
 *   -> record caller via AudioManager.getCallDownlinkExtractionAudioRecord until hang-up / 60 s
 */
public class InCallSvc extends InCallService {
    static final String[] RX_MUTE_ON = {"OPLUS_VC_DOWNLINK_MUTE_MODE=enable", "device_mute=true;direction=rx", "voice_translation_rx_mute=true"};
    static final String[] RX_MUTE_OFF = {"OPLUS_VC_DOWNLINK_MUTE_MODE=disable", "device_mute=false;direction=rx", "voice_translation_rx_mute=false"};

    final Handler main = new Handler(Looper.getMainLooper());
    volatile Call current;

    void log(String m) { L.i(this, m); }

    @Override public void onCallAdded(Call call) {
        log("onCallAdded state=" + call.getDetails().getState());
        if (call.getDetails().getCallDirection() != Call.Details.DIRECTION_INCOMING) return;
        current = call;
        final boolean[] started = {false};
        call.registerCallback(new Call.Callback() {
            @Override public void onStateChanged(Call c, int state) {
                log("call state -> " + state + " (12=AUDIO_PROCESSING 4=ACTIVE 7=DISCONNECTED)");
                if ((state == Call.STATE_AUDIO_PROCESSING || state == Call.STATE_ACTIVE) && !started[0]) {
                    started[0] = true;
                    new Thread(() -> voicemail(c), "vm").start();
                }
            }
        });
        main.postDelayed(() -> {
            if (call.getState() != Call.STATE_RINGING) return;
            try {
                Call.class.getMethod("enterBackgroundAudioProcessing").invoke(call);
                log("private answer (enterBackgroundAudioProcessing)");
            } catch (Throwable t) {
                log("private answer failed " + t + " -> answer");
                call.answer(VideoProfile.STATE_AUDIO_ONLY);
            }
        }, 4000);
    }

    @Override public void onCallRemoved(Call call) { log("onCallRemoved"); if (call == current) current = null; }
    @Override public void onCallAudioStateChanged(CallAudioState s) { log("callAudioState " + s); }

    void voicemail(Call call) {
        AudioManager am = getSystemService(AudioManager.class);
        try {
            Thread.sleep(600);
            log("mode=" + am.getMode() + " interceptable=" + am.getClass().getMethod("isPstnCallAudioInterceptable").invoke(am));
            am.setMode(5 /* MODE_CALL_REDIRECT */);
            Thread.sleep(300);
            log("after setMode(CALL_REDIRECT): mode=" + am.getMode());
            for (String kv : RX_MUTE_ON) am.setParameters(kv);
            // probe both APIs at two rates before using them
            for (int rate : new int[]{48000, 16000}) {
                try {
                    Object tr = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class).invoke(am,
                            new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build());
                    log("probe injection @" + rate + ": OK"); ((AudioTrack) tr).release();
                } catch (Throwable e) { log("probe injection @" + rate + ": " + (e.getCause() != null ? e.getCause() : e)); }
                try {
                    Object rr = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class).invoke(am,
                            new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build());
                    log("probe extraction @" + rate + ": OK"); ((AudioRecord) rr).release();
                } catch (Throwable e) { log("probe extraction @" + rate + ": " + (e.getCause() != null ? e.getCause() : e)); }
            }
            log("rx mute state: " + am.getParameters("OPLUS_GET_VC_DOWNLINK_MUTE_STATE"));

            // --- greeting into the call (uplink injection) ---
            AudioFormat outFmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            Method inj = AudioManager.class.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat.class);
            AudioTrack t = (AudioTrack) inj.invoke(am, outFmt);
            byte[] wav;
            try (InputStream in = getResources().openRawResource(R.raw.greeting)) { wav = in.readAllBytes(); }
            int off = 44, len = wav.length - off;
            t.play();
            for (int w = 0; w < len; ) { int n = t.write(wav, off + w, Math.min(8192, len - w)); if (n < 0) { log("write err " + n); break; } w += n; }
            long until = System.currentTimeMillis() + 10000;
            while (t.getPlaybackHeadPosition() < len / 2 - 10 && System.currentTimeMillis() < until) Thread.sleep(40);
            log("greeting head=" + t.getPlaybackHeadPosition() + "/" + (len / 2) + " routed=" + (t.getRoutedDevice() == null ? "null" : t.getRoutedDevice().getType()));
            t.stop(); t.release();

            // --- record the caller (downlink extraction) ---
            AudioFormat inFmt = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            Method ext = AudioManager.class.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat.class);
            AudioRecord r = (AudioRecord) ext.invoke(am, inFmt);
            File f = new File(getExternalFilesDir(null), "vm6-" + new SimpleDateFormat("HHmmss", Locale.US).format(new Date()) + ".pcm");
            r.startRecording();
            log("recording state=" + r.getRecordingState() + " -> " + f.getName());
            byte[] b = new byte[3200];
            double peak = 0; long bytes = 0, end = System.currentTimeMillis() + 60000;
            try (FileOutputStream o = new FileOutputStream(f)) {
                while (System.currentTimeMillis() < end && current == call) {
                    int n = r.read(b, 0, b.length);
                    if (n < 0) { log("read err " + n); break; }
                    o.write(b, 0, n); bytes += n;
                    for (int i = 0; i + 1 < n; i += 2) peak = Math.max(peak, Math.abs((short) ((b[i] & 0xff) | (b[i + 1] << 8))));
                }
            }
            r.stop(); r.release();
            log(String.format(Locale.US, "recorded %.1fs peak=%.1f dBFS", bytes / 32000.0, 20 * Math.log10(Math.max(peak, 1) / 32768.0)));
        } catch (Throwable e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            log("voicemail failed: " + c);
            try { long e2 = System.currentTimeMillis() + 15000; while (current == call && System.currentTimeMillis() < e2) Thread.sleep(200); } catch (InterruptedException ignored) {}
        } finally {
            for (String kv : RX_MUTE_OFF) am.setParameters(kv);
            am.setMode(AudioManager.MODE_NORMAL);
            if (current == call) { log("disconnect"); call.disconnect(); }
        }
    }
}
