# ADR 0001 — On-device voicemail uses a shell-uid helper (route 4), not a plain app (route 5)

- **Status:** Accepted — 2026-11-21
- **Device:** OnePlus 13s (CPH2723), OxygenOS on Android 16 (SDK 36), Snapdragon 8 Elite, dual SIM (Vodafone Idea + Jio)
- **Scope:** Personal use on this one phone. No cross-device support required.

## Context

Goal: when I don't pick up, the phone itself answers, plays my greeting **to the caller**, records the caller, and keeps it in an inbox — with two privacy rules:

1. The caller must never hear my microphone / surroundings.
2. Nobody near my phone may hear the caller.

No carrier visual voicemail and no mandatory cloud. (An opt-in cloud number for "phone switched off" is a separate, later feature.)

## Options considered

| Route | What it is |
|---|---|
| **4 — shell-uid helper** | App starts a helper process as the ADB shell user (uid 2000) via the phone's own wireless debugging (self-paired, JemRec-style, or Shizuku). The helper does the call audio work. |
| **5 — plain app** | Ordinary installed app with only normal/runtime permissions (Play-Store-call-recorder style), optionally as default dialer and/or with roles. |
| Root (Magisk priv-app) | App installed as a privileged system app. Rejected: unlocking the bootloader wipes the phone and banking apps may break. Kept as a possible future upgrade — same engine code. |
| Carrier forwarding to cloud | `**61` / `**62` to Twilio or Truecaller Assistant. Not on-device; kept only for the phone-off case. |

## Decision

**Route 4.** It is the only no-root option that meets every requirement on this phone. Route 5 was tested exhaustively and cannot put a spoken greeting into the call.

## Route 4 — validated end to end (2026-11-21)

Shell uid on this device holds `CAPTURE_AUDIO_OUTPUT`, `CALL_AUDIO_INTERCEPTION`, `MODIFY_PHONE_STATE`, `MODIFY_AUDIO_ROUTING`, `ANSWER_PHONE_CALLS`. With those, from one long-lived process (`research/route4-research/route4-spike/src/Voicemail.java`):

| Step | API | Result |
|---|---|---|
| Answer | `ITelecomService.acceptRingingCall` | ✅ |
| Keep my mic out | `IAudioService.setMicrophoneMute(true)` + `setMode(MODE_CALL_SCREENING)` held by a Binder token for the whole call | ✅ caller heard nothing from my side |
| Greeting into the call | `AudioTrack` `USAGE_VOICE_COMMUNICATION` + `setPreferredDevice(TYPE_TELEPHONY)` → lands on the HAL's `in_call_music` stream | ✅ caller heard greeting + beep |
| Record caller | `AudioRecord` `VOICE_DOWNLINK` (shell attribution) | ✅ clean, ~-7 to -10 dBFS peak |
| Silence the caller on my phone | voice stream `ADJUST_MUTE` + OnePlus HAL params `OPLUS_VC_DOWNLINK_MUTE_MODE=enable`, `device_mute=true;direction=rx`, `voice_translation_rx_mute=true` | ✅ earpiece silent, recording unaffected |
| Detect hang-up | `ITelecomService.isInCall` | ✅ |

Trade-off accepted: after every reboot the helper must be restarted, which needs wireless debugging on for a few seconds (automated, on home Wi-Fi only, then switched off). This is how JemRec, Shizuku apps and similar tools work.

## Why not route 5 — evidence

Every result below is from live calls on this phone with a plain installed test app (`route5/`), not theory.

### 5a. Plain app (normal + runtime permissions, accessibility service enabled)

| Check | Result | Reason |
|---|---|---|
| Detect ringing | ⚠️ `TelephonyCallback` never fired on OxygenOS (even per-subscription); only the legacy `PHONE_STATE` broadcast worked | OEM filtering |
| Auto-answer (`TelecomManager.acceptRingingCall`) | ✅ | public API |
| Speakerphone for greeting | ❌ `setCommunicationDevice(speaker)` returned true but route stayed earpiece | only the dialer controls cellular call routing |
| Greeting reaches caller | ❌ played locally only | no uplink access; acoustic path removed by echo cancellation |
| Caller can't hear my surroundings | ❌ caller heard their own echo | mic can't be muted without killing the app's own recording |
| Record caller | ⚠️ mic only (picks up earpiece leakage) | `VOICE_DOWNLINK` not available |

### 5b. Plain app as **default dialer** (InCallService)

| Check | Result | Reason |
|---|---|---|
| Speakerphone (`setAudioRoute(ROUTE_SPEAKER)`) | ✅ works | dialer can route |
| Greeting via speaker reaches caller | ❌ heard loudly on my phone, caller heard nothing | modem/DSP echo canceller subtracts the phone's own playback |
| Greeting via `TYPE_TELEPHONY` preferred device | ❌ rerouted to earpiece, 0 frames played (`write err -4`) | audio policy denies telephony TX |
| `USAGE_CALL_ASSISTANT` track | ❌ `Cannot create AudioTrack` | system usage, privileged only |
| Private answer (`Call.enterBackgroundAudioProcessing`, hidden API via reflection) | ✅ call state `AUDIO_PROCESSING`, Telecom set `MODE_CALL_SCREENING` | — |
| DTMF to caller (`Call.playDtmfTone`) | ✅ caller heard tones | modem-generated, bypasses audio policy and AEC |
| Deflect / transfer to a cloud number | ❌ capabilities `false` on both SIMs | carrier doesn't offer it |

### 5c. Plain app + roles (granted once via `cmd role set-bypassing-role-qualification`)

Searched the device's `roles.xml` (from GooglePermissionController) for every role granting `CALL_AUDIO_INTERCEPTION`, `CAPTURE_AUDIO_OUTPUT` or `MODIFY_PHONE_STATE`:

| Role | Flags | Grants | Assignable? |
|---|---|---|---|
| `SYSTEM_CALL_STREAMING` | systemOnly, static | `CALL_AUDIO_INTERCEPTION`, `RECORD_AUDIO` | ✅ **only with the test bypass**; wiped on every app reinstall/update |
| `SYSTEM_AUDIO_INTELLIGENCE` | systemOnly, static | `MODIFY_PHONE_STATE`, `CAPTURE_AUDIO_OUTPUT`, … | ❌ refused even with bypass |
| `SYSTEM_AMBIENT_AUDIO_INTELLIGENCE` | systemOnly, static | `CAPTURE_AUDIO_OUTPUT` | ❌ (not tried; same class) |
| `RETAIL_DEMO` | static | `MODIFY_PHONE_STATE` | ❌ refused even with bypass |

With `SYSTEM_CALL_STREAMING` held (plus default dialer):

| Check | Result |
|---|---|
| Record caller via `AudioManager.getCallDownlinkExtractionAudioRecord` | ✅ OK at 16 kHz and 48 kHz |
| Greeting via `AudioManager.getCallUplinkInjectionAudioTrack` | ❌ `Cannot create AudioTrack` |
| `setMode(MODE_CALL_REDIRECT)` | ❌ silently ignored (mode stayed `CALL_SCREENING`) |

**Root cause (AOSP source):**

- `AudioPolicyService::getOutputForAttr`, case `API_OUTPUT_TELEPHONY_TX`:
  ```
  if ((flags & AUDIO_FLAG_CALL_REDIRECTION) && !CALL_AUDIO_INTERCEPTION) → PERMISSION_DENIED
  else if (!MODIFY_PHONE_STATE)                                         → PERMISSION_DENIED
  ```
  It is if/else-if, so **any audio into the call uplink requires `MODIFY_PHONE_STATE`** on the creating app, regardless of `CALL_AUDIO_INTERCEPTION`.
- `AudioService.setMode`: `MODE_IN_CALL`, `MODE_CALL_REDIRECT`, `MODE_COMMUNICATION_REDIRECT` require `MODIFY_PHONE_STATE`; otherwise ignored with a log.
- `MODIFY_PHONE_STATE` is `signature|privileged|role`; the only roles granting it on this device are static (OEM-configured holders only).

### 5d. OnePlus built-in AI Call Assistant (`com.oplus.aicall`, priv-app)

Contains "Answers calls on your behalf and converts text to speech" / 小布通话 (Breeno Call), and call translation that speaks into the call. Its services and settings are protected by the signature permission `com.oplus.permission.safe.PHONE`, so third-party apps can't drive it. The answering feature appears to be China-only; not exposed in this build's UI (to confirm manually in Phone → Settings → AI Call Assistant).

### Conclusion for route 5

A plain app on this phone can at best: answer privately, record the caller cleanly (only with the bypass-granted role, lost on every update), and send DTMF beeps. **It cannot play a spoken greeting to the caller** — that requires `MODIFY_PHONE_STATE`, which is unreachable without root or OEM signing. That fails the core requirement, so route 5 is rejected.

## Consequences

- Build the app around a shell-uid helper started via self-paired wireless debugging (JemRec architecture) or Shizuku; app UI is separate from the helper so OxygenOS battery management can't kill call handling.
- After reboot: helper restarts automatically when on home Wi-Fi (debugging on ~3 s, then off). Until then, incoming calls are not answered (consider DTMF-beep + record fallback using route 5c only if the role ever proves to survive reboots).
- Root/Magisk remains a drop-in upgrade path that removes the reboot step; engine code unchanged.

## References

- JemRec (self-paired wireless debugging, answering machine): https://github.com/jemcik/JemRec
- Shizuku-based cellular SIP gateway (same APIs, shell uid): https://blog.sparktour.me/en/posts/2026/10/10/android-shizuku-cellular-sip-gateway/
- AOSP `AudioPolicyInterfaceImpl.cpp`: https://android.googlesource.com/platform/frameworks/av/+/refs/heads/main/services/audiopolicy/service/AudioPolicyInterfaceImpl.cpp
- AOSP `AudioManager.java` / `AudioService.java` (frameworks/base, main)
- Spike code: `spike/` (route 4), `route5/` (route 5 probes)
