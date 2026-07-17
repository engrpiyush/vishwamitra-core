# Runbook — serving vishwamitra-core locally

The operator's guide to running the console on your machine and walking the full
**Stage 1 → 2 → 3** loop, including the Stage 3 dry-run corpus demo. Setup rationale lives in
[README.md](README.md) ("Local development"); this file is the do-this-then-that.

**The local posture:** Firestore = emulator, Neo4j = **real** (Docker container — graph logic is
the thing under test), file uploads = local disk (`var/intake/`), every LLM/STT leg = dry-run
stub by default (no GCP credentials needed, no credits spent). Auth is bypassed — you are
`dev@vishwakarma.ai` (ADMIN) on every request, and CSRF is off, so plain `curl` works.

---

## 1. Prerequisites & auth (once per machine / credential expiry)

```bash
# JDK 17 + Docker Desktop assumed.
gcloud components install cloud-firestore-emulator

# Auth: take the role of the app's runtime service account — the local process then holds
# exactly the permissions the deployed Cloud Run service has (Vertex, STT, buckets).
gcloud auth application-default login \
  --impersonate-service-account=vishwakarma-labelling-sa@vishwakarma-ai-poc.iam.gserviceaccount.com
```

(Your Google account needs `roles/iam.serviceAccountTokenCreator` on that SA. The pure dry-run
posture technically runs credential-free — emulator + local Neo4j + stubbed LLM legs — but do
the auth up front so flipping any live leg in §6 just works.)

## 2. Start the stack (every session — three terminals)

```bash
# T1 — Neo4j (bolt 7687 · Browser http://localhost:7474 · auth neo4j/vishwamitra-dev)
docker compose up -d neo4j

# T2 — Firestore emulator (bind 127.0.0.1 explicitly; data lives in memory)
gcloud beta emulators firestore start --host-port=127.0.0.1:8082 --project=vishwakarma-ai-poc

# T3 — the app (dev profile)
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8082
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"
```

Order matters only for Stage 3 (the run submit pings Neo4j). Use `127.0.0.1`, not `localhost`,
for the emulator host. The two odd flags are load-bearing — see the README callout (plaintext
h2c channel; macOS dual-stack loopback).

**Verify:**

```bash
curl -s http://localhost:8090/actuator/health          # {"status":"UP"}
curl -s http://localhost:8090/api/stage3/graph/health  # {"ping":{"reachable":true,…}}
# The posture check (ADMIN): effective dry-run flags per stage + emulator/bucket/graph wiring.
# Read this BEFORE any live-leg run — it shows what the server will ACTUALLY do (§6 gotcha).
curl -s http://localhost:8090/api/admin/status | python3 -m json.tool
```

Open **http://localhost:8090** — you land signed in as ADMIN. Strawman catalogs seed on first
run.

## 3. What is real vs. stubbed in dev

| Piece | Dev behavior |
| --- | --- |
| Firestore | Emulator (in-memory — restart = wipe; snapshot/restore: §7) |
| Neo4j graph | **Real** container; data persists in the `neo4j-data` volume |
| Intake uploads | Local disk `var/intake/` (no GCS, no signed URLs) |
| Stage 2 STT + extraction | Dry-run: canned diarized transcript / canned claims |
| Stage 3 embeddings / entity extraction / judge | Dry-run: pseudo-embeddings + scripted verdicts (`app.stage3.dry-run=true`) |
| Stage 3 scoring, facts, timeline, publish | **Real** — pure app code + real graph + emulator ledger |
| Tuning jobs | Simulated (`app.tuning.dry-run=true`) |

## 4. Fast path — the Stage 3 dry-run corpus (LLD §11.12)

The seeded corpus (`stage3-dryrun-asha`, 19 fixed claims) reproduces the §11.8 worked example
end-to-end and exercises every §11 branch. This is the demo/regression walk:

```bash
curl -s -X POST http://localhost:8090/api/stage3/dev/seed-corpus   # idempotent wholesale replace
```

Then in the browser:

1. **Run page** — `http://localhost:8090/intake/stage3-dryrun-asha/stage3` → **Run Stage 3**.
   The phase rail advances on auto-poll (keep the tab open — the poll IS the worker; closing
   the tab pauses the run, reopening resumes it). Expect counters to settle around: claims
   synced **19**, facts **15**, contradiction queue **0** (fresh DB).
2. **AWAITING_REVIEW banner** → **Publish** (queue is empty) → **PUBLISHED** stamp.
3. **Scored claims** (`…/stage3/scores`) — headline checks: top score **0.90**; a **0.74**;
   the sidecar claim at **0.35** with a **bare 0.31** chip and, in its row-expand, the
   sidecar block showing **+0.04**; a **0.60**; one claim showing tier movement **LOW → HIGH**
   backed by an **anchored** fact in the panel. Row-expand answers "why this score" (signal
   vector, edges with votes + rationale, attestor trust).
4. **Timeline** (`…/stage3/timeline`) — the employer lane shows a **SUCCEEDS** succession
   (connected bars); dated EVENT diamonds below; undated/timeless facts in the list under the
   axis.
5. **Contradiction queue** (`…/stage3/contradictions`) — empty state offering Publish (the
   corpus's contradiction is explained away pre-publish by design). To see live cards, use a
   reopen + dismissal exercise (§7) or a real-judge run (§6).
6. **Entity browser** (`http://localhost:8090/admin/entities`) — search finds the corpus
   skills; the **near-miss review list** contains the *AWS Associate* provisional link
   (similarity ≈ 0.80). Open an entity → try **Merge into…** then **Split** to round-trip a
   repair; each flashes the journal outcome + "re-run Stage 3 for: …" note.
7. **Eval** (`http://localhost:8090/admin/stage3-eval`) — pick the corpus subject → **Label
   pairs (blind)** → label a handful → **Run metrics** (choose the corpus subject as reference
   to get the sanity chips). The confusion matrix / calibration curve render once labels +
   verdicts overlap.

**Determinism caveat:** identical graph state ⇒ identical scores, but consecutive runs move
attestor trust *by design* (§11.8 step 3 persists globally). For exact worked-example numbers,
reset the graph first (§7) and re-seed.

## 5. Full path — your own subject (Stage 1 → 3)

1. **Intake** (`/intake`): create a subject → upload assets (stored on local disk) → grant
   consent → **seal** the manifest.
2. **Stage 2** (`/intake/{id}/stage2`): **Run Stage 2** — dry-run produces a canned diarized
   transcript + canned claims per asset. Resolve any speaker gates, then **Begin claim
   review** → decide/PII/preview → **Submit review**.
3. **Stage 3**: the reviewed panel now links **Run Stage 3 →** — same lifecycle as §4.

## 6. Live-LLM variants (optional — real Vertex calls, spends credits)

Auth is already done (§1 — the impersonated ADC is what the app authenticates with; no API keys
anywhere). The dev profile pins `app.stage3.dry-run: true` in YAML, so use the **`APP_STAGE3_*`
env names** (OS env outranks profile YAML; the `STAGE3_DRY_RUN` name only works outside dev):

```bash
# The gated live smoke (VA-19): pseudo embeddings + REAL Gemini judge
APP_STAGE3_DRYRUN=true APP_STAGE3_DRYRUNJUDGE=false \
FIRESTORE_EMULATOR_HOST=127.0.0.1:8082 \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"

# Everything real (embeddings + extraction + judge):
APP_STAGE3_DRYRUN=false … (same command)
```

Per-leg switches: `APP_STAGE3_DRYRUNEMBEDDINGS` / `APP_STAGE3_DRYRUNEXTRACTION` /
`APP_STAGE3_DRYRUNJUDGE` (unset = follow the master). If `gemini-embedding-001` is missing in
the home region, set `STAGE3_EMBEDDING_LOCATION=us-central1`.

**Embedding transport:** this project's Vertex `gemini-embedding` quota is 5 RPM in every region
(verified 2026-07-11, not increasable), so live embedding runs use the Gemini Developer API
instead — same model, same vectors, paid-tier 3000 RPM:

```bash
STAGE3_EMBEDDING_TRANSPORT=gemini-api GEMINI_API_KEY=$(cat ~/.gemini-api-key | sed 's/^[^=]*=//') \
VERTEX_BACKOFF_MS=1000,2000,4000,8000,16000,32000 \
APP_STAGE3_DRYRUN=false FIRESTORE_EMULATOR_HOST=127.0.0.1:8082 \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"
```

`VERTEX_BACKOFF_MS` is the 429/5xx retry ladder for all Vertex/Gemini calls — **unset = backoff
disabled** (first failure fails the run; Retry resumes). Values are per-attempt *caps*: the wait
is the server's `Retry-After` when the 429 carries one, else equal jitter — at least half the
cap, decorrelated in the top half. The judge fans its ensemble calls across
`APP_STAGE3_JUDGEPARALLELISM` lanes (default 8) — raise for speed, lower if DSQ 429s pile up.
The key is an API key for the Generative Language API on this project (paid tier) — never
commit it; the app reads it only from env.

**Generative transport:** `GEMINI_TRANSPORT=gemini-api` routes ALL generateContent (judge, both
extractors, drafting) through the Developer API's fixed paid-tier quotas instead of Vertex's
shared DSQ pool — the A/B lever when 429 weather is chronic. Same `GEMINI_API_KEY`; verdict
cache and stamps are door-agnostic, so flipping mid-subject is safe. Note: flipping a dry-run leg
changes the version stamps — the next run re-embeds/re-resolves/re-judges accordingly (that's
the §15 #6 staleness design, not a bug). Stage 2 live STT similarly: `APP_STAGE2_DRYRUN=false`
(needs real buckets — usually not worth it locally).

## 7. Resets & re-runs

| Want | Do |
| --- | --- |
| Wipe the graph (exact worked-example repro) | `docker compose down -v && docker compose up -d neo4j`, then re-seed |
| Wipe Firestore (subjects, claims, runs, golden set) | Restart the emulator (in-memory), restart the app |
| Snapshot / restore Firestore across restarts (protect a paid judge run) | `scripts/firestore-emulator-backup.sh` / `…-restore.sh` — see below |
| Re-seed the corpus | `POST /api/stage3/dev/seed-corpus` (refused while its run is active) |
| Re-run a subject (PUBLISHED or parked AWAITING_REVIEW) | Run page → **Re-run** (cache-warm) or **Fresh re-run** (wipes the subject's evidence layer + cached judge verdicts). From AWAITING_REVIEW the parked run retires as SUPERSEDED — its provisional scores are discarded without touching the ledger |
| Get back to the queue after publish | `curl -X POST http://localhost:8090/api/stage3/runs/{runId}/reopen` (ADMIN; PUBLISHED → AWAITING_REVIEW — the ledger keeps the last-published values until the next publish) |
| Un-stick a FAILED run | Run page → **Retry** (resumes the failed phase; phases are re-entrant) |

### Snapshot / restore the Firestore ledger

The emulator holds everything in memory — a restart loses artifacts that cost real credits to
produce (a live judge run: `stage3_runs`, `stage3_edges`, scores). The habit: **expensive run
finishes → snapshot immediately.** A snapshot is point-in-time and needs the emulator still
alive — an unplanned crash loses everything since the last one.

```bash
scripts/firestore-emulator-backup.sh   # → var/firestore-backups/<utc-stamp>/ (+ manifest.tsv of doc counts)
```

Snapshots are plain Firestore export bundles on disk (gitignored under `var/`): they survive
emulator, app, and machine restarts. Only disk/laptop loss is uncovered — copy
`var/firestore-backups/<stamp>/` to a bucket for real durability.

**Round-trip across a restart** — order matters, because the app seeds strawman catalogs into an
empty ledger at startup; restore *before* the app's first start:

```bash
# 1. While the emulator is still up — capture state
scripts/firestore-emulator-backup.sh

# 2. Restart whatever needs restarting (emulator or the whole machine)

# 3. Start the emulator as usual (§2 T2)
gcloud beta emulators firestore start --host-port=127.0.0.1:8082 --project=vishwakarma-ai-poc

# 4. Load the newest snapshot back — ends with a per-collection count check vs the manifest
scripts/firestore-emulator-restore.sh
#    (or a specific one: scripts/firestore-emulator-restore.sh var/firestore-backups/<stamp>)

# 5. Only now start the app (§2 T3)
```

The restore refuses a non-empty database — the "app already started and seeded" mistake —
`--force` overrides (documents are overwritten by path). Both scripts honor
`FIRESTORE_EMULATOR_HOST` / `GCP_PROJECT_ID` / `FIRESTORE_DATABASE`, defaulting to the §2
posture.

## 8. Troubleshooting

- **`INTERNAL: http2 exception` on startup** — `FIRESTORE_EMULATOR_HOST` not exported in the
  app's shell, or the emulator isn't up. The plaintext channel only engages when the env var is
  set.
- **`No route to host` (gRPC/Netty)** — you dropped `-Djava.net.preferIPv4Stack=true`, or used
  `localhost` instead of `127.0.0.1`.
- **"Neo4j unreachable" flash / run submit refused** — `docker compose up -d neo4j`; if you
  overrode the password, keep `NEO4J_LOCAL_PASSWORD` (container) and `NEO4J_PASSWORD` (app) in
  sync.
- **Run FAILED with "made no progress for Nm"** — the phase-timeout reclaim fired (e.g. the tab
  was closed mid-phase for a long time). **Retry** resumes exactly where it stopped.
- **Phases never advance** — the page drives the run; keep a run-page tab open (or poll by
  hand: `curl -X POST http://localhost:8090/api/stage3/runs/{runId}/poll`).
- **Run FAILED with `429 … RESOURCE_EXHAUSTED` (quota exceeded for a base model)** — a Vertex
  per-minute quota. First: is `VERTEX_BACKOFF_MS` set? Unset = no retries at all (by design).
  For embeddings the answer is the `gemini-api` transport (§6 — the Vertex `gemini-embedding`
  quota is 5 RPM everywhere and not increasable). Check any quota: IAM & Admin → Quotas, filter
  Dimensions for the base model (e.g. `gemini-embedding`). Other knobs:
  `APP_STAGE3_EMBEDBATCHPERPOLL=8` (smaller bursts). **Retry** resumes from the cursor; work
  already done is never repeated.
- **Scores look different on a second run** — attestor trust accrual (§4 caveat), not drift.
- **Port clashes** — app `8090`, emulator `8082`, Neo4j `7687`/`7474`.

## 9. URL map (dev)

| Surface | URL |
| --- | --- |
| Dashboard / intake | `/home` · `/intake` |
| Stage 2 + claim review | `/intake/{id}/stage2` · `/intake/{id}/review/decide` |
| **Stage 3 run page** | `/intake/{id}/stage3` |
| Scored claims | `/intake/{id}/stage3/scores` |
| Contradiction queue | `/intake/{id}/stage3/contradictions` |
| Timeline | `/intake/{id}/stage3/timeline` |
| Entity browser (ADMIN) | `/admin/entities` |
| Eval dashboard (ADMIN) | `/admin/stage3-eval` |
| Graph health | `GET /api/stage3/graph/health?guards=true` |
| Server posture (ADMIN) | `GET /api/admin/status` — per-stage dry-run legs + env wiring; the run page shows the same as a DRY-RUN/LIVE badge |
| Neo4j Browser (graph spelunking) | `http://localhost:7474` (`neo4j` / `vishwamitra-dev`) |

## 10. Prod wiring (VA-72 — where prod differs from this runbook)

Prod runs the same binary with the live legs wired by Terraform
(`vishwamitra-infra/terraform`, README → "Stage 3 graph … Option 1" + "Prod verification"):

| Local (this runbook) | Prod (Cloud Run) |
| --- | --- |
| Firestore **emulator** (in-memory, snapshot scripts) | Real Firestore, named DB `vishwakarma-labelling` |
| Local Docker Neo4j (`neo4j`/`vishwamitra-dev`) | **AuraDB Free** via operator secrets `NEO4J_DB_URL` / `NEO4J_DB_SECRET` (`neo4j_wire_app=true`) |
| `GEMINI_API_KEY` exported in T3 | Secret-backed env from the `GEMINI_API_KEY` secret (+ `STAGE3_EMBEDDING_TRANSPORT=gemini-api`) |
| dev profile simulates tunes/serving | Live legs — check `GET /api/admin/status` FIRST, same as §2 |
| SA impersonation ADC (§1) | The service's own SA (`vishwakarma-labelling-sa`) |

The posture check (`GET /api/admin/status`, ADMIN) is the same habit in both worlds: read it
before any live-leg run — it shows what the server will ACTUALLY do. Prod smoke = Stage 1→3
loop on a test subject to PUBLISH; all-zero corroborations/mentions on a real subject = the
stub-run symptom (§8), check the run's paramsSnapshot.
