# Syncing with upstream (yuliskov/SmartTube)

This fork tracks upstream master. Strategy: **merge, never rebase** (rebase
would force-push and re-conflict the same files every time).

## Current sync target (2026-11-09)

- Upstream master: `2bcad2c130f5789af22094a59c029d14461825f0`
  (upstream remains 32.72 / 2462; two commits after `a945135eee`).
- Source and published fork: 32.76 / 2466. The source-only synchronization
  created no tag/release; v32.76 was published later on explicit request.
- MediaServiceCore: `4be5193d912c5424c31a1967926219315c48e144`.
  Both SharedModules remain `5b6b5d1d3ba670e3f89edef1d1394a7562251f2c`.
- Changes: one SABR repair attempt instead of three; extra Shorts detection
  through reel overlay style and additive parser models. Recovery still uses
  the existing reload/client-switch paths, including VOT lifecycle callbacks.
- The only merge conflict was README; the fork README was retained. Upstream
  removed its email contact, which is not present in the fork documentation.
- VOT controller, explicit play/pause hooks, preference slots 61/62, relay and
  published manifest are unchanged.
- Verification: JDK 17 `:common:testStstableDebugUnitTest
  :smarttubetv:assembleStstableDebug --no-daemon` succeeded in 2m 52s.
  Common test reports: 39 tests, zero failures/errors/skips. Release verifier
  regression suite: 14 tests passed.
- Isolated Android 34 runtime: Home tiles loaded, public English video rendered
  at 4K/25/VP9 with a 0:13 / 3:33 timeline and STATE_READY log events.
  VOT settings stayed reachable/default-off; diagnostics showed 32.76 / 2466
  and the exact new upstream SHA. Independent merged-source review: APPROVED.
- Unverified in this sync: forced SABR error/retry/fallback, translated audio,
  Shorts-filter on/off comparison and physical-TV behavior. The actual Fire TV
  was not updated; no OAuth or account actions were performed.

### Release 32.76 verification

- Annotated `v32.76` points to merge `d276ba098a3c8b6fc3bfbd09f4aa431e34015b83`.
  Release workflow `42607778228` succeeded in 4m 38s, including downloaded-byte
  verification of the uploaded assets.
- All four downloaded production APKs passed package/version 32.76 / 2466,
  exact ABI, pinned certificate and checksum validation. Their signing
  certificate matches v32.75; provenance verification passed for every APK.
- Physical signed-upgrade smoke: Fire TV AFTMM / Android 7.1.2 API 25,
  `adb install -r` succeeded; dumpsys reports 32.76 / 2466. Application Home
  populated and public video rendered at 1080/25/VP9 with a 0:34 / 3:33 timeline.
  No uninstall, data reset, settings edits or authentication actions occurred.
  Forced SABR fallback and translated audio were not tested in this smoke.
- v32.76 is published as Latest. All four public manifest URLs were downloaded
  and passed package/version/ABI/signature checks; their SHA256 values match
  the accepted signed artifacts before announcing 32.76 in `update.json`.

## Previous sync target (2026-11-08)

- Upstream master: `a945135eee6387cd6549b87604ad673944c06b5d`
  (upstream 32.72, versionCode 2462; 59 incoming commits).
- Fork source version after integration: 32.73 / versionCode 2463;
  relay migration maintenance version: 32.74 / versionCode 2464.
- `.upstream-last-sha` records the exact upstream commit included in the
  merge; the merge-base should match it after committing the merge.
- VOT is ours-only in `common/.../vot/*`, the VOT controller/dialogs,
  `relay/*`, and `update.json`.
- Resolved conflicts in README, PlayerSettingsPresenter, PlayerTweaksData and
  smarttubetv/build.gradle, preserving fork documentation and VOT integration.
- The recurring integration touchpoints are:
  - `common/.../app/models/playback/manager/PlayerUI.java`
  - `common/.../app/presenters/PlaybackPresenter.java`
  - `common/.../app/presenters/settings/PlayerSettingsPresenter.java`
  - `common/.../app/presenters/dialogs/AppUpdatePresenter.java`
  - `common/.../prefs/PlayerTweaksData.java`
  - `common/.../utils/AppDialogUtil.java`
  - `smarttubetv/.../ui/playback/PlaybackFragment.java`
  - `smarttubetv/.../ui/playback/other/VideoPlayerGlue.java`
  - `smarttubetv/.../widgets/embedplayer/EmbedPlayerView.java`
  - shared resource files and module build files
- Sync is triggered by upstream master SHA, not release tags. The workflow
  opens an issue when upstream moves; the merge commit updates
  `.upstream-last-sha`.

### Integration status and verification

- `MediaServiceCore` is pinned to `db543d4d5d639cbf3084e3870b0dac6e4e2524a1`;
  `SharedModules` is at `5b6b5d1d3ba670e3f89edef1d1394a7562251f2c` in both locations.
- Upstream adds SABR live/past-live support and live-edge positioning, opt-in
  DIAL discovery, queue insertion through intents, remote watched-progress fixes,
  additional hardware decoder recognition, updated dialogs and translations.
- Network settings move to General/Network. VOT stays in Player settings.
  Preference slot 61 remains `mIsVotButtonMigrated`; live edge uses slot 62.
  The SABR-live option is stored separately in MediaServiceCore.
- Validation: `:smarttubetv:assembleStstableDebug --no-daemon` with JDK 17:
  `BUILD SUCCESSFUL in 1m 58s`. All four debug APKs report
  `org.smarttube.stable` 32.73 / 2463.
- On an isolated Android 34 emulator, the 32.69 arm64 APK was upgraded with
  `adb install -r` to 32.73 successfully. A VOT button deliberately disabled
  before the upgrade remained disabled afterward (UI screenshot inspected).
- Emulator live edge defaulted off independently of the VOT migration flag.
  Enabling it, waiting for the preference debounce, and restarting persisted
  slot 62 as true while slot 61 remained true. DIAL defaulted off.
- Public video `jNQXAC9IVRw` played on the emulator: logcat reported
  `ExoPlayerController: STATE_READY`, video/audio decoder output and
  `SabrStream: StreamProtectionStatus status: OK`; playback screenshot inspected.
  Suggestions/storyboard requests logged nonfatal null-result errors.
- VOT activation/deactivation UI and seek were exercised during public-video
  playback; the enabled toast and button state were observed, but successful
  translated audio/ETA were not established. Do not treat this as end-to-end VOT.
- The expired custom-domain relay caused `TLSV1_ALERT_INTERNAL_ERROR`.
  Follow-up commit `01c792123` switches QR auth to
  `https://smarttube-vot-auth.youloveprimebeat.workers.dev` and redirects an
  exact saved legacy URL to this default, preserving other custom endpoints.
  Live `/api/start`, phone page and pending poll succeeded. The rebuilt app
  displayed a QR with the new host for both fresh settings and a saved legacy
  URL on Android 34. No account was linked; OAuth completion remains unverified.
- Existing JVM tests (`:common:testStstableDebugUnitTest`, SuggestionsControllerTest
  and ScreensaverManagerTest) could not execute with installed JDK 17:
  Robolectric 4.6.1 fails on ASM class-file major 61. A temporary ASM 9.2
  runtime override exposed a second incompatibility, removed
  `Unsafe.defineAnonymousClass`. The override was removed; no test dependency
  changes are included. These tests are not reported as passing.
- At integration time no physical TV was connected. Emulator checks did not
  prove hardware decoder, real audio mix, live SABR recovery, or authenticated
  QR sign-in behavior; subsequent Fire TV QR diagnostics are recorded below.
- Follow-up release [v32.73](https://github.com/Nombah501/SmartTube-VOT/releases/tag/v32.73)
  was published from `01c79212328598326001193d507e00b31060219d`.
  Release workflow `42495515736` succeeded and uploaded four APKs.
  All four were checked with aapt/apksigner: `org.smarttube.stable` 32.73 / 2463,
  certificate SHA-256 `6de965656c0c049c677981e6bfe6dda074ed0ac158c361661ed5c25d744ff7b8`
  matches the downloaded v32.69 release. Public manifest URLs were downloaded
  and byte-matched against these verified APKs before advertising 32.73 in
  `update.json`.
- Real Fire TV AFTMM (Android 7.1.2/API 25) exposed a migration gap in 32.73:
  saved `v.n501.site` without a scheme was not redirected. Correcting only the
  saved endpoint produced a workers.dev QR with the original TLS provider;
  the temporary Conscrypt diagnostic setting was restored to off.
  Maintenance commit `fb67bfce7` also handles bare/http/https legacy hosts with
  whitespace and trailing slashes, leaving custom endpoints and auth untouched.
  Actual-getter smoke: 100 assertions passed; regression tests compile with
  `:common:compileStstableDebugUnitTestJavaWithJavac` (BUILD SUCCESSFUL, 43s).
  Robolectric runtime remains blocked by the previously documented JDK issue.
- Maintenance release [v32.74](https://github.com/Nombah501/SmartTube-VOT/releases/tag/v32.74)
  is Latest from `fb67bfce7bd90236d748618296f30308b840a8fd`.
  Workflow `42498286539` succeeded. All four APKs report 32.74 / 2464 and
  the same signing certificate as v32.73. All public manifest downloads
  were byte-matched to the verified signed APKs before the manifest update.
- Review limitation: upstream gamepad `rewind`/`fastForward` bypasses the
  timeline offset for SABR live streams with a non-zero DVR window start.
  This is unmodified upstream behavior behind the default-off SABR-live
  developer option; gamepad seeking on such streams remains unverified.
- DIAL is default-off. When enabled it exposes unauthenticated SSDP/HTTP
  discovery and pairing on the local network (ports 1900/8090); use only on
  a trusted LAN. Actual discovery/pairing was not exercised.

## Workflow and VOT hardening (released in 32.75)

- `common` now uses Robolectric 4.7.3 with JDK 17, without editing upstream
  submodules. The full common suite runs; the earlier JDK incompatibility above
  describes historical runs before this change.
- Regression coverage exercises actual VOT controller requests, cancellation,
  audio readiness, manual intent, stale generations, volume restoration and
  engine-release playback snapshots; preference fixtures preserve slots 61/62.
- Preparation pause is optional and defaults off. User Play/Pause overrides it;
  translation audio is prepared silently. A failure leaves an owned pause for
  manual Play; explicit VOT cancellation resumes only a VOT-owned pause.
- Local diagnostics contain only app/upstream/device metadata and closed
  state/error categories. Playback reset preserves OAuth, relay and unrelated
  preferences. No report is uploaded.
- PR/master CI runs common tests, verifier tests and debug assembly without
  release secrets, preserving APK and test reports.
- Release artifact verification is independent of the announced manifest:
  source tag/version, stable package, exact four ABI sets and pinned certificate
  are checked before upload. Uploaded bytes are downloaded and compared before
  success; SHA256SUMS and provenance accompany future releases.
- `Verify published update` checks all URLs of the highest announced version
  on manifest changes, completed release workflows or manual runs. Downloads
  allow only GitHub HTTPS hosts and are bounded to 256 MiB each.
- Implementation commit `41c0c418c` did not publish a release or advance the
  manifest. A subsequent signed v32.75 candidate is recorded below.
- Local verification: JDK 17 `:common:testStstableDebugUnitTest
  :smarttubetv:assembleStstableDebug --no-daemon` succeeded; final common report
  contains 39 tests, zero failures/errors. The release verifier has 14 passing
  tests and was exercised against actual signed v32.74 APKs and every public
  manifest URL. Workflow YAML and all run-block shell syntax were checked.
- Isolated Android 34 UI smoke: pause defaults off and persists after restart;
  missing-token lively voice explains auth; reset returns mix 15/100 and all
  playback toggles off while preserving synthetic OAuth/custom relay.
  Diagnostics displayed metadata/status and excluded seeded secrets/relay.
  These are debug/emulator checks, not physical-TV audio validation.

### Signed 32.75 release

- Source/tag: `79ce673a43f9a8cedfa09ea2301a8546412b066e`, 32.75 / 2465.
  CI `42600770287` succeeded. Release workflow `42600778459` succeeded,
  including artifact validation, provenance and uploaded-byte verification.
- All four downloaded APKs passed the verifier and checksum comparison;
  `gh attestation verify` against the repository and downloaded bundle passed
  for each APK.
- The signed ARMv7 candidate was installed with `adb install -r` on Fire TV
  AFTMM / Android 7.1.2 API 25. Launch, settings and English playback worked.
  A focused VOT activation was attempted, but audio READY and owned-pause
  resume/cancel behavior were not conclusively observed.
- Temporary auto-translation and pause preferences were restored; all other
  current preference values were preserved. No account login was performed.
- The user confirmed acceptance on the installed Fire TV candidate ("всё ок").
  This is user-reported device acceptance; the earlier automated observation
  limits above remain documented, not retrospectively treated as passing tests.
- v32.75 is published as Latest; manifest announcement follows successful
  download/signature/version/ABI verification of all four public APK URLs.

## Merge procedure

```bash
git fetch upstream master
git log --oneline HEAD..upstream/master     # what's incoming
git merge upstream/master --no-edit
# resolve conflicts in the touchpoint files above; keep both intents
```

Then, in order:

1. **Version policy:** keep the fork `versionCode` above upstream's.
2. **Compile-risk scan:** inspect ExoPlayer, `MediaServiceCore`, `SharedModules`,
   `PlayerUI`, `PlaybackPresenter`, and `PlayerTweaksData`.
3. **Build and smoke-test:** VOT on an EN video, live volume mix, QR sign-in,
   and in-app update discovery from `update.json`.
4. **Separate source and release metadata:** bump `smarttubetv/build.gradle`.
   Keep `update.json` at the published APK version during a code-only sync.
   Update it only after a separately requested release has published APKs and
   every manifest URL has been checked for version, package and signing identity.
5. **Record the exact merged upstream SHA** in `.upstream-last-sha`, commit and
   push the verified synchronization. Tags and releases require a separate request.

The sync workflow deliberately does not write `.upstream-last-sha`; recording
the marker is part of the verified merge commit.

## Conflict cheat-sheet

| Upstream change | Fork behavior to preserve |
|---|---|
| `PlayerUI` interface methods | `updateVoiceTranslatePendingEta` plus the embed-player stub |
| `PlaybackPresenter` listener list | `mEventListeners.add(new VoiceTranslateController())` |
| `PlayerTweaksData` data format | `PLAYER_BUTTON_VOICE_TRANSLATE` and migration field at index 61 |
| `PlayerSettingsPresenter` DNS settings | VOT settings plus upstream `Utils`/OkHttp behavior |
| `smarttubetv/build.gradle` version | upstream base, then a higher fork `versionCode` |
| `strings.xml` and IDs | upstream strings plus the `vot_*` block |
| `AppUpdatePresenter` rewrites | fork update-dialog behavior and update manifest |
