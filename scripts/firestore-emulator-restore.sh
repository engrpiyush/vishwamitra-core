#!/usr/bin/env bash
# Load a snapshot taken by firestore-emulator-backup.sh into a RUNNING Firestore emulator,
# then verify per-collection document counts against the snapshot's manifest.
#
# Order matters after an emulator restart: start the emulator, run this script, THEN start
# the app — the app seeds strawman catalogs into an empty ledger on startup, and restoring
# on top of a non-empty database is refused (override with --force).
#
# Usage: firestore-emulator-restore.sh [--force] [backup-dir]
#   backup-dir defaults to the newest snapshot under var/firestore-backups/
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

FORCE=0
BK=""
for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    *) BK="$arg" ;;
  esac
done

if [ -z "$BK" ]; then
  # newest = last in lexicographic order (dirs are named by UTC timestamp)
  for d in "$ROOT/var/firestore-backups"/*/; do
    [ -e "$d/firestore_export" ] && BK="$d"
  done
  if [ -z "$BK" ]; then
    echo "ERROR: no snapshots under $ROOT/var/firestore-backups/ — run firestore-emulator-backup.sh first" >&2
    exit 1
  fi
fi
BK="${BK%/}"

META="$(find "$BK" -name '*.overall_export_metadata' -type f)"
if [ "$(printf '%s\n' "$META" | grep -c .)" != "1" ]; then
  echo "ERROR: expected exactly one *.overall_export_metadata under $BK, found:" >&2
  printf '%s\n' "$META" >&2
  exit 1
fi

if ! curl -s -m 3 "http://$HOST/" | grep -q Ok; then
  echo "ERROR: no Firestore emulator answering on http://$HOST/ — start it first:" >&2
  echo "  gcloud beta emulators firestore start --host-port=$HOST --project=$PROJECT" >&2
  exit 1
fi

DB_URL="http://$HOST/v1/projects/$PROJECT/databases/$DATABASE/documents"
existing="$(curl -sS -X POST "$DB_URL:listCollectionIds" \
  -H "Authorization: Bearer owner" -H "Content-Type: application/json" -d '{}')"
if [ "$FORCE" != "1" ] && printf '%s' "$existing" | grep -q '"collectionIds"'; then
  echo "ERROR: database $DATABASE on $HOST already has data — restore is meant for a fresh emulator." >&2
  echo "       (Did the app already start and seed catalogs? Restore first, then start the app.)" >&2
  echo "       Pass --force to import anyway (documents are overwritten by path)." >&2
  exit 1
fi

echo "Restoring $META"
echo "      into projects/$PROJECT/databases/$DATABASE on $HOST"
# Quirk verified against the emulator: for :import, "export_directory" must be the full path
# to the .overall_export_metadata FILE (a directory path is rejected as unparseable).
body="$(curl -sS -m 300 -w $'\n%{http_code}' -X POST \
  "http://$HOST/emulator/v1/projects/$PROJECT:import" \
  -H "Authorization: Bearer owner" -H "Content-Type: application/json" \
  -d "{\"database\": \"projects/$PROJECT/databases/$DATABASE\",
       \"export_directory\": \"$META\"}")"
code="${body##*$'\n'}"
if [ "$code" != "200" ]; then
  echo "ERROR: import failed (HTTP $code):" >&2
  echo "${body%$'\n'*}" >&2
  exit 1
fi

# Verify what landed against the snapshot manifest (when present).
python3 - "$HOST" "$PROJECT" "$DATABASE" "$BK/manifest.tsv" <<'PY'
import json, os, sys, urllib.request

host, project, db, manifest_path = sys.argv[1:5]
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

def count(c):
    q = {"structuredAggregationQuery": {
        "structuredQuery": {"from": [{"collectionId": c, "allDescendants": True}]},
        "aggregations": [{"alias": "n", "count": {}}]}}
    res = post(f"{base}:runAggregationQuery", q)
    for item in (res if isinstance(res, list) else [res]):
        f = item.get("result", {}).get("aggregateFields", {})
        if "n" in f:
            return int(f["n"].get("integerValue", 0))
    return 0

expected = {}
if os.path.isfile(manifest_path):
    with open(manifest_path) as fh:
        for line in fh:
            if line.startswith("#") or not line.strip():
                continue
            name, n = line.rstrip("\n").split("\t")
            expected[name] = int(n)

restored = {c: count(c) for c in cols}

if not expected:
    print("No manifest.tsv in the snapshot — restored counts (unverified):")
    for c in sorted(restored):
        print(f"  {c:<22}{restored[c]:>8}")
    sys.exit(0)

bad = False
print(f"{'collection':<22}{'snapshot':>10}{'restored':>10}")
for c in sorted(set(expected) | set(restored)):
    e, g = expected.get(c, 0), restored.get(c, 0)
    flag = "" if e == g else "   <-- MISMATCH"
    bad = bad or e != g
    print(f"{c:<22}{e:>10}{g:>10}{flag}")
if bad:
    print("RESTORE VERIFICATION FAILED — counts differ from the snapshot manifest")
    sys.exit(1)
print(f"ALL COLLECTIONS MATCH ({len(expected)} collections, {sum(expected.values())} documents)")
PY
