# First UHAN16/Eta release
Memory landing policy: preauthorized
- User authorized Git commit and public publication on 2026-11-27; explicitly chose a new long-lived release signing key.
- Release v3.4.5 / 2026112701 from feature/android-offline-voice (base 3b97922c). Keep origin/main as the upstream baseline; no changes to runtime functionality or CI configuration.
- Local encrypted PKCS12 / DPAPI pairing is outside this repository and must never be committed, uploaded, printed, or included in output archives. New identity cannot replace official or debug installs. Keep original data intact; no device action.
- Gate: release build, real signer/version/ABI/permissions, mandatory lint/tests; push feature without force, create public Release only with canonical signed APK and GitHub SHA-256 confirmation. Deploy key is for Git transport, not APK signing or REST publication.

## Verified release candidate
- Signed APK: `Eta-v3.4.5.apk`, package `io.github.mangi.eta`, version code `2026112701`, Android 14+, ARM64. Verified the fork's release certificate with APK signature scheme v2.
- The first combined test run timed out in conversation-link integration test teardown while the simulated runtime service waited for a binder callback. Isolated that IPC boundary in the test only; kept Room and the behavioral assertions intact. No production source or signed-build input changed.
- Final Windows run: Lint passed; all 197 feature tests passed, including all five local-link integration cases. Full suite: 2,171 total, 2,079 passed, 8 skipped, 84 failed. All 84 failing test cases also failed on unmodified upstream `ffd972d6` under the same Windows setup. No real-device audio or update installation was tested.
- Public release requires GitHub account authorization, a matching release tag, and remote asset verification. A pushed commit/tag is not proof of publication.
