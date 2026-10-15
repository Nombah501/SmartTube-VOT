# Syncing with upstream (yuliskov/SmartTube)

This fork tracks upstream master. Strategy: **merge, never rebase** (rebase
would force-push and re-conflict the same files every time).

## Current sync target (2026-10-16)

- Upstream master: `615ddecb19106bb58b2d35935f3d1b1d3de970bc`
  (upstream 32.67, versionCode 2457).
- Fork version after integration: 32.68 / versionCode 2458.
- `.upstream-last-sha` records the exact upstream commit included in the
  merge; the merge-base should match it after committing the merge.
- VOT is ours-only in `common/.../vot/*`, the VOT controller/dialogs,
  `relay/*`, and `update.json`.
- This sync has two textual conflicts: `README.md` (fork README kept; upstream
  only added a TubeBeam casting note) and `smarttubetv/build.gradle` (fork
  version policy).
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

- `MediaServiceCore` is pinned to `7f36935513f55ba3f76f40427b339146a5b52f13`;
  `SharedModules` is at `890f94fcda4b3b11eeea161b7ceb06e27ac0bfe9` in both locations.
- Upstream renames `ReloadPlayerResponseError` to `SabrReloadPlayerError`, adds
  `SabrBackoffError`, SABR CDN failover for stalled networks and seek retry.
  `ErrorFixerController` now handles SABR backoff and long SABR buffering with
  `reloadVideo()` instead of `restartEngine()`. `reloadVideo()` dispatches
  `onNewVideo(sameVideo)`, so `VoiceTranslateController` cancels the job, releases
  translation audio, restores volume and re-arms according to the manual state.
- Fork README, VOT hooks, ETA forwarding, settings, resources and preference
  migration slot 61 are unchanged.
- Validation: `:smarttubetv:assembleStstableDebug` and
  `:exoplayer-library-sabr:testStstableDebugUnitTest --tests '*SabrCdnSelectorTest*'`
  with JDK 17.
- Runtime playback, SABR recovery, VOT volume mix, QR sign-in and update discovery
  remain unverified: `adb devices -l` listed no connected device.
- `update.json` is unchanged: no 32.68 APKs are published. Note: it already
  advertises 32.65 while the latest GitHub release is v32.60; publish v32.65+
  (or the 32.68 release) to make the manifest consistent.

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
4. **Update release metadata:** bump `smarttubetv/build.gradle` and add the
   matching entry to `update.json`.
5. **Record the exact merged upstream SHA** in `.upstream-last-sha`, commit,
   push, and release.

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
