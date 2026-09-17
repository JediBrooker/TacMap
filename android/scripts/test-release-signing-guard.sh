#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
android_dir="$(cd "$script_dir/.." && pwd)"
cd "$android_dir"

umask 077
probe_dir="$(mktemp -d -t tacmap-signing-guard.XXXXXX)"
signed_bundle="$android_dir/app/build/outputs/bundle/release/app-release.aab"
bundle_backup="$probe_dir/preexisting-app-release.aab"
had_preexisting_bundle=0
manage_signed_bundle=0
cleanup() {
  set +e
  if [[ "$manage_signed_bundle" -eq 1 ]]; then
    if [[ "$had_preexisting_bundle" -eq 1 && -f "$bundle_backup" ]]; then
      cp -p "$bundle_backup" "$signed_bundle"
    elif [[ "$had_preexisting_bundle" -eq 0 ]]; then
      rm -f "$signed_bundle"
    fi
  fi
  rm -f "$probe_dir"/*.log "$probe_dir"/*.txt "$probe_dir"/*.jks "$bundle_backup"
  rmdir "$probe_dir"
}
trap cleanup EXIT
trap 'echo "Release signing guard check failed at line $LINENO" >&2' ERR

secret_marker="TACTICALMAPS_GUARD_VALUE_MUST_NOT_APPEAR"
path_marker="TACTICALMAPS_GUARD_PATH_MUST_NOT_APPEAR"
missing_keystore_path="$probe_dir/$path_marker.jks"
readable_non_keystore="$android_dir/gradlew"

blank_args=(
  "-PTACTICALMAPS_RELEASE_STORE_FILE= "
  -PTACTICALMAPS_RELEASE_STORE_PASSWORD=
  -PTACTICALMAPS_RELEASE_KEY_ALIAS=
  -PTACTICALMAPS_RELEASE_KEY_PASSWORD=
)
missing_path_args=(
  "-PTACTICALMAPS_RELEASE_STORE_FILE=$missing_keystore_path"
  "-PTACTICALMAPS_RELEASE_STORE_PASSWORD=${secret_marker}_STORE"
  "-PTACTICALMAPS_RELEASE_KEY_ALIAS=${secret_marker}_ALIAS"
  "-PTACTICALMAPS_RELEASE_KEY_PASSWORD=${secret_marker}_KEY"
)
readable_non_keystore_args=(
  "-PTACTICALMAPS_RELEASE_STORE_FILE=$readable_non_keystore"
  "-PTACTICALMAPS_RELEASE_STORE_PASSWORD=${secret_marker}_STORE"
  "-PTACTICALMAPS_RELEASE_KEY_ALIAS=${secret_marker}_ALIAS"
  "-PTACTICALMAPS_RELEASE_KEY_PASSWORD=${secret_marker}_KEY"
)

assert_not_logged() {
  local log_file="$1"
  shift
  local forbidden
  for forbidden in "$@"; do
    if grep -Fq "$forbidden" "$log_file"; then
      echo "Sensitive signing input was written to Gradle output" >&2
      exit 1
    fi
  done
}

assert_guard_precedes_expensive_work() {
  local graph_log="$1"
  local guard_line
  guard_line="$(grep -nF ':app:verifyReleaseSigning SKIPPED' "$graph_log" | head -n 1 | cut -d: -f1)"
  if [[ -z "$guard_line" ]]; then
    echo "Release signing guard is absent from the task graph" >&2
    exit 1
  fi

  local task_name task_line
  for task_name in \
    preBuild \
    preDebugBuild \
    preReleaseBuild \
    processDebugNavigationResources \
    generateDebugResources \
    mergeDebugResources \
    compileDebugKotlin \
    extractReleaseVersionControlInfo \
    generateReleaseResources \
    mergeReleaseResources \
    compileReleaseKotlin \
    minifyReleaseWithR8 \
    lintVitalRelease \
    packageReleaseBundle \
    signReleaseBundle; do
    task_line="$(grep -nF ":app:$task_name SKIPPED" "$graph_log" | head -n 1 | cut -d: -f1 || true)"
    if [[ -n "$task_line" && "$guard_line" -ge "$task_line" ]]; then
      echo "Release signing guard runs too late for $task_name" >&2
      exit 1
    fi
  done
}

# Regression against resolving any signing Provider before the task's doLast.
pre_execution_source="$probe_dir/pre-execution-source.txt"
sed -n '/val releaseStoreFilePath =/,/    doLast {/p' app/build.gradle.kts >"$pre_execution_source"
if grep -Eq '\.(orNull|isPresent)([^A-Za-z0-9_]|$)' "$pre_execution_source" || \
    grep -Eq 'release(StoreFilePath|StorePassword|KeyAlias|KeyPassword)\.get([^A-Za-z0-9_]|$)' \
      "$pre_execution_source"; then
  echo "A release signing Provider is resolved during Gradle configuration" >&2
  exit 1
fi
if grep -Fq 'hasReleaseSigning' app/build.gradle.kts; then
  echo "Eager hasReleaseSigning configuration gate was reintroduced" >&2
  exit 1
fi

# All four configured values remain lazy during configuration; a dry run must
# neither validate nor print them, while still wiring the early guard.
configured_dry_run_log="$probe_dir/configured-bundle-dry-run.log"
./gradlew --no-daemon --console=plain "${missing_path_args[@]}" \
  :app:bundleRelease --dry-run >"$configured_dry_run_log" 2>&1
grep -Fq ':app:verifyReleaseSigning SKIPPED' "$configured_dry_run_log"
assert_guard_precedes_expensive_work "$configured_dry_run_log"
assert_not_logged \
  "$configured_dry_run_log" \
  "$secret_marker" \
  "$path_marker" \
  "$missing_keystore_path"

# The matcher itself covers the three unflavored entry points and their
# flavored equivalents without sweeping assembleRelease into the guard.
coverage_log="$probe_dir/task-name-coverage.log"
./gradlew --no-daemon --console=plain "${missing_path_args[@]}" \
  :app:verifyReleaseSigningGuardCoverage >"$coverage_log" 2>&1
grep -Fq 'BUILD SUCCESSFUL' "$coverage_log"
assert_not_logged \
  "$coverage_log" \
  "$secret_marker" \
  "$path_marker" \
  "$missing_keystore_path"

missing_log="$probe_dir/missing-inputs.log"
if ./gradlew --no-daemon --console=plain "${blank_args[@]}" \
    :app:verifyReleaseSigning >"$missing_log" 2>&1; then
  echo "Expected missing signing inputs to fail" >&2
  exit 1
fi
grep -Fq 'Missing property/environment name(s)' "$missing_log"
for input_name in \
  TACTICALMAPS_RELEASE_STORE_FILE \
  TACTICALMAPS_RELEASE_STORE_PASSWORD \
  TACTICALMAPS_RELEASE_KEY_ALIAS \
  TACTICALMAPS_RELEASE_KEY_PASSWORD; do
  grep -Fq "$input_name" "$missing_log"
done

missing_path_log="$probe_dir/missing-path.log"
if ./gradlew --no-daemon --console=plain "${missing_path_args[@]}" \
    :app:verifyReleaseSigning >"$missing_path_log" 2>&1; then
  echo "Expected an absent keystore to fail" >&2
  exit 1
fi
grep -Fq 'TACTICALMAPS_RELEASE_STORE_FILE is missing, unreadable, or not a regular file' \
  "$missing_path_log"
assert_not_logged \
  "$missing_path_log" \
  "$secret_marker" \
  "$path_marker" \
  "$missing_keystore_path"

# A readable script is not a keystore and must fail format/password validation.
readable_file_log="$probe_dir/readable-non-keystore.log"
if ./gradlew --no-daemon --console=plain "${readable_non_keystore_args[@]}" \
    :app:verifyReleaseSigning >"$readable_file_log" 2>&1; then
  echo "Expected a readable non-keystore to fail" >&2
  exit 1
fi
grep -Fq 'TACTICALMAPS_RELEASE_STORE_FILE is not a JKS/PKCS12 keystore' "$readable_file_log"
grep -Fq 'TACTICALMAPS_RELEASE_STORE_PASSWORD is invalid' "$readable_file_log"
assert_not_logged \
  "$readable_file_log" \
  "$secret_marker" \
  "$readable_non_keystore"

# Each real bundle entry point carries the early guard. bundleRelease was
# checked above with all four values present; check the two AGP stage tasks too.
for entry_point in packageReleaseBundle signReleaseBundle; do
  graph_log="$probe_dir/$entry_point.log"
  ./gradlew --no-daemon --console=plain "${blank_args[@]}" \
    ":app:$entry_point" --dry-run >"$graph_log" 2>&1
  assert_guard_precedes_expensive_work "$graph_log"
done

# Gradle resolves unique task abbreviations and aggregate lifecycle selectors
# after reading startParameter.taskNames. The ordering must therefore follow
# the resolved graph, not merely exact raw selector spelling.
for selector in bundleRel bR packageRelB signRelB bundle; do
  selector_graph_log="$probe_dir/selector-$selector.log"
  ./gradlew --no-daemon --console=plain "${blank_args[@]}" \
    ":app:$selector" --dry-run >"$selector_graph_log" 2>&1
  assert_guard_precedes_expensive_work "$selector_graph_log"
done

# assembleRelease remains the intentionally unsigned release/R8 verification
# route and therefore must not gain either the custom guard or AGP signing.
assemble_graph_log="$probe_dir/assemble-release.log"
./gradlew --no-daemon --console=plain "${missing_path_args[@]}" \
  :app:assembleRelease --dry-run >"$assemble_graph_log" 2>&1
if grep -Fq ':app:verifyReleaseSigning' "$assemble_graph_log" || \
    grep -Fq ':app:validateSigningRelease' "$assemble_graph_log"; then
  echo "Unsigned assembleRelease unexpectedly requires signing" >&2
  exit 1
fi
assert_not_logged \
  "$assemble_graph_log" \
  "$secret_marker" \
  "$path_marker" \
  "$missing_keystore_path"

# A real bundle invocation (not --dry-run) must fail at the sanitized guard,
# before resource processing, compilation, lint, R8, packaging, or signing.
actual_bundle_log="$probe_dir/actual-bundle-failure.log"
if ./gradlew --no-daemon --console=plain "${missing_path_args[@]}" \
    :app:bundleRelease >"$actual_bundle_log" 2>&1; then
  echo "Expected the invalid release bundle invocation to fail" >&2
  exit 1
fi
grep -Fq '> Task :app:verifyReleaseSigning FAILED' "$actual_bundle_log"
grep -Fq 'TACTICALMAPS_RELEASE_STORE_FILE is missing, unreadable, or not a regular file' \
  "$actual_bundle_log"
if grep -Eq '> Task :app:(generateReleaseResources|mergeReleaseResources|compileReleaseKotlin|minifyReleaseWithR8|lintVitalRelease|packageReleaseBundle|signReleaseBundle)' \
    "$actual_bundle_log"; then
  echo "Bundle work began before release signing validation" >&2
  exit 1
fi
assert_not_logged \
  "$actual_bundle_log" \
  "$secret_marker" \
  "$path_marker" \
  "$missing_keystore_path"

# Prove the execution-time credentials validated by verifyReleaseSigning are
# the credentials used by the final in-process AAB signing action. This is a
# disposable local test key only; the generated AAB is verified and then
# removed (or a prior AAB is restored) by the trap, and neither artifact can be
# mistaken for a release.
command -v keytool >/dev/null
command -v jarsigner >/dev/null
manage_signed_bundle=1
if [[ -f "$signed_bundle" ]]; then
  had_preexisting_bundle=1
  cp -p "$signed_bundle" "$bundle_backup"
fi
disposable_keystore="$probe_dir/disposable-signing-proof.jks"
disposable_store_password="${secret_marker}_DISPOSABLE_STORE_PASSWORD"
disposable_key_alias="${secret_marker}_DISPOSABLE_ALIAS"
disposable_key_password="${secret_marker}_DISPOSABLE_KEY_PASSWORD"
keytool_log="$probe_dir/keytool.log"
keytool \
  -genkeypair \
  -noprompt \
  -storetype JKS \
  -keystore "$disposable_keystore" \
  -storepass "$disposable_store_password" \
  -alias "$disposable_key_alias" \
  -keypass "$disposable_key_password" \
  -keyalg RSA \
  -keysize 2048 \
  -sigalg SHA256withRSA \
  -validity 2 \
  -dname 'CN=TacticalMaps Disposable Guard Test, OU=Build Verification, O=Local, L=Sydney, ST=NSW, C=AU' \
  >"$keytool_log" 2>&1

signed_bundle_log="$probe_dir/signed-bundle.log"
if ! ./gradlew \
    --no-daemon \
    --no-build-cache \
    --console=plain \
    "-PTACTICALMAPS_RELEASE_STORE_FILE=$disposable_keystore" \
    "-PTACTICALMAPS_RELEASE_STORE_PASSWORD=$disposable_store_password" \
    "-PTACTICALMAPS_RELEASE_KEY_ALIAS=$disposable_key_alias" \
    "-PTACTICALMAPS_RELEASE_KEY_PASSWORD=$disposable_key_password" \
    :app:bundleRelease >"$signed_bundle_log" 2>&1; then
  sed \
    -e "s|$disposable_keystore|[REDACTED_KEYSTORE_PATH]|g" \
    -e "s|$disposable_store_password|[REDACTED_STORE_PASSWORD]|g" \
    -e "s|$disposable_key_alias|[REDACTED_KEY_ALIAS]|g" \
    -e "s|$disposable_key_password|[REDACTED_KEY_PASSWORD]|g" \
    "$signed_bundle_log" | tail -n 80 >&2
  exit 1
fi
grep -Fq 'BUILD SUCCESSFUL' "$signed_bundle_log"
test -f "$signed_bundle"
assert_not_logged \
  "$signed_bundle_log" \
  "$secret_marker" \
  "$disposable_keystore" \
  'DEFERRED_BY_VERIFY_RELEASE_SIGNING'

signature_log="$probe_dir/signature-verification.log"
jarsigner -verify -verbose -certs "$signed_bundle" >"$signature_log" 2>&1
grep -Fq 'jar verified.' "$signature_log"
grep -Fq 'CN=TacticalMaps Disposable Guard Test' "$signature_log"

echo "Release signing guard checks passed."
