# Research

Code from the investigation that led to Afterbeep's design. Not part of the app build — kept for reference.
The full write-up is [ADR 0001](../docs/decisions/0001-route-4-shell-helper.md).

- **`route4-spike/`** — the original proof of concept: a single Java program run with `app_process` as the shell
  user that answers a call, keeps the user's mic out, plays a greeting into the call, silences the earpiece and
  records the caller. Afterbeep's `Engine.java` grew out of this. Build with `javac` against `android.jar` + `d8`,
  push to `/data/local/tmp`, run via the scripts (set `DEV=<phone-ip>:<port>`).
- **`route5-plain-app/`** — the experiment proving an ordinary app (even as default dialer, with roles) **cannot**
  put a spoken greeting into a call on this platform. Kept so nobody has to repeat it. Audio test files were
  removed; add your own `res/raw/greeting*.wav` to build it.
