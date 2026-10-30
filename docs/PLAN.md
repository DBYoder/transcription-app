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
4. **AI polish and reformatting** (next): a small instruction-tuned model running on the
   phone's GPU via Google's LiteRT-LM (published figures for Gemma 3 1B on a recent flagship:
   1,000+ tokens/s to read, 25–45 tokens/s to write, so roughly 2–4 s for a typical dictation).
   - *Polish* toggle: handles what rules can't — self-corrections ("Tuesday, no wait,
     Wednesday" → "Wednesday"), grammar, punctuation, paragraphs, and turning spoken lists into
     bullet points.
   - *Reformat* one-tap actions on a dictation: bullet list, email, shorter, more formal,
     friendlier.
   - Later, *command mode*: select text, tap the bubble, and say what to do with it ("make this
     a bulleted list").
   - Model choice (Gemma 3 1B vs. Qwen ~1.5B) to be settled by benchmarking on the phone.
     It's a second, optional download (~0.5–1 GB) imported the same way as the speech model.
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
