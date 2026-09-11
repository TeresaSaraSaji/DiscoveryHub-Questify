#!/bin/sh
# Two owners, three buckets. P2 holds archived originals, P5 holds the export packages it builds.
# Nothing writes to both.
set -eu

mc alias set local http://minio:9000 "${MINIO_ROOT_USER:-minioadmin}" "${MINIO_ROOT_PASSWORD:-minioadmin}"

# P2 Archive: message bodies and attachment bytes. Versioning on — this is the system of record
# and an accidental overwrite should be recoverable.
mc mb --ignore-existing local/archive-attachments
mc version enable local/archive-attachments

# P5 Evidence: exports are assembled under staging and moved to packages on completion, so a
# failed job never leaves a partial package in the download path.
mc mb --ignore-existing local/export-staging
mc mb --ignore-existing local/export-packages

echo
mc ls local
