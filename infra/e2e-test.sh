#!/usr/bin/env bash
# End-to-end acceptance run across all three live services: P1 Ingestion, P2 Archive,
# P5 Evidence Export & Audit.
#
# Where acceptance-test.sh exercises P1's API surface on its own, this one follows a single
# piece of evidence the whole way through the platform — ingested by P1, archived by P2,
# exported and checksum-verified by P5, and audited by P5 at every step — and then tries to
# break it, because a chain of custody that has never been attacked has not been tested.
#
#   ./infra/e2e-test.sh
set -uo pipefail

P1=http://localhost:8081
P2=http://localhost:8082
P5=http://localhost:8085
RUN=$(date +%s)
CUSTODIAN="e2e-alice-$RUN"
CASE="case-$RUN"

pass=0; fail=0
ok()   { printf "  \033[32mPASS\033[0m  %s\n" "$1"; pass=$((pass+1)); }
bad()  { printf "  \033[31mFAIL\033[0m  %s — %s\n" "$1" "$2"; fail=$((fail+1)); }
check(){ [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected '$3', got '$2'"; }
field(){ python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('$1'))" 2>/dev/null; }
section(){ printf "\n\033[1m%s\033[0m\n" "$1"; }

# An attachment with real bytes, so the checksum story is not theoretical.
ATT_BYTES='ref,amount
NORTHGATE-1,12400000'
ATT_B64=$(printf '%s' "$ATT_BYTES" | base64)
ATT_SHA=$(printf '%s' "$ATT_BYTES" | shasum -a 256 | awk '{print $1}')

msg() { # externalId, subject, body
  printf '{"externalId":"%s","source":"EXCHANGE","type":"EMAIL","custodianId":"%s",' "$1" "$CUSTODIAN"
  printf '"from":"dana@firm.test","to":["marcus@firm.test"],"subject":"%s","body":"%s",' "$2" "$3"
  printf '"sentAt":"2024-05-11T21:37:00Z","threadId":"thread-%s"}' "$RUN"
}

msg_with_attachment() {
  printf '{"externalId":"%s","source":"EXCHANGE","type":"EMAIL","custodianId":"%s",' "$1" "$CUSTODIAN"
  printf '"from":"dana@firm.test","to":["marcus@firm.test"],"subject":"Housekeeping","body":"see attached",'
  printf '"sentAt":"2024-05-11T21:37:00Z","threadId":"thread-%s",' "$RUN"
  printf '"attachments":[{"filename":"bridgeline-indicative-figures.csv","contentType":"text/csv",'
  printf '"sizeBytes":%s,"sha256":"%s","contentBase64":"%s"}]}' "${#ATT_BYTES}" "$ATT_SHA" "$ATT_B64"
}

# ---------------------------------------------------------------- 1. all three alive
section "0. every service is reachable and healthy"
for entry in "P1 Ingestion:$P1" "P2 Archive:$P2" "P5 Export/Audit:$P5"; do
  name="${entry%%:*}"; url="${entry#*:}"
  check "$name reports UP" "$(curl -s "$url/actuator/health" | field status)" "UP"
done

# ---------------------------------------------------------------- 2. P1 -> P2 pipeline
section "1. P1 accepts evidence and P2 becomes the system of record"
BODY="[$(msg "E2E-$RUN-1" "Northgate kickoff" "first message"),$(msg_with_attachment "E2E-$RUN-2")]"
r=$(curl -s -X POST "$P1/messages" -H 'Content-Type: application/json' -d "$BODY")
check "P1 accepted both messages" "$(echo "$r" | field accepted)" "2"

MSG1=$(echo "$r" | python3 -c "import sys,json;print(json.load(sys.stdin)['results'][0]['messageId'])")
MSG2=$(echo "$r" | python3 -c "import sys,json;print(json.load(sys.stdin)['results'][1]['messageId'])")

# P1 publishes to Kafka and P2 consumes it — the one genuinely asynchronous hop in the pipeline,
# so poll rather than assume. FR-1.7 gives this 30 seconds; it normally takes well under one.
archived=""
for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "$P2/messages/$MSG2"; then archived="yes"; break; fi
  sleep 1
done
check "P2 archived the message from Kafka" "$archived" "yes"
check "P2 returns it under the id P1 derived" \
  "$(curl -s "$P2/messages/$MSG2" | field messageId)" "$MSG2"

# The archived shape drops attachment bytes and keeps the checksum (message-schema.md).
att=$(curl -s "$P2/messages/$MSG2" | python3 -c "
import sys,json; a=json.load(sys.stdin)['attachments'][0]
print(a['attachmentId'], a['sha256'], a.get('contentBase64'))")
check "P2 preserved the attachment checksum end to end" "$(echo "$att" | awk '{print $2}')" "$ATT_SHA"
check "P2 dropped contentBase64 from the read model" "$(echo "$att" | awk '{print $3}')" "None"
ATT_ID=$(echo "$att" | awk '{print $1}')

# The bytes themselves are still downloadable, and still hash to the same value.
check "attachment bytes still hash to the original checksum" \
  "$(curl -s "$P2/messages/$MSG2/attachments/$ATT_ID" | shasum -a 256 | awk '{print $1}')" "$ATT_SHA"

section "2. idempotency holds across the pipeline (FR-1.6)"
r=$(curl -s -X POST "$P1/messages" -H 'Content-Type: application/json' -d "[$(msg "E2E-$RUN-1" "Northgate kickoff" "first message")]")
check "re-submitting is a duplicate, not an error" "$(echo "$r" | field duplicates)" "1"
BEFORE=$(curl -s "$P2/stats" | field totalMessages)
curl -s -X POST "$P1/messages" -H 'Content-Type: application/json' -d "$BODY" > /dev/null
sleep 3
check "P2's message count did not move" "$(curl -s "$P2/stats" | field totalMessages)" "$BEFORE"

# ---------------------------------------------------------------- 3. P5 export
section "3. P5 exports that evidence asynchronously (FR-6.1, FR-6.2)"
JOB=$(curl -s -X POST "$P5/exports" -H 'Content-Type: application/json' \
  -d "{\"caseId\":\"$CASE\",\"messageIds\":[\"$MSG1\",\"$MSG2\"]}")
JOB_ID=$(echo "$JOB" | field jobId)
check "P5 returned a job id immediately" "$([ -n "$JOB_ID" ] && echo yes)" "yes"
check "the job starts QUEUED, not COMPLETED" "$(echo "$JOB" | field status)" "QUEUED"

STATUS=""
for _ in $(seq 1 30); do
  STATUS=$(curl -s "$P5/exports/$JOB_ID" | field status)
  [ "$STATUS" != "QUEUED" ] && [ "$STATUS" != "RUNNING" ] && break
  sleep 1
done
check "the job reached COMPLETED" "$STATUS" "COMPLETED"

DETAIL=$(curl -s "$P5/exports/$JOB_ID")
PKG_SHA=$(echo "$DETAIL" | field packageSha256)
# 2 messages + 1 attachment = 3 manifest items.
check "the package contains every item in scope" "$(echo "$DETAIL" | field itemCount)" "3"
check "a package-level checksum was recorded" "$(printf '%s' "$PKG_SHA" | wc -c | xargs)" "64"

section "4. the package is downloadable and genuine (FR-6.3, FR-6.4)"
DL=$(curl -s "$P5/exports/$JOB_ID/download")
URL=$(echo "$DL" | field url)
check "the download link is a presigned, expiring URL" \
  "$(echo "$URL" | grep -c 'X-Amz-Expires')" "1"

ZIP=/tmp/dh-e2e-$RUN.zip
code=$(curl -s -o "$ZIP" -w '%{http_code}' "$URL")
check "the package downloads over that link" "$code" "200"
check "the downloaded bytes match the recorded checksum" \
  "$(shasum -a 256 "$ZIP" | awk '{print $1}')" "$PKG_SHA"
check "the package carries a manifest" "$(unzip -l "$ZIP" | grep -c 'manifest.json')" "1"
check "the package carries the real attachment" "$(unzip -l "$ZIP" | grep -c 'bridgeline-indicative-figures.csv')" "1"
# The attachment inside the delivered package must still be the original bytes, not a stand-in.
check "the packaged attachment is byte-identical to what was ingested" \
  "$(unzip -p "$ZIP" "attachments/${ATT_ID}_bridgeline-indicative-figures.csv" | shasum -a 256 | awk '{print $1}')" "$ATT_SHA"
check "the manifest checksum for that attachment agrees" \
  "$(unzip -p "$ZIP" manifest.json | python3 -c "
import sys,json
items=json.load(sys.stdin)['items']
print(next(i['sha256'] for i in items if i['type']=='attachment'))")" "$ATT_SHA"

section "5. verification passes, and tampering is detected (FR-6.5)"
v=$(curl -s "$P5/exports/$JOB_ID/verify")
check "an untouched package verifies clean" "$(echo "$v" | field valid)" "True"
check "  ...including the package-level checksum" "$(echo "$v" | field packageChecksumMatches)" "True"

# Now attack it: rewrite one message inside the delivered package and put it back in the
# download path under the same key, exactly as someone altering evidence after the fact would.
WORK=/tmp/dh-e2e-tamper-$RUN
rm -rf "$WORK" && mkdir -p "$WORK/x" && unzip -q "$ZIP" -d "$WORK/x"
TARGET=$(ls "$WORK/x/messages" | head -1)
python3 - "$WORK/x/messages/$TARGET" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read().replace('"body"', '"body_TAMPERED"')
open(p, 'w').write(s)
PY
(cd "$WORK/x" && zip -qr "$WORK/tampered.zip" .)
docker cp "$WORK/tampered.zip" discoveryhub-minio:/tmp/t.zip > /dev/null
docker exec discoveryhub-minio mc alias set local http://localhost:9000 minioadmin minioadmin > /dev/null 2>&1
docker exec discoveryhub-minio mc cp /tmp/t.zip "local/export-packages/$JOB_ID.zip" > /dev/null 2>&1

v=$(curl -s "$P5/exports/$JOB_ID/verify")
check "a tampered package fails verification" "$(echo "$v" | field valid)" "False"
check "  ...the package checksum no longer matches" "$(echo "$v" | field packageChecksumMatches)" "False"
check "  ...and the altered item is named" \
  "$(echo "$v" | python3 -c "import sys,json;print(len(json.load(sys.stdin)['itemMismatches']))")" "1"

section "6. every step of the above is in the audit trail (FR-7)"
# Audit events travel P1/P2 -> Kafka -> P5, so give the last few a moment to land.
sleep 5
audited_ingest=$(curl -s "$P5/audit?action=message.ingested&subjectId=$MSG2" | field totalElements)
check "P1's ingestion of the message was audited" "$audited_ingest" "1"
check "P2's archival of the message was audited" \
  "$(curl -s "$P5/audit?action=message.archived&subjectId=$MSG2" | field totalElements)" "1"
check "P1 audited the duplicate it refused" \
  "$([ "$(curl -s "$P5/audit?action=message.deduped" | field totalElements)" -gt 0 ] && echo yes)" "yes"
check "P5 audited the export request" \
  "$(curl -s "$P5/audit?action=export.requested&subjectId=$JOB_ID" | field totalElements)" "1"
check "P5 audited the export completing" \
  "$(curl -s "$P5/audit?action=export.completed&subjectId=$JOB_ID" | field totalElements)" "1"
check "P5 audited the package being downloaded" \
  "$(curl -s "$P5/audit?action=export.downloaded&subjectId=$JOB_ID" | field totalElements)" "1"
# One correlation id ties an entire user action together across services (FR-7.2).
check "the export's events share one correlation id" \
  "$(curl -s "$P5/audit?correlationId=$JOB_ID" | field totalElements)" "3"
# All three services are visibly contributing to one shared chain of custody. P1 labels itself
# "ingestion" rather than "P1"; see the contract check immediately below.
check "the audit log carries entries from all three services" \
  "$(for s in ingestion P2 P5; do curl -s "$P5/audit?service=$s&size=1" | field totalElements; done | awk '$1>0{n++} END{print n}')" "3"

# AuditEvent.java documents this field as 'Emitting service: "P1" .. "P5"'. P2 and P5 comply;
# P1 emits "ingestion". Nothing crashes, but a UI filtering the audit trail by service (FR-8.1)
# has to special-case one service, and no per-service test can see the disagreement — only a
# cross-service run like this one can. Left failing on purpose until the owners agree a fix.
check "P1 labels itself per the AuditEvent contract" \
  "$(curl -s "$P5/audit?service=P1&size=1" | field totalElements)" "1"

section "7. the audit log is append-only through the API (FR-7.3)"
EVT=$(curl -s "$P5/audit?action=export.completed&subjectId=$JOB_ID" | python3 -c "
import sys,json;print(json.load(sys.stdin)['content'][0]['eventId'])")
for verb in PUT PATCH DELETE POST; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X $verb "$P5/audit/$EVT" -H 'Content-Type: application/json' -d '{}')
  # 404/405 both mean "no such operation exists here", which is the point: there is no write path.
  check "$verb /audit/{id} is refused ($code)" \
    "$([ "$code" = "404" ] || [ "$code" = "405" ] && echo refused)" "refused"
done

section "8. one service failing does not cascade (NFR-2)"
# P5 is the audit sink and the exporter. With it stopped, ingestion and archival must carry on
# untouched, and the audit events it missed must be waiting in Kafka when it returns.
# Kill every process matching the jar, not just the first: a service started via a shell
# wrapper has two matching pids, and killing only the wrapper leaves the JVM serving happily —
# which silently turns this whole section into a no-op that passes.
if pgrep -f "export-service-0.1.0-SNAPSHOT.jar" > /dev/null; then
  pkill -f "export-service-0.1.0-SNAPSHOT.jar"
  for _ in $(seq 1 20); do curl -sf -o /dev/null -m 2 "$P5/actuator/health" || break; sleep 1; done
  check "P5 is down" "$(curl -sf -o /dev/null -m 2 "$P5/actuator/health" && echo up || echo down)" "down"

  r=$(curl -s -X POST "$P1/messages" -H 'Content-Type: application/json' -d "[$(msg "E2E-$RUN-during-outage" "sent while P5 was down" "still accepted")]")
  check "P1 still accepts messages with P5 down" "$(echo "$r" | field accepted)" "1"
  MSG3=$(echo "$r" | python3 -c "import sys,json;print(json.load(sys.stdin)['results'][0]['messageId'])")

  landed=""
  for _ in $(seq 1 30); do
    if curl -sf -o /dev/null "$P2/messages/$MSG3"; then landed="yes"; break; fi
    sleep 1
  done
  check "P2 still archives it with P5 down" "$landed" "yes"

  # Bring P5 back and confirm it catches up on the backlog rather than losing it.
  (cd "$(dirname "$0")/.." && nohup java -jar services/export-service/target/export-service-0.1.0-SNAPSHOT.jar > /tmp/p5-restart.log 2>&1 &)
  recovered=""
  for _ in $(seq 1 60); do
    if [ "$(curl -s -m 2 "$P5/actuator/health" | field status)" = "UP" ]; then recovered="yes"; break; fi
    sleep 1
  done
  check "P5 comes back up" "$recovered" "yes"

  caught_up=""
  for _ in $(seq 1 30); do
    if [ "$(curl -s "$P5/audit?subjectId=$MSG3&action=message.ingested" | field totalElements)" = "1" ]; then
      caught_up="yes"; break
    fi
    sleep 1
  done
  check "P5 replayed the audit events it missed while down" "$caught_up" "yes"
else
  bad "resiliency check" "could not find the P5 process to stop"
fi

# ---------------------------------------------------------------- tidy up
docker exec discoveryhub-minio mc rm "local/export-packages/$JOB_ID.zip" > /dev/null 2>&1
rm -rf "$WORK" "$ZIP"

printf "\n"
if [ "$fail" -eq 0 ]; then
  printf "\033[32m%d checks passed, 0 failed.\033[0m  P1 + P2 + P5 work as one system.\n" "$pass"
else
  printf "\033[31m%d passed, %d FAILED.\033[0m\n" "$pass" "$fail"; exit 1
fi
