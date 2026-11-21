# Contributing to Afterbeep

Thanks for helping! The most valuable contributions right now:

1. **Device reports.** Does it work on your phone? Open a *Device report* issue with your model, Android/OEM
   version and the results of a test call (greeting heard? your phone silent? message saved?). Attach
   `engine.log` and `startup.log` from `Android/data/dev.afterbeep/files/` (phone numbers in them are masked).
2. **Bug fixes** with a clear description of the symptom and the log lines that show it.
3. **Features** from the roadmap — open an issue first so we can agree on the approach.

## Development

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- JDK 17+, Android SDK platform `android-37.2`.
- UI is Jetpack Compose (Material 3, custom dark theme in `ui/theme`).
- The engine (`engine/Engine.java`) is plain Java started by `app_process` as the shell user — it can't use
  Android `Context` APIs normally and talks to the app only through files in the app's external files dir and
  explicit broadcasts. Keep it dependency-free.
- Anything that touches call audio must preserve both privacy rules: the caller never hears the user's mic, and
  nobody near the phone hears the caller. Test with a real call.
- Never play audio on a test phone outside a call without warning the person holding it.

## Pull requests

- One topic per PR, with a short description of what you tested (device + result).
- Keep the engine's log messages informative — they're how remote problems get debugged.
- By contributing you agree your work is released under the [MIT License](LICENSE).

## Code of conduct

Be kind. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
