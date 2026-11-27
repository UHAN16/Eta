# First UHAN16/Eta release
Memory landing policy: preauthorized
- User authorized Git commit and public publication on 2026-11-27; explicitly chose a new long-lived release signing key.
- Release v3.4.5 / 2026112701 from feature/android-offline-voice (base 3b97922c). Keep origin/main as the upstream baseline; no changes to runtime functionality or CI configuration.
- Local encrypted PKCS12 / DPAPI pairing is outside this repository and must never be committed, uploaded, printed, or included in output archives. New identity cannot replace official or debug installs. Keep original data intact; no device action.
- Gate: release build, real signer/version/ABI/permissions, mandatory lint/tests; push feature without force, create public Release only with canonical signed APK and GitHub SHA-256 confirmation. Deploy key is for Git transport, not APK signing or REST publication.
