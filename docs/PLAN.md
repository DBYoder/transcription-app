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
   - **Choosing buttons and closing them** (added after testing): the AI formatting card has a
     checklist of which buttons pop up beside the bubble (none ticked means no pop-up; every
     button stays on each dictation in the app). The pop-up has a ✕ at the end farthest from the
     bubble, so closing it never starts a dictation by mistake.
   - **Command mode** (done, awaiting on-phone testing): select text and the bubble turns purple
     (it listens for selection changes). Tap or hold it and say what to do; the instruction is
     transcribed, Gemma rewrites the selection (`core/EditCommand` prompt), and the result
     replaces the selection, with Undo and the formatting buttons offered as usual. Can be
     switched off in the AI card to dictate over a selection instead.
   - Next: per-app defaults (e.g. always offer Email first in Gmail).
5. **Polish the bubble** (done, awaiting on-phone testing):
   - **Hold-to-talk**: hold the bubble (Android's touch-and-hold delay), speak, let go. Taps and
     drags work as before.
   - **Long dictations**: the recording is cut at natural pauses as it comes in
     (`core/SpeechChunker`: after 10 s of speech, a 0.6 s pause, or 0.3 s after 20 s, and never
     more than 30 s, cutting at the quietest moment). Each piece is transcribed while you keep
     talking, so the wait at the end is at most about a second however long you talk.
     Dictations under 10 s are transcribed in one go, exactly as before. "Quiet" is relative to
     the room's background level and your voice, so it adapts to noise. The limit is now 30
     minutes (a safety stop). History shows "N s wait" when it was much less than the work.

6. **From competitor research** (done, awaiting on-phone testing; Wispr Flow, Superwhisper,
   Willow, Typeless and Gboard compared):
   - **Your words** (`core/PersonalDictionary`): names and terms spelled your way, with what the
     speech model writes instead. Fixed after every dictation (whole words, any case; words with
     unusual capitals like "iPhone" also get their capitals fixed). "Fix a word" on any
     dictation in the app adds one. With words added, the speech model also *listens out* for
     them (sherpa-onnx hotwords, via a small beam search, slightly slower; can be switched off).
     That needs the model's word-piece list (bpe.vocab), which newer model downloads include and
     older ones get rebuilt from tokens.txt (`core/BpeVocab`).
   - **Names from your contacts** (added after testing: adding names one by one was too much
     work): with the contacts permission, first/middle/last names (`StructuredName`, starred
     contacts first, up to 2,000) are listened for alongside your own words.
     `core/ContactNameFilter` leaves out titles, family labels, business words and names that are
     also everyday words ("Will", "Grace"), which would bend ordinary speech towards names; names
     the model can't spell (characters missing from its word pieces) are skipped too. Contacts are
     re-read when they change (or every 30 minutes) and only kept in memory.
   - **Snippets** (`core/Snippets`): "insert my email" anywhere, or just "my email" as the whole
     dictation, types the saved text.
   - **Voice commands** (`core/VoiceCommands`, part of the tidy-up): "scratch that" deletes the
     sentence before it (said first, it takes back the previous dictation from the text box);
     "<name> emoji" types the emoji; ending with "send" (or "press enter") sends the message.
     - *Fixed after testing ("send" didn't send):* "send" no longer needs a pause before it,
       unless the word before makes it part of the sentence ("I'll send", "can you send"). The
       Send button is now found by walking the screen's contents, because apps built with
       Jetpack Compose (like Google Messages) don't answer Android's quick text search. It tries
       the app's Send button first, then the text box's Send/Go/Search action, then Enter
       (single-line boxes only), and checks the text box emptied after each before moving on.
   - **Auto-polish** (AI card): runs Polish on every bubble dictation of 4 to 300 words before
     typing it, which handles self-corrections like Wispr Flow does. Undo restores the exact
     words; if the AI fails or its reply looks cut short, the dictation is typed as said.

7. **App layout** (done): the settings had grown into one long scrolling page, so the app now
   has four pages on a bottom bar. **Dictate** shows the next setup step until everything
   works (then a short "how to use the bubble" card), a mic to try it, and recent dictations.
   **Words** has contact names, your own words and snippets. **AI** has the AI model, the
   buttons beside the bubble, command mode and Auto-polish. **Settings** has the tidy-up
   switch, a list of everything you can say, the bubble, the speech model and an About card.

8. **Journal** ("second brain"; steps 1 and 2 done, awaiting on-phone testing):
   - Entries dictated on the Journal page (long ones are fine, with the usual tidy-up and
     Auto-polish) or typed, or saved from any dictation with "Save to journal".
   - One Markdown file per day (`core/JournalMarkdown`): front matter (date, tags), a title,
     then `## 9:14 AM · Title` sections with an optional `#tags` line. Reading is forgiving and
     keeps anything other apps add (front matter properties, notes before the first entry,
     hand-written sections), and each change re-reads the day's file first.
   - Kept in a folder you pick with Android's folder picker (Storage Access Framework, so
     Obsidian or a sync app can use the files, with no storage permission), or inside the app.
     Switching copies the days over.
   - Keyword search (`core/JournalSearch`: stop words dropped, crude stemming, rarer words and
     title/tag matches ranked higher).
   - "Ask your journal" (`core/JournalAsk`): picks entries by any dates in the question ("last
     week", "in October", "yesterday") and by keywords, up to what Gemma reads at once (about
     7,000 characters), and has it answer from them, citing dates.
   - AI titles and tags after each entry is saved (`core/EntryLabels`), so it's never held up.
   - No sharing built in (removed after testing: not wanted). The Markdown files in the
     chosen folder can be opened, copied or shared with any app. The app has no internet access.
   - Next (step 3): search by meaning with an on-device embedding model.

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
