# Android on-device voice
Memory landing policy: preauthorized
- User explicitly requests Android on-device recognition + system offline TTS, selectable voice, small APK, no separate model/API service.
- Start from 2b740a1a with previous call mode and online update intact. Branch locally; no push/install/publication.
- Use createOnDeviceSpeechRecognizer only (no generic/default recognition fallback). Local ASR bypasses provider credentials, cloud transcribers and model transcript refinement. Native recognizer controls end-of-speech; turn handling stays existing.
- Use Android TextToSpeech with installed, non-network voices only. Voice selector and existing preview use the same strict validation; missing voice/service surfaces clear error. No packaged models or new app permissions.
- Preserve existing cloud ASR/TTS, ordinary hold-to-talk, streaming text and familiar audio lease behavior; verify tests/build/lint. Hardware/engine limitations need real phone acceptance.

## Result
- Implemented the one-tap local preset, system recognition language setting, installed-offline voice picker and native preview/playback. Android recognition and TTS use isolated code paths and never fall back to cloud. Cloud settings are preserved.
- Final build, 197 targeted tests (8 new offline tests), Android Lint and APK signing check passed. Compared the APK against the previous call build: no new permissions, model assets or native libraries.
- No Android device was connected. Actual device packs, engine latency, sound, and barge-in in airplane mode remain for phone acceptance; the chat model may still require network.
- No remote push, release, or install performed.
