# Task 3 integration evidence

Status: Task 3 source and all required build/unit/relay gates accepted. All writers are frozen; final sync merge commit is held until coordinator approval. This package does not claim Task 4 devices, Task 5 final threat-model audit or Task 6 version preparation.

Workspace: `/Users/cbrooker/Code/TacticalMaps/.claude/worktrees/review-pdf-sync-tm`, branch `review/pdf-sync-threat-model`. Scratch evidence: `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-integration`.

## Bases and preservation

- Initial tracked HEAD `82bf970511991bad353bdd99d30e35d32c6d2e5f`, tracked worktree/index clean. The 133 preexisting untracked audit helpers/artifacts are recorded in `prestatus.txt` and SHA-256 `preexisting-untracked.json`; all are preserved byte for byte and excluded from integration staging.
- Read-only `git fetch origin` succeeded. Latest fetched `origin/main` is `9b4b809bf894047a3e5a229be3831bfacdc7993f`, already an ancestor of the initial integration HEAD. No additional overlapping mainline fix had landed, so no new mainline merge was needed.
- Merged `review/pdf-calibration` at `8b08cf03b8f0f597737fd03e51266faa6ee70c67`, including WP2, first. Git merged automatically without conflicts. The authorized intermediate merge commit is `664f0b07660d977d1135d32922b489a8c73b5817`, parents initial HEAD and WP4 squash.
- Then merged `review/sync-relay` at `92e49dd27c3b82b81dae080e32c42361f7c52042` with `--no-commit --no-ff`; its final merge commit remains pending approval. Merge-base for both source packages was `e3369bc8d29ec0d10e1f004590dfc9cc07205198`.

## Conflict resolutions

Exactly three files had textual conflicts on the second merge:

1. `localization/catalog.json`: recursive genuine three-way JSON merge, deciding each key/leaf against its base. No concurrent conflicting semantic leaf values occurred. Existing ordered WP4 keys are retained; newly introduced sync keys are appended. No whole-file ours/theirs choice, dropped translation, invented wording or expected-value edit.
2. `localization/reviews.json`: the same recursive three-way resolution preserves every unambiguous review/fingerprint change, including the nested catalog/plural/permission sections. No semantic leaf conflicts occurred.
3. `ios/TacticalMaps/Map/Renderer/TileMapContainer.swift`: preserve WP4 tile rendering, atomic calibration source/camera install, calibration grid policy and suppressed mission overlays; retain sync's direct presence-model subscription instead of root peer observation. The old `syncPDF` image/mask renderer resurrected as merge context is omitted because WP2/WP4 replaced it with the tile renderer. Presence subscription updates obey calibration suppression and restore the newest model when calibration ends; nil subscription clears markers.

JSON proof files: `wp4-json-merge-proof.json` and `sync-json-merge-proof.json`. The first automatic JSON result was independently recomputed and equaled its keywise three-way result. The second resolution records its source key sets, decision counts and zero semantic conflicts. The catalog has 2,091 messages/review records, combining the WP4 catalog's 2,074 with the sync catalog's 17 added messages. Independent reviewers independently computed the same absence of conflicting leaves and confirmed preservation of all changed source/translation/review leaves.

The presence integration adds one actual overlay/coordinator regression in `MapInteractionRegressionTests`: a visible signed-peer presentation is cleared during calibration; a direct publisher update cannot repopulate its markers or tap targets; leaving calibration restores the latest coordinate; detaching the model clears its markers. No networking/authentication bypass or wire-format change.

The first iOS compile found WP4's `PDFTileSourceTests/testGridBuildsAreLatestWins` still supplied the removed `peers:[:]` argument. Its test-only setup now uses a real empty `SyncPresenceModel` and `observePresence`, on Main. All existing grid rebuild, camera and LOD assertions remain intact; no peer assertions were removed.

Automatically merged `ContentView` retains calibration arguments and the direct presence model, plus current sync lifecycle/key-lock gates. `docs/THREAT_MODEL.md` retains both PDF storage/recovery/parser guarantees and sync durability/retention/chat boundaries. The merged WP2/WP4/client contracts remain binding. Integration-authored source changes are limited to the container and the two named regression/test-adaptation files.

## Validation

- `python3 scripts/generate_localizations.py`: PASS, outputs regenerated from the merged source of truth.
- `python3 scripts/check_localizations.py`: PASS; 443 source components, 61 reviewed literal occurrences, 2,091 messages, 23 plural families and review fingerprints/native outputs validated.
- `python3 scripts/test_localization_catalog.py`: PASS, 12 catalog regression tests.
- `python3 testdata/tools/gen_sync_client_behaviour.py`: PASS, 41 scenario groups regenerated byte-identically; SHA-256 `46e1437fae8b9c871486436aa3da58feaf4ed456667923feaa76fba37fd4a2c2`.
- Current sync-merge staged and worktree `git diff --check`: PASS. The earlier WP4 source/code diff check passed excluding `testdata/geopdf/*.pdf`: generated PDF xref entries contain required trailing spaces already present in the reviewed source package. Their bytes were preserved rather than rewriting offsets to satisfy a text whitespace check.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools ./gradlew --console=plain :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` from `android`: PASS in 51 seconds; **1,128 tests across 170 classes, zero failures/errors, one opt-in skip**. Debug and instrumentation APKs built. Direct XML totals recorded in `android-result.json`; execution log `android-full.log`.
- Independent iOS generated-project/build/full unit gate: **991 tests, zero failures, two documented opt-in live skips**, exit 0. Actual presence/calibration integration regression focused **1/0**, and the adapted grid latest-wins test genuinely passes in the full suite. Exact commands, fresh derived-data path/result bundles, initial compile failure and independent JSON/fixture/source preservation checks are in `TASK3_IOS_VERIFICATION.md`; log `ios-merged-units.log`, result `iosDD/Logs/Test/Test-TacticalMaps-2026.10.02_10-39-35-+1000.xcresult`.
- Independent relay `npm ci`, `npx vitest run`, `npm run typecheck`, and `npm run check`: PASS, **117 tests**; check is dry-run only. Exact `npm run soak -- --spawn --port 8797 --inspector-port 19297 --clients 8 --seconds 90 --restart-at 30,60`: PASS, both ordinary convergence and late joiner convergence true, zero pending work/record or presence verification failures and no relay-attributed closes. The owned soak listener was stopped. Logs/results are under `/Users/cbrooker/.claude/jobs/10094e99/tmp/codex-task3-review/relay`; exact evidence in `TASK3_MERGE_REVIEW.md`.
- Independent merge/security/parity review accepted with no unresolved finding. Catalog/reviews/inventory match the independently recomputed recursive three-way result; reviewed source/fixtures and both PDF recovery/sync security semantics are retained. Every one of the 133 preexisting untracked artifact hashes and three inherited tracked audit paths was independently verified. Current merge-relative staged/worktree whitespace checks pass with no unmerged entries.

No push, PR, deployment, version bump, reset of the main checkout or deletion of audit artifacts.
