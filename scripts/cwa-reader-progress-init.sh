#!/bin/sh
# Install alongside CWA's other /custom-cont-init.d hooks. The Python patcher
# lives in the persistent /config volume and checks source compatibility first.
PATCHER=/config/voicebook/patch-cwa-reader-progress.py
if [ ! -f "$PATCHER" ]; then
    echo "[voicebook-progress] Patcher missing: $PATCHER; skipped."
    exit 0
fi
if ! python3 "$PATCHER" /app/calibre-web-automated; then
    # An unsupported future version must not prevent CWA from starting.
    echo "[voicebook-progress] Patch failed; check CWA version and backups. CWA will continue starting."
fi
