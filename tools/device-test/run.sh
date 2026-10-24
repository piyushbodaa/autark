#!/usr/bin/env bash
# Runs the on-device tests (app/src/androidTest/.../devicetest/DeviceTest.kt) on phones in
# Firebase Test Lab, and downloads what they measured to build/device-test/<run>/.
#
#   tools/device-test/run.sh MODEL,version=API [MODEL,version=API ...] [-- extra gcloud flags]
#   tools/device-test/run.sh tokay,version=35 a15,version=34
#
# Needs the Google Cloud CLI, signed in (gcloud auth login) with a Firebase project selected
# (gcloud config set project PROJECT). Firebase's free plan allows 5 runs a day on physical
# devices and 10 on virtual ones. To see the devices:
#
#   gcloud firebase test android models list --filter=form=PHYSICAL
#
# Test Lab's phones download slowly, so put the model in a Cloud Storage folder once and name
# it in MODELS: it is then copied onto each phone before the tests start.
#
#   gcloud storage cp ~/models/Qwen3.5-2B-Q4_K_M.gguf gs://BUCKET/models/
#   MODELS=gs://BUCKET/models tools/device-test/run.sh ...
set -euo pipefail
cd "$(dirname "$0")/../.."

devices=()
while [ $# -gt 0 ] && [ "$1" != "--" ]; do
    devices+=(--device "model=$1")
    shift
done
[ $# -gt 0 ] && shift
[ ${#devices[@]} -gt 0 ] || { sed -n '2,18p' "$0"; exit 1; }

files=/sdcard/Android/data/com.piyushbodaa.autark.debug/files
models=()
if [ -n "${MODELS:-}" ]; then
    model=Qwen3.5-2B-Q4_K_M.gguf
    models=(--other-files "$files/test-models/$model=${MODELS%/}/$model")
fi

./gradlew -q assembleDebug assembleDebugAndroidTest

run=autark-$(date +%Y%m%d-%H%M%S)
out=build/device-test/$run
mkdir -p "$out"
# Failed tests still leave results worth reading, so carry on either way.
gcloud firebase test android run \
    --type instrumentation \
    --app app/build/outputs/apk/debug/app-debug.apk \
    --test app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    --use-orchestrator \
    --timeout 45m \
    --no-record-video \
    --directories-to-pull $files/device-test \
    --results-dir "$run" \
    "${models[@]}" "${devices[@]}" "$@" 2>&1 | tee "$out/gcloud.log" || true

bucket=$(grep -o 'storage/browser/[^/]*' "$out/gcloud.log" | head -1 | cut -d/ -f3)
[ -n "$bucket" ] || { echo "No results bucket in the gcloud output" >&2; exit 1; }
# Everything but the model, which Test Lab files under the results too.
gcloud storage rsync --recursive --exclude=".*\.gguf$" "gs://$bucket/$run" "$out"
tools/device-test/summarize.py "$out"
