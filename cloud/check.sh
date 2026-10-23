#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
if [[ -f .cloud/env.sh ]]; then source .cloud/env.sh; fi
python3 -m json.tool .devcontainer/devcontainer.json >/dev/null
git diff --check
python3 -m unittest discover -s cloud -p test_apk_identity.py -v
bash ./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 cloud/check_apk_identity.py app/build/outputs/apk/debug/app-debug.apk --variant development
