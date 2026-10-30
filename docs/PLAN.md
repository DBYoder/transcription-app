# Plan

Goal: a Wispr Flow-style dictation tool for a Pixel 10a (Tensor G4, 8 GB RAM) that runs
100% on the phone and works alongside Gboard rather than replacing it.

## How it will work

1. Tap into any text box. Gboard opens as usual, and a small mic bubble appears just above it.
2. Tap (or hold) the bubble and talk.
3. Tap again (or let go). About a second later the text appears at the cursor.

## Building blocks

| Piece | Choice | Notes |
|---|---|---|
| Speech to text | NVIDIA Parakeet TDT 0.6B (int8) via sherpa-onnx | Adds punctuation and capitals itself; ~1 s for a typical sentence on a recent phone |
| Floating bubble + inserting text | Android accessibility service | Same approach as Wispr Flow on Android. Draws the bubble without the "display over other apps" permission. Skips password fields. Pasting puts the dictation on the clipboard too |
| Tidy-up | Filler-word filter first; small on-device LLM later (optional) | Gemini Nano doesn't appear to be offered to apps on the 10a, so we'd bundle a ~1B model |
| Model delivery | User downloads the model in the browser and imports it | The app has **no internet permission**; CI enforces this |
| Install | GitHub Actions builds a signed APK on every push | One stable link: Releases → Latest build |

## Phases

1. **Test app** (done): a big mic button that records, transcribes and shows the text, with
   timing stats. On the Pixel 10a: 21.6 s of speech transcribed in 0.96 s (~22× real time).
2. **The bubble** (done, awaiting on-phone testing): accessibility service that shows the
   bubble while the keyboard is up (not in password fields), positioned above the keyboard
   and draggable; records directly (Android binds accessibility services with the system's
   foreground capabilities, so no foreground service or notification is needed); inserts at
   the cursor (paste, falling back to set-text, then clipboard); saved history of recent
   dictations; the model loads on first use and unloads after 10 idle minutes.
3. **Polish**: filler-word removal, hold-to-talk, drag to reposition, settings, and voice
   activity detection so long dictations transcribe in chunks while you talk (keeping the wait
   short and removing the 3-minute limit).
4. **Optional AI tidy-up**: a small on-device model (e.g. Gemma ~1B), benchmarked on the
   phone and offered as a toggle.

## Known caveats

- Android hides the accessibility switch for apps installed outside the Play Store until you
  go to **App info → ⋮ → Allow restricted settings**.
- Battery optimisation can switch the service off; setting the app's battery use to
  "Unrestricted" may be needed.
- Google's developer verification for sideloaded apps is planned to reach the US in 2027.
  Personal apps should still be installable via a free hobbyist account or the "advanced"
  install flow.
