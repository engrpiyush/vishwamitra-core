#!/usr/bin/env bash
# Snapshot the local Firestore emulator to var/firestore-backups/<timestamp>/.
#
# The emulator is in-memory: a restart loses everything, including artifacts that cost
# real money to reproduce (live Stage 3 judge output). Take a snapshot before any
# emulator restart; load it back with scripts/firestore-emulator-restore.sh.
#
# Usage: firestore-emulator-backup.sh [dest-dir]
#   dest-dir defaults to var/firestore-backups/<UTC timestamp>/
#
# Env overrides (defaults match the dev posture in RUNBOOK-local.md §2):
#   FIRESTORE_EMULATOR_HOST  (127.0.0.1:8082)
#   GCP_PROJECT_ID           (vishwakarma-ai-poc)
#   FIRESTORE_DATABASE       (vishwakarma-labelling)
set -euo pipefail

HOST="${FIRESTORE_EMULATOR_HOST:-127.0.0.1:8082}"
PROJECT="${GCP_PROJECT_ID:-vishwakarma-ai-poc}"
DATABASE="${FIRESTORE_DATABASE:-vishwakarma-labelling}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${1:-$ROOT/var/firestore-backups/$(date -u +%Y%m%d-%H%M%S)}"

if [ -e "$DEST/firestore_export" ]; then
  echo "ERROR: $DEST already contains an export — pick another directory" >&2
  exit 1
fi

if ! curl -s -m 3 "http://$HOST/" | grep -q Ok; then
  echo "ERROR: no Firestore emulator answering on http://$HOST/" >&2
  exit 1
fi

mkdir -p "$DEST"
DEST="$(cd "$DEST" && pwd)"   # the emulator resolves the path itself — must be absolute

# Emulator admin API (the "Bearer owner" token is the emulator's stand-in for admin auth).
body="$(curl -sS -m 300 -w $'\n%{http_code}' -X POST \
  "http://$HOST/emulator/v1/projects/$PROJECT:export" \
  -H "Authorization: Bearer owner" -H "Content-Type: application/json" \
  -d "{\"database\": \"projects/$PROJECT/databases/$DATABASE\",
       \"export_directory\": \"$DEST\",
       \"export_name\": \"firestore_export\"}")"
code="${body##*$'\n'}"
if [ "$code" != "200" ]; then
  echo "ERROR: export failed (HTTP $code):" >&2
  echo "${body%$'\n'*}" >&2
  exit 1
fi

# Per-collection document counts (collection-group, so subcollections are included) —
# the restore script verifies against this manifest.
{
  echo "# firestore emulator backup manifest"
  echo "# taken=$(date -u +%Y-%m-%dT%H:%M:%SZ) host=$HOST project=$PROJECT database=$DATABASE"
  python3 - "$HOST" "$PROJECT" "$DATABASE" <<'PY'
import json, sys, urllib.request

host, project, db = sys.argv[1:4]
base = f"http://{host}/v1/projects/{project}/databases/{db}/documents"

def post(url, payload):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
        headers={"Authorization": "Bearer owner", "Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return json.load(r)

cols, tok = [], None
while True:
    page = {"pageSize": 300, **({"pageToken": tok} if tok else {})}
    r = post(f"{base}:listCollectionIds", page)
    cols += r.get("collectionIds", [])
    tok = r.get("nextPageToken")
    if not tok:
        break

for c in sorted(cols):
    q = {"structuredAggregationQuery": {
        "structuredQuery": {"from": [{"collectionId": c, "allDescendants": True}]},
        "aggregations": [{"alias": "n", "count": {}}]}}
    res = post(f"{base}:runAggregationQuery", q)
    n = 0
    for item in (res if isinstance(res, list) else [res]):
        f = item.get("result", {}).get("aggregateFields", {})
        if "n" in f:
            n = int(f["n"].get("integerValue", 0))
    print(f"{c}\t{n}")
PY
} > "$DEST/manifest.tsv"

total="$(awk -F'\t' '!/^#/ {s+=$2} END {print s+0}' "$DEST/manifest.tsv")"
echo "Backup written: $DEST ($(du -sh "$DEST" | cut -f1))"
echo "Collections:"
grep -v '^#' "$DEST/manifest.tsv" | awk -F'\t' '{printf "  %-22s %8d\n", $1, $2}'
echo "Total documents: $total"
