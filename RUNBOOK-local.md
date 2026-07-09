# Runbook — serving vishwamitra-core locally

The operator's guide to running the console on your machine and walking the full
**Stage 1 → 2 → 3** loop, including the Stage 3 dry-run corpus demo. Setup rationale lives in
[README.md](README.md) ("Local development"); this file is the do-this-then-that.

**The local posture:** Firestore = emulator, Neo4j = **real** (Docker container — graph logic is
the thing under test), file uploads = local disk (`var/intake/`), every LLM/STT leg = dry-run
stub by default (no GCP credentials needed, no credits spent). Auth is bypassed — you are
`dev@vishwakarma.ai` (ADMIN) on every request, and CSRF is off, so plain `curl` works.

---

## 1. Prerequisites (once)

```bash
# JDK 17 + Docker Desktop assumed.
gcloud components install cloud-firestore-emulator
```

No `gcloud auth` is needed for the default dry-run posture. Only the live-LLM variants (§6)
need Application Default Credentials.

## 2. Start the stack (every session — three terminals)

```bash
# T1 — Neo4j (bolt 7687 · Browser http://localhost:7474 · auth neo4j/vishwamitra-dev)
docker compose up -d neo4j

# T2 — Firestore emulator (bind 127.0.0.1 explicitly; data lives in memory)
gcloud beta emulators firestore start --host-port=127.0.0.1:8081 --project=vishwakarma-ai-poc

# T3 — the app (dev profile)
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"
```

Order matters only for Stage 3 (the run submit pings Neo4j). Use `127.0.0.1`, not `localhost`,
for the emulator host. The two odd flags are load-bearing — see the README callout (plaintext
h2c channel; macOS dual-stack loopback).

**Verify:**

```bash
curl -s http://localhost:8080/actuator/health          # {"status":"UP"}
curl -s http://localhost:8080/api/stage3/graph/health  # {"ping":{"reachable":true,…}}
```

Open **http://localhost:8080** — you land signed in as ADMIN. Strawman catalogs seed on first
run.

## 3. What is real vs. stubbed in dev

| Piece | Dev behavior |
| --- | --- |
| Firestore | Emulator (in-memory — restart = wipe) |
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
curl -s -X POST http://localhost:8080/api/stage3/dev/seed-corpus   # idempotent wholesale replace
```

Then in the browser:

1. **Run page** — `http://localhost:8080/intake/stage3-dryrun-asha/stage3` → **Run Stage 3**.
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
6. **Entity browser** (`http://localhost:8080/admin/entities`) — search finds the corpus
   skills; the **near-miss review list** contains the *AWS Associate* provisional link
   (similarity ≈ 0.80). Open an entity → try **Merge into…** then **Split** to round-trip a
   repair; each flashes the journal outcome + "re-run Stage 3 for: …" note.
7. **Eval** (`http://localhost:8080/admin/stage3-eval`) — pick the corpus subject → **Label
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

```bash
gcloud auth application-default login   # ADC for Vertex
```

The dev profile pins `app.stage3.dry-run: true` in YAML, so use the **`APP_STAGE3_*` env
names** (OS env outranks profile YAML; the `STAGE3_DRY_RUN` name only works outside dev):

```bash
# The gated live smoke (VA-19): pseudo embeddings + REAL Gemini judge
APP_STAGE3_DRYRUN=true APP_STAGE3_DRYRUNJUDGE=false \
FIRESTORE_EMULATOR_HOST=127.0.0.1:8081 \
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"

# Everything real (embeddings + extraction + judge):
APP_STAGE3_DRYRUN=false … (same command)
```

Per-leg switches: `APP_STAGE3_DRYRUNEMBEDDINGS` / `APP_STAGE3_DRYRUNEXTRACTION` /
`APP_STAGE3_DRYRUNJUDGE` (unset = follow the master). If `gemini-embedding-001` is missing in
the home region, set `STAGE3_EMBEDDING_LOCATION=us-central1`. Note: flipping a dry-run leg
changes the version stamps — the next run re-embeds/re-resolves/re-judges accordingly (that's
the §15 #6 staleness design, not a bug). Stage 2 live STT similarly: `APP_STAGE2_DRYRUN=false`
(needs real buckets — usually not worth it locally).

## 7. Resets & re-runs

| Want | Do |
| --- | --- |
| Wipe the graph (exact worked-example repro) | `docker compose down -v && docker compose up -d neo4j`, then re-seed |
| Wipe Firestore (subjects, claims, runs, golden set) | Restart the emulator (in-memory), restart the app |
| Re-seed the corpus | `POST /api/stage3/dev/seed-corpus` (refused while its run is active) |
| Re-run a PUBLISHED subject | Run page → **Re-run** (cache-warm) or **Fresh re-run** (wipes the subject's evidence layer + cached judge verdicts) |
| Get back to the queue after publish | `curl -X POST http://localhost:8080/api/stage3/runs/{runId}/reopen` (ADMIN; PUBLISHED → AWAITING_REVIEW — the ledger keeps the last-published values until the next publish) |
| Un-stick a FAILED run | Run page → **Retry** (resumes the failed phase; phases are re-entrant) |

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
  hand: `curl -X POST http://localhost:8080/api/stage3/runs/{runId}/poll`).
- **Scores look different on a second run** — attestor trust accrual (§4 caveat), not drift.
- **Port clashes** — app `8080`, emulator `8081`, Neo4j `7687`/`7474`.

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
| Neo4j Browser (graph spelunking) | `http://localhost:7474` (`neo4j` / `vishwamitra-dev`) |
