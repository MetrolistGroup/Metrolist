#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "$0")/../.." && pwd)
work=${NATIVE_EXPERIMENT_DIR:-/tmp/r6-native-image}
python3 "$repo/scripts/desktop-native-image/native_image.py" \
    --work "$work" --graalvm "${GRAALVM_HOME:-/mnt/work/tools/graalvm-25}" --experiment
