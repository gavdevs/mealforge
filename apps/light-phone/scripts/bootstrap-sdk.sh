#!/usr/bin/env bash
# Provision the pinned Light SDK checkout this Gradle build compiles against.
#
# The app build includes the SDK's plugin build and maps :sdk:shared/:sdk:ui/
# :sdk:client/:lint-rules onto a checkout at an exact commit. That checkout
# lives under apps/light-phone/.sdk, which is git-ignored, so builds never
# depend on a developer's live SDK working tree.
#
# Usage:
#   apps/light-phone/scripts/bootstrap-sdk.sh                        # clone/fetch into .sdk
#   LIGHT_SDK_DIR=/path/to/clean/checkout scripts/bootstrap-sdk.sh
set -euo pipefail

# Owner fork at the commit this scaffold was established against. Change both
# values together; the commit must exist in the fork's history.
SDK_REPO="${LIGHT_SDK_REPO:-https://github.com/gavdevs/light-sdk.git}"
SDK_SHA="251237ef3778c907c5dbd89e84685532c9d99a28"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
sdk_dir="${LIGHT_SDK_DIR:-$here/.sdk}"

if [ ! -d "$sdk_dir/.git" ]; then
  echo "==> cloning $SDK_REPO into $sdk_dir"
  git clone --filter=blob:none --no-checkout "$SDK_REPO" "$sdk_dir"
fi

# A --no-checkout clone has neither an index nor any working files. Recover only
# that exact state; a populated checkout with deleted files is owner input.
needs_checkout=false
index_path="$(git -C "$sdk_dir" rev-parse --git-path index)"
if [[ "$index_path" != /* ]]; then
  index_path="$sdk_dir/$index_path"
fi
if [ ! -e "$index_path" ] &&
  [ -z "$(find "$sdk_dir" -mindepth 1 -maxdepth 1 ! -name .git -print -quit)" ]; then
  needs_checkout=true
elif [ -n "$(git -C "$sdk_dir" status --porcelain --untracked-files=all)" ]; then
  echo "error: $sdk_dir has local modifications; leaving files and HEAD unchanged." >&2
  exit 1
fi

if git -C "$sdk_dir" cat-file -e "$SDK_SHA^{commit}" 2>/dev/null; then
  echo "==> pinned commit already present"
else
  echo "==> fetching pinned commit $SDK_SHA"
  # GitHub allows fetching a commit by id; fall back to a normal fetch.
  git -C "$sdk_dir" fetch --quiet origin "$SDK_SHA" ||
    git -C "$sdk_dir" fetch --quiet origin
fi

if ! git -C "$sdk_dir" cat-file -e "$SDK_SHA^{commit}" 2>/dev/null; then
  echo "error: commit $SDK_SHA not found in $SDK_REPO" >&2
  echo "       point LIGHT_SDK_REPO at a remote containing it, or update SDK_SHA." >&2
  exit 1
fi

head_sha="$(git -C "$sdk_dir" rev-parse HEAD 2>/dev/null || true)"
if [ "$head_sha" != "$SDK_SHA" ] || [ "$needs_checkout" = true ]; then
  echo "==> checking out $SDK_SHA (detached)"
  git -C "$sdk_dir" checkout --quiet --detach "$SDK_SHA"
  head_sha="$(git -C "$sdk_dir" rev-parse HEAD)"
fi

# The pinned commit is only meaningful if the tree is pristine; SDK edits belong
# upstream, not in a build input.
if [ -n "$(git -C "$sdk_dir" status --porcelain --untracked-files=all)" ]; then
  echo "error: $sdk_dir has local modifications; a pinned build needs a clean tree." >&2
  exit 1
fi

echo "==> Light SDK ready at $head_sha"
echo "    next: apps/light-phone/gradlew -DlightSdk.unsigned=true :app:assembleDebug --console=plain"
