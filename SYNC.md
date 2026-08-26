# Syncing with upstream (yuliskov/SmartTube)

This fork tracks upstream master. Strategy: **merge, never rebase** (rebase
would force-push and re-conflict the same files every time).

## Facts (verified 2026-08-26)

- Merge-base with upstream: `705ec850c` (upstream 32.30 / versionCode 2420).
- Our side: 15 own commits. Files we changed that **also exist upstream**
  (the only possible conflict surface — 15 files):
  - `common/.../app/models/playback/controllers/VoiceTranslateController.java` (ours-only new file, but registered below)
  - `common/.../app/models/playback/controllers/ErrorFixerController.java`
  - `common/.../app/models/playback/manager/PlayerUI.java`
  - `common/.../app/presenters/PlaybackPresenter.java`
  - `common/.../app/presenters/settings/PlayerSettingsPresenter.java`
  - `common/.../app/presenters/dialogs/AppUpdatePresenter.java`
  - `common/.../prefs/PlayerTweaksData.java`
  - `common/.../prefs/VotData.java` (ours-only)
  - `common/.../utils/AppDialogUtil.java`
  - `common/.../utils/EmbedPlayerView` → `smarttubetv/.../widgets/embedplayer/EmbedPlayerView.java`
  - `smarttubetv/.../ui/playback/PlaybackFragment.java`
  - `smarttubetv/.../ui/playback/actions/ActionHelpers.java`
  - `smarttubetv/.../ui/playback/actions/TwoStateAction.java`
  - `smarttubetv/.../ui/playback/other/VideoPlayerGlue.java`
  - `common/src/main/res/values*/strings.xml`, `values/ids.xml`, `AndroidManifest.xml`, `common/build.gradle`
- Everything under `common/.../vot/*`, `relay/*`, `update.json` is ours-only → merge silently.
- Upstream **releases lag its master** (release 32.22 vs master 32.30 at check time).
  Sync trigger = upstream **master HEAD SHA** (`.github/workflows/upstream-sync.yml`
  opens an `upstream-sync` issue when it moves), not releases.

## Merge procedure

```bash
git fetch upstream master
git log --oneline HEAD..upstream/master     # what's incoming
git merge upstream/master --no-edit
# resolve conflicts in the touchpoint files above (ours = VOT integration, keep both intents)
```

Then, in order:

1. **versionCode policy**: ours must stay **above** upstream's
   (`smarttubetv/build.gradle` versionCode/versionName). Upstream bumped? Bump ours higher.
2. **Compile-risk scan** — did upstream touch any of these since our base?
   - ExoPlayer version (submodule pointers `SharedModules`, `MediaServiceCore`)
   - `PlayerUI` interface (our `updateVoiceTranslatePendingEta` lives there)
   - `PlaybackPresenter` event-listener registration list
   - `PlayerTweaksData` split-data format (our flag sits at index 61)
3. Build + smoke: VOT starts on an EN video, mix dialog applies volume live,
   QR sign-in round-trips, in-app updater sees `update.json`.
4. Commit merge, bump version (see `SKILL.md` release flow), push, release.
5. Update `.upstream-last-sha` to the merged upstream SHA (silences the sync issue).

## Conflict cheat-sheet

| Upstream change | Our side to preserve |
|---|---|
| `PlayerUI` interface methods | `updateVoiceTranslatePendingEta` + EmbedPlayerView stub |
| `PlaybackPresenter` listener list | `mEventListeners.add(new VoiceTranslateController())` |
| `PlayerTweaksData` data format | `PLAYER_BUTTON_VOICE_TRANSLATE` bit, index 61 migration flag |
| `strings.xml` (upstream edits same lines) | keep both: their strings + our `vot_*` block |
| `AppUpdatePresenter` rewrites | our `onUpdateFound` dialog-first logic |
