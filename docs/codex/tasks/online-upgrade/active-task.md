# Active task: online-upgrade
Memory landing policy: preauthorized
- Requested 2026-11-26: add one-click in-app online upgrade, layered on feature/voice-conversation-links at cb3f8c74.
- Existing settings only checks Mangi-11/Eta and opens its release page. 2026-11-26: Mangi stable v4.0.0, UHAN16/Eta releases/latest HTTP 404. Do not import upstream or recommend its APK over the custom build.
- Isolate release channel at build time. Validate HTTPS asset route, exact stable release asset, size, SHA-256, installed application ID, strictly increasing versionCode, and exact signer set. Android remains authoritative for installation and user confirmation. No outside app installation or new root privileges.
- Preserve existing version/check UI, normal chat, voice, links, and unrelated permission and file-sharing behavior. Validate with unit tests/lint/build. No secrets on disk in project memory. Do not publish a release or push to GitHub as part of this change without authorization.

## Recovery notes
- Avoid reusing the image FileProvider: the updater has its own non-exported provider with only the private app_updates/ cache root and a read-only grant.
- One Windows Robolectric test could not resolve a FileProvider path because AndroidX assumes Android '/' path separators. Confirmed provider and resource in the merged manifest; on Windows the test verifies the real provider metadata and the generated intent with a content URI. On POSIX it also exercises the real FileProvider URI resolution. Production path/permissions were not broadened.
- No Android device was connected. There is no published UHAN16/Eta stable Release for end-to-end OTA testing. A signed, newer public fork release is a separate prerequisite.

## Result
- Implemented settings check, channel isolation, cancellable download and progress, SHA-256/length validation, same-package/version/signature/platform policy, and system-confirmed installation.
- Final Debug APK built and verified, 63 targeted tests passed (including chat/voice/link regressions), and Android Lint reported 0 errors.
- Work is in the local feature/online-upgrade branch, based on cb3f8c74. No GitHub push, release, or on-device install was performed.
