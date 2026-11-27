# Current context
Memory landing policy: preauthorized

## Current continuation: Gitee release channel
- Current branch: feature/gitee-update-source, based on published GitHub v3.4.5 / 59a72e5d. Read [the active Gitee task](tasks/gitee-update-source/active-task.md) first; the earlier sections below preserve previous stages.
- User approved making a962327460/eta public and publishing there. Public Git transport now verifies the three mirrored GitHub branches and v3.4.5 tag; main is still ffd972d6. Do not merge custom features into main.
- Candidate 3.4.7 / 2026112703 defaults to Gitee; build-only provider/repository overrides preserve GitHub support. Gitee requires exact attachment metadata plus one eta-update SHA-256 manifest. Do not embed access tokens, weaken signer checks, or fall back to another channel.
- First migration from 3.4.5 requires manually installing the same-signature new APK (old builds still query GitHub). No handset action is authorized or tested here.

- Upstream baseline and synced origin/main: ffd972d63e36a2500bf58ef38483a1015be54ed9 (78 commits beyond the original fork).
- Feature branch: feature/voice-conversation-links. Do not merge feature behavior into main without the user deciding to do so.
- Direct speech input comes from upstream. Extensions add local-only conversation link continuation and an explicit foreground roleplay voice loop.
- Final local build, Android Lint (0 errors), and 44 targeted tests passed. No real device audio tested.
- Preserve ordinary text chat, existing manual dictation, ordinary background read-aloud, roleplay persistence, and permission policy. Do not change broad permissions to make voice work.
- GitHub deploy-key push of main succeeded. SSH settings are repository-local; secrets live outside the repository and must never be copied into memory or artifacts.
- Before continuing, read active-task.md and ../VOICE.md. Phone acceptance and original-signature production packaging remain separate follow-up work.

## Online upgrade (2026-11-26)
- Separate local branch feature/online-upgrade adds one-click verified download and user-confirmed Android install without editing chat, voice, or local conversation links.
- Read [the online upgrade task](tasks/online-upgrade/active-task.md) and [release/usage requirements](../ONLINE_UPDATE.md) before changing this flow.
- Default channel UHAN16/Eta has no stable release yet; never silently switch to upstream or permit cross-signature installation. Device OTA remains untested.

## Voice call and auto-read
- Local branch feature/voice-call extends a5e1b8ed (online upgrade) with foreground conversational audio. See [voice call task](tasks/voice-call/active-task.md) and [usage](../VOICE.md).
- Preserve ordinary push-to-talk, manual/background read-aloud, roleplay storage, and OTA. Only the explicit call audio pair can overlap; ASR and TTS are still the configured services.
- Build, lint and 88 targeted tests passed; no real phone was connected. Do not claim hands-free echo performance on any particular device without testing.

## Android on-device voice
- Local branch feature/android-offline-voice extends 2b740a1a. See [the Android offline task](tasks/android-offline-voice/active-task.md) and the [voice guide](../VOICE.md).
- Device ASR is restricted to Android on-device recognition; native TTS only selects installed non-network voices. No app-bundled language models and no additional permissions. Do not add a cloud fallback for either local provider.
- Final build, Android Lint and 197 targeted tests passed. No handset or OEM voice engine was tested.
