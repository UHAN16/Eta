# Gitee mirror and update source
Memory landing policy: preauthorized

- 2026-11-27: user requested synchronization of UHAN16/Eta to a962327460/eta and moving the APK update channel to Gitee.
- Baseline: published GitHub v3.4.5 at 59a72e5d; app queries GitHub by default. Preserve GitHub history, mirror public branch/tag refs without forced updates, and keep feature work out of the mirrored upstream main unless user says otherwise.
- Protect chat, voice, local links, existing permissions, verification of APK SHA-256, package, signer and monotonically increasing versionCode, and user-confirmed Android installation. Only update source/metadata/download routing, release tooling/docs and version.
- Verify public anonymous Gitee availability, validate and sign with the existing fork release identity, and check the downloaded asset before advertising the update. Never record passwords, tokens, private keys or chat content.

## Recovery 2026-11-27
- Continued in a new chat. The attached credential belongs to the requested owner and can access the existing empty private repository; the older environment credential cannot. Do not persist either credential.
- User explicitly approved making the empty repository public and synchronizing/publishing. Public visibility API returned HTTP 422; inspect the reason before proceeding. Anonymous API currently returns rate-limit HTTP 403 even for unrelated public repositories; back off rather than broad probing.
- Migrate only release channel/checker/download redirect routing and release tooling/docs/version. Preserve APK integrity, identity and installer confirmation, plus all chat/voice/permissions behavior.
- Gitee has separate release attachment metadata and no GitHub-style SHA-256 digest. Require one structured release-body checksum manifest plus exact attachment name/size/ID/path. Never downgrade verification to enable installation.
- HTTP 422 reason was that Gitee cannot publicize an empty repository. After a non-force push of the 3 GitHub public branches and v3.4.5 tag, visibility change succeeded. Authenticated metadata confirms public and main default; anonymous API remains rate-limited.
- Candidate v3.4.6 / 2026112702 migrates the default source to Gitee, preserving optional GitHub builds and channel-specific redirect restrictions. New release tool stages as prerelease until anonymous full-download verification. Existing v3.4.5 cannot discover Gitee automatically; first migration requires a same-signature manual update unless a GitHub bridge is also published.

## Validated candidate
- Final signed release assembly, Android Lint and all 208 targeted tests (33 classes, including 11 new Gitee tests) passed. No production chat/voice/permission source was changed.
- Initial negative test caught acceptance of a valid manifest following a malformed duplicate opening fence; added an independent opening-fence count. Final negative/CRLF/attachment/redirect tests pass.
- APK: io.github.mangi.eta, 3.4.6 / 2026112702, Android 14+, ARM64; size 9061908, SHA-256 ffa24eaf4e3e6d94be2b04096d8943d03b94cf25e3c6d996a1139a8ec159b0b6.
- Actual APK signature matches v3.4.5, permission set is unchanged, and a decompressed-APK credential scan is clean. No real-device audio or Android installation was tested.
- Next: commit/push feature/gitee-update-source and v3.4.6 without modifying main; stage Gitee release, anonymously verify metadata and full downloaded APK, then promote to stable. Do not claim stable publication from a tag or authenticated upload alone.
