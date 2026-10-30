# Transcribe

Speech-to-text dictation for Android that runs **entirely on the phone**. The app has no
internet permission, so Android itself guarantees your audio and text can't leave the device.

It uses NVIDIA's open [Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) speech
model through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

**Status:** a floating mic bubble appears above your keyboard (Gboard or any other)
in any app: tap it, speak, tap again, and the text is typed into the text box. Dictation is
tidied up instantly (no AI): "um"s and stutters removed, and a spoken "new paragraph" becomes
a line break. On-device AI polishing and reformatting is next; see [docs/PLAN.md](docs/PLAN.md).

## Install on your phone

1. On your phone, open
   **https://github.com/DBYoder/transcription-app/releases/download/latest-build/transcribe.apk**
   (always the newest build) and open the downloaded file.
2. If Android asks, allow your browser to install apps.
3. Open **Transcribe** and follow the two setup steps on screen:
   1. **Download the model.** This opens the model file in your browser
      (`sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2`, several hundred MB).
   2. **Import the downloaded file.** Pick it from Downloads. Unpacking takes a minute or
      two, and you can delete the download afterwards.
4. Turn on the floating bubble from the app's **Turn on the bubble** card:
   1. Allow microphone access.
   2. Switch on **Use Transcribe** in Android's accessibility settings (it may be under
      *Downloaded apps*). If the switch is greyed out or says *Restricted setting*, open
      **App info → ⋮ → Allow restricted settings** and try again. Android requires this
      extra step for accessibility services in apps installed outside the Play Store.
      Leave **Transcribe shortcut** off: it adds Android's own floating button that stays
      on screen everywhere, which the bubble doesn't need.
5. Tap into any text box. The bubble appears above the keyboard. Tap it, speak, tap again.
   Drag the bubble to move it.

Later builds install over the top; the model and settings stay put.

### Why an accessibility service?

It's the only way Android lets an app notice you're typing in another app and put text
into that app's text box. Wispr Flow's Android app uses the same approach. Transcribe only
looks at the focused text box and the keyboard's position, skips password fields, and has no
internet access. Recent dictations are kept on the phone (in the app) so nothing is lost if
inserting fails, and you can clear them anytime.

### Other models

Any sherpa-onnx **transducer** model archive works. For example,
[Parakeet TDT 0.6B v3](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2)
covers 25 European languages. Use **Replace model** in the app.

## How it's built

- `core/`: plain Kotlin logic (model unpacking, audio helpers, spacing when inserting text)
  with unit tests that run on any computer: `./gradlew :core:test`.
- `app/`: the Android app. `./gradlew :app:assembleRelease` builds the APK. The sherpa-onnx
  library is downloaded from its GitHub release at build time.
  - `bubble/`: the accessibility service, the overlay bubble, and text insertion.
  - `speech/SpeechEngine`: loads the model on first use (shared by the bubble and the app
    screen) and frees its memory after 10 idle minutes.
- `.github/workflows/build.yml`: every push builds the APK on GitHub, fails the build if the
  APK requests any network permission, and (on pushes) updates the **Latest build** release.

### Signing

Android only installs an update if it's signed with the same key as the installed app. CI
signs builds with a key stored in the `SIGNING_KEYSTORE_BASE64` repository secret (a
base64-encoded PKCS#12 keystore, alias `transcribe`). Builds without the secret use a
throwaway key and aren't published. Keep a private backup of the keystore: if it's lost,
you'll need to uninstall the app (and re-import the model) to switch to a new key.
