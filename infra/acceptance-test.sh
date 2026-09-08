#!/usr/bin/env bash
# End-to-end acceptance run against a live P1 and a live stack.
# Every message uses a run-scoped id so this is repeatable without a wiped Redis.
set -uo pipefail

BASE=http://localhost:8081
RUN=$(date +%s)
pass=0; fail=0

ok()   { printf "  \033[32mPASS\033[0m  %s\n" "$1"; pass=$((pass+1)); }
bad()  { printf "  \033[31mFAIL\033[0m  %s — %s\n" "$1" "$2"; fail=$((fail+1)); }
check(){ [ "$2" = "$3" ] && ok "$1" || bad "$1" "expected $3, got $2"; }

msg() { # externalId, body, custodian
  printf '{"externalId":"%s","source":"EXCHANGE","type":"EMAIL","custodianId":"%s",' "$1" "$3"
  printf '"from":"a@firm.test","to":["b@firm.test"],"subject":"s","body":"%s",' "$2"
  printf '"sentAt":"2024-05-11T21:37:00Z","threadId":"t-%s"}' "$RUN"
}

field() { python3 -c "import sys,json;print(json.load(sys.stdin).get('$1'))"; }

echo "FR-1.1  accepts a batch"
r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' \
     -d "[$(msg "R$RUN-1" "one" alice),$(msg "R$RUN-2" "two" alice)]")
check "two new messages accepted" "$(echo "$r" | field accepted)" "2"

echo
echo "FR-1.6  idempotency"
r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' -d "[$(msg "R$RUN-1" "one" alice)]")
check "same externalId is a duplicate" "$(echo "$r" | field duplicates)" "1"

r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' -d "[$(msg "R$RUN-99" "one" alice)]")
check "same content, new externalId is a duplicate" "$(echo "$r" | field duplicates)" "1"

r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' -d "[$(msg "R$RUN-3" "one" bob)]")
check "same content, different custodian is NOT a duplicate" "$(echo "$r" | field accepted)" "1"

echo
echo "batch semantics"
r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' \
     -d "[$(msg "R$RUN-4" "four" alice),{\"externalId\":\"R$RUN-bad\"},$(msg "R$RUN-5" "five" alice)]")
check "one bad message does not lose the batch" "$(echo "$r" | field accepted)" "2"
check "the bad one is rejected" "$(echo "$r" | field rejected)" "1"

echo
echo "attachments"
B64=$(python3 -c "import base64;print(base64.b64encode(b'ref,amount\nNG-1,12400000\n').decode())")
r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' -d "[{
  \"externalId\":\"R$RUN-att\",\"source\":\"EXCHANGE\",\"type\":\"EMAIL\",\"custodianId\":\"alice\",
  \"from\":\"a@firm.test\",\"to\":[\"b@firm.test\"],\"subject\":\"invoice\",\"body\":\"attached\",
  \"sentAt\":\"2024-05-11T21:37:00Z\",\"threadId\":\"t-$RUN\",
  \"attachments\":[{\"filename\":\"i.csv\",\"contentType\":\"text/csv\",\"contentBase64\":\"$B64\"}]}]")
# The two paths have different trust models on purpose. A source system posting to the API knows
# its checksums and must attest to them, so an unattested attachment is refused at the boundary.
# An uploaded file has nobody to attest for it, so the checksum is computed. Asserting both.
check "API: unattested attachment is rejected" "$(echo "$r" | field rejected)" "1"

r=$(curl -s -X POST "$BASE/messages" -H 'Content-Type: application/json' -d "[{
  \"externalId\":\"R$RUN-badhash\",\"source\":\"EXCHANGE\",\"type\":\"EMAIL\",\"custodianId\":\"alice\",
  \"from\":\"a@firm.test\",\"to\":[\"b@firm.test\"],\"subject\":\"invoice\",\"body\":\"attached\",
  \"sentAt\":\"2024-05-11T21:37:00Z\",\"threadId\":\"t-$RUN\",
  \"attachments\":[{\"filename\":\"i.csv\",\"contentType\":\"text/csv\",\"sizeBytes\":4,
  \"sha256\":\"$(printf '0%.0s' {1..64})\",\"contentBase64\":\"$B64\"}]}]")
check "a wrong checksum is rejected" "$(echo "$r" | field rejected)" "1"

echo
echo "upload"
printf '%s\n%s\n' "$(msg "R$RUN-u1" "u one" alice)" "$(msg "R$RUN-u2" "u two" alice)" > /tmp/dh-up.ndjson
r=$(curl -s -F "file=@/tmp/dh-up.ndjson" "$BASE/messages/upload")
check "NDJSON upload accepted" "$(echo "$r" | field accepted)" "2"
r=$(curl -s -F "file=@/tmp/dh-up.ndjson" "$BASE/messages/upload")
check "re-uploading the same file adds nothing" "$(echo "$r" | field duplicates)" "2"

printf '[%s,%s]' "$(msg "R$RUN-j1" "j one" alice)" "$(msg "R$RUN-j2" "j two" alice)" > /tmp/dh-up.json
r=$(curl -s -F "file=@/tmp/dh-up.json" "$BASE/messages/upload")
check "JSON array upload accepted" "$(echo "$r" | field accepted)" "2"

cat > /tmp/dh-att.ndjson <<EOF
{"externalId":"R$RUN-uatt","source":"EXCHANGE","type":"EMAIL","custodianId":"alice",
 "from":"a@firm.test","to":["b@firm.test"],"subject":"invoice","body":"attached",
 "sentAt":"2024-05-11T21:37:00Z","threadId":"t-$RUN",
 "attachments":[{"filename":"i.csv","contentType":"text/csv","contentBase64":"$B64"}]}
EOF
python3 -c "
import json
line=''.join(open('/tmp/dh-att.ndjson').read().split('\n'))
open('/tmp/dh-att.ndjson','w').write(line+'\n')"
r=$(curl -s -F "file=@/tmp/dh-att.ndjson" "$BASE/messages/upload")
check "upload: unattested attachment is accepted and hashed" "$(echo "$r" | field accepted)" "1"

echo
echo "async upload"
JOB=$(curl -s -F "file=@tools/corpus-generator/fixtures/messages.ndjson" "$BASE/messages/upload?async=true" | field jobId)
[ -n "$JOB" ] && ok "async upload returns a job id" || bad "async upload" "no job id"
for _ in $(seq 1 30); do
  S=$(curl -s "$BASE/messages/uploads/$JOB" | field status)
  [ "$S" != "RUNNING" ] && break
  sleep 3
done
check "async job completes" "$S" "COMPLETED"
TOTAL=$(curl -s "$BASE/messages/uploads/$JOB" | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['totalMessages'])")
check "async processed the whole corpus" "$TOTAL" "12025"

echo
echo "status and docs"
check "known id reports ingested" \
  "$(curl -s "$BASE/messages/R$RUN-1/status" | field ingested)" "True"
check "unknown id reports not ingested" \
  "$(curl -s "$BASE/messages/NEVER-$RUN/status" | field ingested)" "False"
check "unknown job is 404" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/messages/uploads/nope")" "404"
check "swagger ui serves" \
  "$(curl -s -o /dev/null -w '%{http_code}' -L "$BASE/swagger-ui.html")" "200"
check "openapi spec serves" \
  "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v3/api-docs")" "200"

echo
echo "limits"
check "oversized batch refused" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/messages" -H 'Content-Type: application/json' \
     -d "$(python3 -c "print('['+','.join(['{\"externalId\":\"x\"}']*1001)+']')")")" "413"
check "empty body refused" \
  "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/messages" -H 'Content-Type: application/json' -d '[]')" "400"

echo
if [ "$fail" -eq 0 ]; then
  printf "\033[32m%d checks passed, 0 failed.\033[0m\n" "$pass"
else
  printf "\033[31m%d passed, %d FAILED.\033[0m\n" "$pass" "$fail"; exit 1
fi
