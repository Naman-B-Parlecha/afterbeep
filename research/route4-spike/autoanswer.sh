#!/usr/bin/env bash
# Spike: wait for incoming calls, auto-answer after RING_SECS, inject greeting into the call,
# record caller (downlink) + what we send (uplink). Loops over calls; AUDIO_MODES cycles per call.
#   VARIANTS="4:telephony:1 5:telephony:1"  -> per call  <audioMode>:<route>:<micMute>  (cycles)
set -u
DEV="${DEV:-<phone-ip>:<port>}"
RING_SECS="${RING_SECS:-4}"
REC_SECS="${REC_SECS:-25}"
read -r -a MODES <<< "${VARIANTS:-4:telephony:1 5:telephony:1 -1:telephony:1}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/out}"
mkdir -p "$OUT"
A="adb -s $DEV"
JAR="CLASSPATH=/data/local/tmp/spike.jar app_process /"

call_state() { $A shell dumpsys telephony.registry | awk -F= '/mCallState=/{print $2; exit}' | tr -d '\r '; }
rec() { # source file
  scrcpy -s "$DEV" --no-video --no-playback --no-window --audio-source="$1" \
    --audio-codec=aac --record="$2" --time-limit="$REC_SECS" >"$2.log" 2>&1 &
}

n=0
while true; do
  mode="${MODES[$((n % ${#MODES[@]}))]}"; n=$((n+1))
  IFS=: read -r amode route mute <<< "$mode"
  echo "=== call #$n (audio mode $amode, route $route, micMute $mute) — waiting for incoming call, don't touch the phone"
  while [ "$(call_state)" != "1" ]; do sleep 0.5; done
  echo "ringing! answering in ${RING_SECS}s"; sleep "$RING_SECS"
  $A shell "$JAR Answer" 2>&1
  sleep 1.5
  if [ "$(call_state)" != "2" ]; then
    echo "telecom answer didn't take, trying KEYCODE_CALL"; $A shell input keyevent KEYCODE_CALL; sleep 1.5
  fi
  echo "call state now: $(call_state) (2 = offhook)"

  s="$(date +%H%M%S)-m${amode}-${route}-mute${mute}"
  rec voice-call-downlink "$OUT/caller-$s.m4a"; R1=$!
  rec voice-call-uplink "$OUT/uplink-$s.m4a"; R2=$!
  sleep 1
  ( sleep 3; $A shell dumpsys media.audio_flinger > "$OUT/flinger-$s.txt" 2>&1 ) &
  $A shell "$JAR Inject /data/local/tmp/greeting.wav $route $amode $mute" 2>&1
  echo "greeting done, recording caller..."
  wait $R1 $R2
  [ "$(call_state)" = "2" ] && { echo "hanging up"; $A shell input keyevent KEYCODE_ENDCALL; }
  $A shell "$JAR Inject unmute" 2>&1
  for f in caller uplink; do
    ffmpeg -hide_banner -loglevel error -y -i "$OUT/$f-$s.m4a" -af "loudnorm=I=-16:TP=-1.5" -ar 48000 "$OUT/$f-$s-loud.wav" 2>/dev/null
  done
  echo "saved: $OUT/caller-$s-loud.wav  $OUT/uplink-$s-loud.wav"
  sleep 2
done
