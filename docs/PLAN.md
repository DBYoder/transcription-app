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
| Tidy-up and formatting | Instant rule-based filter, plus optional on-device AI buttons | Gemini Nano doesn't appear to be offered to apps on the 10a, so the user imports Gemma 4 E2B |
| Model delivery | User downloads the model in the browser and imports it | The app has **no internet permission**; CI enforces this |
| Install | GitHub Actions builds a signed APK on every push | One stable link: Releases → Latest build |

## Phases

1. **Test app** (done): a big mic button that records, transcribes and shows the text, with
   timing stats. On the Pixel 10a: 21.6 s of speech transcribed in 0.96 s (~22× real time).
2. **The bubble** (done, awaiting on-phone testing): accessibility service that shows the
   bubble while the keyboard is up (not in password fields), positioned above the keyboard
   and draggable; records directly (Android binds accessibility services with the system's
   foreground capabilities, so no foreground service or notification is needed); saved history
   of recent dictations; the model loads on first use and unloads after 10 idle minutes.
   Tested on the Pixel 10a: the bubble appears and transcribes correctly. First build fell back
   to the clipboard instead of typing, so insertion now types through Android 13's
   accessibility input method (like a keyboard, no clipboard), checks the text box actually
   changed, then falls back to paste, set-text, and finally the clipboard. Each dictation
   records which method worked.
3. **Instant tidy-up** (done): rule-based, no AI, no delay (`core/TextCleaner`). Removes
   um/uh/hmm with the punctuation around them, collapses stutters ("I I think"), drops
   comma-wrapped fillers (", you know,", ", like,"), and turns a spoken "new line" / "new
   paragraph" into line breaks. On by default; the original is kept in history.
   - **Spoken punctuation** (added after testing, `core/SpokenPunctuation`): "comma", "period" /
     "full stop", "question mark", "exclamation point/mark", "colon", "semicolon", "ellipsis",
     "open/close quote" and "open/close paren" become the marks themselves, replacing the speech
     model's own guess at that spot and re-casing the next word. "Period" and "colon" stay words
     after cues like "the", "a" or "trial" ("the trial period", "a period of time").
4. **AI formatting buttons** (done; tested on the Pixel 10a: Polish and Bullets work well,
   speed is good, in-place replacement works; Email was reworded to keep the speaker's voice): Polish, Bullets, Email and
   Shorter. They appear beside the bubble for a few seconds after a dictation and rewrite
   what was just typed in place (with Undo); the same buttons are on every dictation in the
   app, where the result is added to history and copied. Runs Google's Gemma 4 E2B
   (`litert-community/gemma-4-E2B-it-litert-lm`, ~2.6 GB, Apache 2.0) through LiteRT-LM on
   the GPU, falling back to the CPU. Imported like the speech model (the app still has no
   internet access). Loads on first use, unloads after 5 idle minutes; pre-loads after a
   dictation only if a button was used in the last day.
   - **Prompt** (added after testing): turns spoken instructions into a structured prompt for an
     AI assistant: the task first, then Context / Requirements / Output sections only where the
     dictation covers them, with Markdown "- " bullets that chat apps render as lists.
   - Next: *command mode* (select text, tap the bubble, say what to do with it) and
     per-app defaults (e.g. always offer Email first in Gmail).
5. **Polish the bubble**: hold-to-talk, settings, and voice activity detection so long
   dictations transcribe in chunks while you talk (keeping the wait short and removing the
   3-minute limit).

## Known caveats

- Android hides the accessibility switch for apps installed outside the Play Store until you
  go to **App info → ⋮ → Allow restricted settings**.
- Turn on only "Use Transcribe" in accessibility settings. "Transcribe shortcut" adds Android's
  own floating accessibility button (the app icon, on screen everywhere), which isn't needed;
  the app warns when it's on.
- Battery optimisation can switch the service off; setting the app's battery use to
  "Unrestricted" may be needed.
- Google's developer verification for sideloaded apps is planned to reach the US in 2027.
  Personal apps should still be installable via a free hobbyist account or the "advanced"
  install flow.
