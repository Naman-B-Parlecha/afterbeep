#!/usr/bin/env bash
# Spike: wait for each incoming call, let it ring RING_SECS, then hand the whole call to the
# on-device Voicemail process (answer, greet, record, hang up). Pulls each message back as WAV.
set -u
DEV="${DEV:-<phone-ip>:<port>}"
RING_SECS="${RING_SECS:-4}"
MAX_SECS="${MAX_SECS:-60}"
AUDIO_MODE="${AUDIO_MODE:-4}"
OUT="${OUT:-$(cd "$(dirname "$0")" && pwd)/out}"
mkdir -p "$OUT"
A="adb -s $DEV"
call_state() { $A shell dumpsys telephony.registry | awk -F= '/mCallState=/{print $2; exit}' | tr -d '\r '; }

n=0
while true; do
  n=$((n+1))
  echo "=== call #$n — waiting (audio mode $AUDIO_MODE), don't touch the phone"
  while [ "$(call_state)" != "1" ]; do sleep 0.5; done
  echo "ringing, answering in ${RING_SECS}s"; sleep "$RING_SECS"
  s="vm-$(date +%H%M%S)"
  $A shell "CLASSPATH=/data/local/tmp/spike.jar app_process / Voicemail /data/local/tmp/greeting.wav /data/local/tmp/$s.pcm $MAX_SECS $AUDIO_MODE 1" 2>&1
  $A pull "/data/local/tmp/$s.pcm" "$OUT/$s.pcm" >/dev/null 2>&1 && $A shell rm "/data/local/tmp/$s.pcm"
  ffmpeg -hide_banner -loglevel error -y -f s16le -ar 16000 -ac 1 -i "$OUT/$s.pcm" "$OUT/$s.wav"
  ffmpeg -hide_banner -loglevel error -y -i "$OUT/$s.wav" -af "loudnorm=I=-16:TP=-1.5" -ar 48000 "$OUT/$s-loud.wav"
  echo "saved: $OUT/$s-loud.wav"
  sleep 2
done
