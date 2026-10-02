# Store capture workflow

These harnesses capture actual production UI from the integrated 3.0.0 (73)
snapshot at `4e406f5`. They are not app implementation changes. Run them only
against a capture snapshot of that candidate; the primary checkout originally
contained the older 2.1 UI.

1. Back up the existing app's Documents/Library (iOS) or files/shared_prefs
   (Android). Reuse the existing simulators/emulator; do not create new devices.
2. Prepare the public-domain USGS San Francisco North fixtures. Use a
   georeferenced PDF and a metadata-free copy for calibration. Android needs a
   renderer-compatible map page: the captured fixture embeds a rendered copy
   and copies the original `/VP` geospatial dictionary. A two-page PDF exercises
   the real library menu's **Choose page** control. Importing a georeferenced
   book can automatically choose a usable page; that alone is not a chooser
   capture.
3. Copy the Swift harness into the candidate's UI test target and the Kotlin
   harness into its Android instrumentation target. Configure Android capture
   `applicationId` separately (`com.tacmap.capture`) to protect existing data.
   Put small PDF assets under `store/` in the instrumentation assets directory.
4. iOS tests receive `STORE_OUTPUT` and `STORE_SOURCE` through
   `TEST_RUNNER_STORE_OUTPUT` / `TEST_RUNNER_STORE_SOURCE`. Android receives
   `chapter` through instrumentation arguments and writes PNGs/markers to
   `files/store-capture`. Only calibration target/camera/fixture placement uses
   the existing debug hooks; controls, fitting, measurements and dialogs remain
   production behavior.
5. For live Sync/Chat, run the pinned local relay on port 8794. Generate a fresh
   run-scoped room code into the ignored `ios/build/store-captures/room.private`;
   pass it privately as Android `teamCode`. Keep both clients active while
   sending. Never commit that file, reuse a production room, or include a join
   code in the selected captures.
6. Record native screens using `simctl io recordVideo` and Android
   `screenrecord`. On Android, signal the remote recorder with SIGINT and wait
   for it to finish before pulling; terminating the local adb connection can
   leave an unfinished MP4. Confirm the file has a readable MP4 index first.
   Simulated GPS waypoints provide real location updates during recording.
7. Compose the five screenshot slides using `compose_updates.py PROFILE RAW`.
   It imports the existing store composer, fonts and palette.
8. The original capture-matching edits are `storyboards/*-edit.json` and
   `render_videos.py`. The current marketing edits are in `storyboards/marketing/`
   and render with `marketing_videos.py`. This adds original synthesized music,
   animated benefit copy and dissolves. Landscape masters also add readable
   crops of actual native footage, while Apple previews keep the full UI. Paths
   resolve relative to the repository. It matches full-decoded native footage
   to capture PNGs, chooses a stable interval, trims using decoded timestamps,
   overlays captions, and checks the encoded scene against the capture. Frame-rate normalization before trimming preserves static UI frames. Full
   decoding avoids unreliable seeking in long variable-frame-rate recordings.
9. Run `verify_assets.py` to check all 20 screenshots and eight videos and
   update the upload manifest. Inspect scene contact sheets, preserve
   selected raw captures and hashes, then restore original app data and emulator
   display settings. Stop the capture-only relay/recorders and remove the
   isolated Android capture app.

The store-facing coverage and upload instructions are in
`docs/store/FEATURE_VIDEO.md`. Native recordings, source snapshot, room code,
backups, logs and Xcode results stay in ignored `ios/build/`.
