# Voice call UX and auto-read regression
Memory landing policy: preauthorized
- User wants speech to send automatically, live streamed text, speech interruption, and functioning auto-read.
- Base a5e1b8ed includes local OTA work. Create separate feature/voice-call branch without publishing.
- Existing manual dictation, background read-aloud, roleplay persistence and OTA must remain unchanged except targeted auto-read race fix and opt-in audio sharing. No broad permissions.
- Implement call-only endpoint detection, distinct duplex audio ownership, echo filtering, sentence-level TTS and cancel/queue on interruption. Don't claim real device latency or AEC accuracy without testing.

## Result
- Call flow supports automatic endpoint for both streaming and file ASR, live chat text, incremental sentence TTS, and speech interruption with echo filtering and a manual fallback.
- Fixed ordinary auto-read for replies too fast to expose a streaming placeholder. Call reports TTS configuration/engine errors and continues as text, without overriding the selected provider.
- Regression tests include an instrumented fake microphone -> silence -> MiMo-shaped file ASR result, scoped duplex audio ownership, streaming TTS sentence selection/tails, echo window and fast-reply autoread.
- Fixed an Android lint UAST crash by moving an internal call-state class out of the composable source and not declaring its property in the constructor, without any lint exclusions. Test fake delivers speech and silence on separate simulated frames.
- Final Debug build and lint passed, 88 targeted unit tests passed. No connected Android device: hardware AEC, interruption timing, and live provider playback still require phone validation. No push or publication.
