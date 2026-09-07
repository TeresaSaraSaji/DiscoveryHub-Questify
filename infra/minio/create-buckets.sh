#!/bin/sh
# Two buckets, two owners. P2 holds the archived originals, P5 holds the export packages it
# builds. Nothing writes to both.
set -eu

mc alias set local http://minio:9000 minioadmin minioadmin

# P2 Archive: message bodies and attachment bytes. Versioning on — the archive is the system of
# record and an accidental overwrite should be recoverable.
mc mb --ignore-existing local/archive-attachments
mc version enable local/archive-attachments

# P5 Evidence: completed export packages. Exports are built under a temp prefix and moved on
# completion (decision 6 in architecture.md), so a failed job leaves nothing downloadable.
mc mb --ignore-existing local/export-packages
mc mb --ignore-existing local/export-staging

echo
mc ls local
