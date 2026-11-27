# Active task: voice-linked-roleplay
Current continuation route: [Gitee mirror and update source](tasks/gitee-update-source/active-task.md). The original voice task history is retained below.
Memory landing policy: preauthorized
User approved project memory on 2026-11-25. Never record secrets.
## Request
Check UHAN16/Eta against upstream Mangi-11/Eta; synchronize latest source; implement direct voice input, local conversation links reusable in a new chat, automatic roleplay voice conversation (display and read replies).
## Baseline
Clean clone of UHAN16/Eta main at 83a25e71eed1e5d8dadbd330362e3999b46595d9 (3.4.4). Upstream HEAD observed ffd972d63e36a2500bf58ef38483a1015be54ed9. Latest release remains v3.4.4.
## Constraints
Local-only conversation references; no public upload. Protect existing text chat, role settings, permission policies; only touch required integration points. Do not push remote or install to phone without confirmation.
## Plan
1. Fetch upstream and inspect delta; fast-forward local main if compatible.
2. Inspect voice/chat/session integrations and implement additive features.
3. Add regression tests; attempt build; deliver patch/source and verified artifacts, clearly distinguishing untested device behavior.
## Progress
Implementation and local validation completed; see final status below.

## Implementation progress
- Local main fast-forwarded to upstream/main ffd972d63e36a2500bf58ef38483a1015be54ed9 (78 commits). Latest release remains 3.4.4. Upstream raises minSdk to 34 (Android 14).
- Upstream already implements direct mic dictation and hold-to-talk. Preserved.
- Added local-only link copy menu and explicit empty-chat paste resolver reusing transactional conversation branching and roleplay/context persistence. No HTTP lookup or public link endpoint.
- Added foreground-only roleplay voice loop: endpoint ASR -> normal submit -> final successful reply -> TTS -> next listening. Manual text/mic behavior remains when mode off.
- Added optional successful-drain TTS callback (existing callers unchanged) to distinguish normal completion from interruption. Added regression coverage.
- Validation in progress. Initial Gradle run found no SDK. Located existing JDK 25; installed required SDK components under sibling work/android-sdk (local.properties ignored).
- Never change minSdk or dependency versions just to make a local build pass. Do not claim live audio/device verification without actual testing.

## Remote publication authorization
User explicitly approved on 2026-11-25: synchronize GitHub origin main to upstream; publish new features on a separate branch, not on main. Non-force pushes only. Do not expose or record credentials.

## Validation recovery notes
- Both initial and metadata builds passed all 44 targeted tests; APK assembly and v2 signature verification passed.
- First lint run identified only new-code/local-tooling errors: three LocalContext resource reads in the voice component and unescaped Windows drive separator in ignored local.properties. Replaced UI reads with configuration-aware stringResource + rememberUpdatedState and corrected properties escaping; no suppression or dependency downgrade used. Final lint rerun pending.
- Remote main push initially encountered a transient connection failure; retry reached GitHub but no usable Git login was available. Official sign-in request presented to user; remote remains unchanged until authentication succeeds.

## Final status
- Final assembleDebug, lintDebug, and all 44 targeted unit tests passed. Lint: 0 errors, 453 warning/hint issues; no lint suppression or dependency downgrades.
- GitHub origin main was successfully fast-forwarded from 83a25e71 to ffd972d6 using the user-authorized dedicated deploy key.
- Dedicated SSH key resides outside the repository; only the public key was given to the user for GitHub. No private key or credential is recorded in project memory or output archives.
- Approved feature publication destination: feature/voice-conversation-links, separate from main.
- Remaining acceptance: real Android 14+ ARM64 device mic, configured ASR/TTS multi-turn audio, visual/lifecycle checks. No phone was connected or modified. Debug signing differs from official APK and cannot overwrite it.
