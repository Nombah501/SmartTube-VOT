# Syncing with upstream (yuliskov/SmartTube)

This fork tracks upstream master. Strategy: **merge, never rebase** (rebase
would force-push and re-conflict the same files every time).

## Current sync target (2026-09-24)

- Upstream master: `9336539b3db340c7bcf55f95afad44d1d1abb7e4` (after
  upstream 32.56, versionCode 2446).
- Fork version after integration: 32.57 / versionCode 2447.
- `.upstream-last-sha` records the exact upstream commit included in the
  merge; the merge-base should match it after committing the merge.
- VOT is ours-only in `common/.../vot/*`, the VOT controller/dialogs,
  `relay/*`, and `update.json`.
- This sync has one textual conflict: `smarttubetv/build.gradle`.
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
