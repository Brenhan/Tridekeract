# Pentadekeract (Android)

A 15-dimensional cube (32,768 corners, 245,760 edges) that rests as a plain 3D cube and unfolds with the music — the native
Android version of the Tridekeract/Pentadekeract Visualizers from the "Multidimensional Reasoning" project.

## Getting the app on your phone
Every push to this repository builds the app on GitHub. When the build finishes (about 5 minutes),
open **Releases** on the repository page and download the newest `Pentadekeract-N.apk`. Android will
ask you to allow installing apps from your browser the first time. Later builds install over the
top, because every build is signed with the same key (`keys/debug.keystore`).

## What it listens to
- **Choose a track** / **Play** — any audio file on the phone.
- **Other apps** — the sound from YouTube, Spotify and others, using Android's playback capture
  (Android 10+). Android shows a "start recording or casting" prompt and a notification while it
  listens. Some apps don't allow capture; those stay silent.
- **Microphone** — music in the room, with the phone's call-style processing turned off where the
  phone allows it.

## Gestures
The cube is turned by its own unfolding: each fold that opens pushes it. One finger turns the cube; two fingers fold through hidden dimensions; pinch zooms; twist rolls; double-tap resets the view; long-press opens
the Feel panel: Step (how much each hit adds), Linger (how long a built-up opening takes to sink
back), Memory, Bloom and Bloom high (how open each dimension aims to be on average, sliding with
how full the music is), Adapt and Depth, plus live meters.

## Code map
- `Viz.kt` — the per-frame behavior, ported line for line from the web page (bands, self-adjusting
  thresholds, tumble, 15D projection).
- `Analyser.kt` — a copy of the browser's AnalyserNode (4096-point FFT, Blackman window, smoothing
  0.55, −100…−30 dB → 0…255), so the web tuning carries over unchanged.
- `Renderer.kt` — OpenGL ES 3 instanced edges, stereo views.
- `AudioEngine.kt` — track decoding/playback (kept in step with the analyser) and microphone.
- `CaptureService.kt` — listening to other apps.
- `MainActivity.kt` — controls, gestures, permissions.
