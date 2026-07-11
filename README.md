# vishwamitra-core

Labelling & training console for **Vishwakarma AI** — authors SFT/DPO data, exports `contents`/`parts`
JSONL to the training bucket, and submits/tracks Vertex Managed OSS tuning jobs + model versions.

Stack: Spring Boot 4.1 · Kotlin · Java 17 · Maven + Jib · Thymeleaf + HTMX · Firestore · Arrow-kt.
Target GCP project `vishwakarma-ai-poc`, region `asia-southeast1`. See `labelling-tool-plan.md`.

## Build & test

```bash
./mvnw test          # unit + context-load tests (run with the `dev` profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> **Operator runbook:** step-by-step local serving + the full Stage 1→3 walkthrough (incl. the
> Stage 3 dry-run corpus demo, live-LLM variants, resets, troubleshooting) lives in
> [RUNBOOK-local.md](RUNBOOK-local.md). The sections below cover the one-time setup rationale.

## Local development (Firestore emulator)

The app talks to a named Firestore DB. Locally, run the emulator and point the app at it; the `dev`
profile bypasses Google OAuth and signs you in as a fixed ADMIN user.

```bash
# 1. Install once (skip if present):
gcloud components install cloud-firestore-emulator

# 2. Start the emulator (bind to 127.0.0.1 explicitly):
gcloud beta emulators firestore start --host-port=127.0.0.1:8081 --project=vishwakarma-ai-poc

# 3. Run the app against it:
export FIRESTORE_EMULATOR_HOST=127.0.0.1:8081
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
  -Dspring-boot.run.jvmArguments="-Djava.net.preferIPv4Stack=true"
```

Then open http://localhost:8080. Strawman catalogs (tools, base models, taxonomy, scenarios) are
seeded automatically on first run.

> **Why the two extra flags:** the emulator speaks plaintext h2c, so `FirestoreConfig` wires an
> explicit plaintext gRPC channel when `FIRESTORE_EMULATOR_HOST` is set; and `-Djava.net.preferIPv4Stack=true`
> avoids a gRPC/Netty dual-stack "No route to host" on macOS loopback.

## Local development (Neo4j — the Stage 3 claim graph)

Stage 3 builds the corroboration graph in Neo4j. Locally the graph is **always real** (graph
logic is the thing under test — only the LLM legs are stubbed by `app.stage3.dry-run`, on by
default in dev):

```bash
docker compose up -d neo4j    # bolt://localhost:7687 · Browser http://localhost:7474
```

The dev profile connects with zero extra config (`neo4j` / `vishwamitra-dev`; override via
`NEO4J_LOCAL_PASSWORD` on the container and `NEO4J_PASSWORD` on the app — keep them in sync).
Graph data persists in the `neo4j-data` volume across restarts; full reset:

```bash
docker compose down -v        # stop Neo4j and wipe the graph volume
```

Start order for a full local Stage 1→3 loop: **Neo4j → Firestore emulator → app (dev profile)**
— i.e. the compose one-liner above, then the emulator + app commands from the previous section.
In production the same code talks to Neo4j AuraDB via the `NEO4J_URI`/`NEO4J_USER`/
`NEO4J_DATABASE`/`NEO4J_PASSWORD` env wired by `vishwamitra-infra` (operator-created secrets
`NEO4J_DB_URL`/`NEO4J_DB_SECRET`; LLD §8.1.1). No VPC/egress configuration is involved — Cloud
Run reaches the `neo4j+s://` TLS endpoint directly; after wiring the secrets, smoke-test from
the deployed service with `GET /api/stage3/graph/health` (add `?guards=true` to also sweep the
§21 A.4 layer-boundary guards). AuraDB's silent idle-connection drops are absorbed in
`Neo4jConfig`: pooled-connection liveness checks, a bounded connection lifetime, and
managed-transaction retries on SessionExpired/ServiceUnavailable.

## Deployment

Runs on **Cloud Run** (region `asia-southeast1`, project `vishwakarma-ai-poc`) behind a global
external ALB + Google-managed cert at **`labelling.vishwakarma.ai`**. Auth = Google OAuth2 restricted
to the **`vishx.com`** Workspace (the `HOSTED_DOMAIN` sign-in gate — distinct from the URL) plus the
`users` allowlist.

> **No CI/CD.** Committing to git deploys nothing. Deployment is two manual steps: build/push the
> image (`jib:build`), then roll it onto Cloud Run via Terraform (`terraform apply`).

### Runtime configuration (set by Terraform, not by hand)

The Cloud Run service's env is injected by the `labelling` Terraform module — you do **not** set these
manually: `SPRING_PROFILES_ACTIVE=prod`, `GCP_PROJECT_ID`, `GCP_REGION`, `FIRESTORE_DATABASE`,
`TRAINING_BUCKET`, `SERVING_BUCKET`, `HOSTED_DOMAIN`, `BOOTSTRAP_ADMINS`. The OAuth credentials
(`GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`) are read from **Secret Manager** (`:latest`)
when `labelling_wire_secrets=true`. Infra + the full variable list live in
[`../vishwamitra-infra/terraform`](../vishwamitra-infra/terraform).

### First deployment (two-phase)

Phase 1 brings the infra up on a placeholder image so the apply can't fail before the app
image/DNS/secrets exist; Phase 2 swaps in the real image with OAuth wired.

```bash
# --- prerequisites (once) ---
gcloud auth application-default login                  # Owner/Editor + project IAM admin
gcloud storage buckets create gs://vishwakarma-ai-poc-tfstate \
  --project vishwakarma-ai-poc --location asia-southeast1 --uniform-bucket-level-access
gcloud storage buckets update gs://vishwakarma-ai-poc-tfstate --versioning

# --- Phase 1: bootstrap infra (hello image, secrets not wired) ---
cd ../vishwamitra-infra/terraform
terraform init -backend-config="bucket=vishwakarma-ai-poc-tfstate"
terraform state list      # MUST be empty — never apply if it lists REDACTED-PROJECT / lm-ai-*
terraform plan            # expect all-adds, zero destroys
terraform apply
terraform output          # note labelling_load_balancer_ip + labelling_artifact_registry_repo

# --- manual middle ---
#  • Squarespace DNS: A record  labelling → <labelling_load_balancer_ip>  (cert provisions after it resolves)
#  • OAuth consent screen (External): add piyush@vishx.com as a Test user
#    Web client redirect URI: https://labelling.vishwakarma.ai/login/oauth2/code/google
#  • Ensure Secret Manager secrets GOOGLE_OAUTH_CLIENT_ID / GOOGLE_OAUTH_CLIENT_SECRET have versions

# --- build + push the app image ---
cd ../../vishwamitra-core
gcloud auth configure-docker asia-southeast1-docker.pkg.dev
./mvnw -DskipTests jib:build      # → asia-southeast1-docker.pkg.dev/vishwakarma-ai-poc/vishwakarma-labelling/labelling:0.0.1-SNAPSHOT

# --- Phase 2: go live ---
cd ../vishwamitra-infra/terraform
#   in terraform.tfvars set:
#     labelling_container_image = "asia-southeast1-docker.pkg.dev/vishwakarma-ai-poc/vishwakarma-labelling/labelling:0.0.1-SNAPSHOT"
#     labelling_wire_secrets    = true
terraform apply

# open https://labelling.vishwakarma.ai → sign in with an @vishx.com account
```

### Subsequent deployments (code changes)

Once Phase 2 is done, shipping a code change is just rebuild + roll:

```bash
# 1. build + push a new image (bump the tag for traceability, or reuse the version tag)
cd vishwamitra-core
./mvnw -DskipTests jib:build \
  -Dimage=asia-southeast1-docker.pkg.dev/vishwakarma-ai-poc/vishwakarma-labelling/labelling:$(git rev-parse --short HEAD)

# 2a. roll it via Terraform (keeps state as source of truth):
cd ../vishwamitra-infra/terraform
#   set labelling_container_image to the new tag, then:
terraform apply

# 2b. …or roll it directly without Terraform (faster, but update tfvars afterwards to match):
gcloud run deploy vishwakarma-labelling \
  --project vishwakarma-ai-poc --region asia-southeast1 \
  --image asia-southeast1-docker.pkg.dev/vishwakarma-ai-poc/vishwakarma-labelling/labelling:<tag>
```

### Rollback

```bash
# list revisions, then send 100% traffic to a known-good one
gcloud run revisions list --service vishwakarma-labelling --project vishwakarma-ai-poc --region asia-southeast1
gcloud run services update-traffic vishwakarma-labelling \
  --project vishwakarma-ai-poc --region asia-southeast1 --to-revisions <REVISION>=100
```

### Notes

- **Image runs `prod` profile** (Cloud Run sets `SPRING_PROFILES_ACTIVE=prod`); prod requires the
  OAuth secrets, so the real image only works with `labelling_wire_secrets=true`.
- **Port**: the app honors Cloud Run's injected `PORT` (`server.port=${PORT:8080}`).
- **Tuning is real in prod** (`app.tuning.enabled=true`); jobs spend credits and are 1-concurrent.
- The Jib base image is pulled from Docker Hub at build time — `jib:build`/`jib:buildTar` need network.
