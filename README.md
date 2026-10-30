# Transcribe

Speech-to-text dictation for Android that runs **entirely on the phone**. The app has no
internet permission, so Android itself guarantees your audio and text can't leave the device.

It uses NVIDIA's open [Parakeet](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2) speech
model through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx).

**Status:** phase 1, a test app: tap the mic, talk, tap again and get the text. The floating
bubble that types into any app comes in phase 2. See [docs/PLAN.md](docs/PLAN.md).

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
4. Tap the mic and talk.

Later builds install over the top; the model stays put.

### Other models

Any sherpa-onnx **transducer** model archive works. For example,
[Parakeet TDT 0.6B v3](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2)
covers 25 European languages. Use **Replace model** in the app.

## How it's built

- `core/`: plain Kotlin logic (model unpacking, audio helpers) with unit tests that run on
  any computer: `./gradlew :core:test`.
- `app/`: the Android app (Jetpack Compose). `./gradlew :app:assembleRelease` builds the APK.
  The sherpa-onnx library is downloaded from its GitHub release at build time.
- `.github/workflows/build.yml`: every push builds the APK on GitHub, fails the build if the
  APK requests any network permission, and (on pushes) updates the **Latest build** release.

### Signing

Android only installs an update if it's signed with the same key as the installed app. CI
signs builds with a key stored in the `SIGNING_KEYSTORE_BASE64` repository secret (a
base64-encoded PKCS#12 keystore, alias `transcribe`). Builds without the secret use a
throwaway key and aren't published. Keep a private backup of the keystore: if it's lost,
you'll need to uninstall the app (and re-import the model) to switch to a new key.
