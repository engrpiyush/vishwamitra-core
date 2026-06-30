# Vishwakarma AI — Labelling & Training Console — Implementation Plan

> **⚠️ Superseded (2026-06-30).** This document describes the original **LaborMandi / WorkerChowk
> gig-worker assistant** use-case (skills like plumber/electrician, booking tools, Hinglish). The app
> has been forked toward **Project Neo** (a personal-advocate model): the gig taxonomy/tools/scenarios
> were stripped and the SFT/DPO tag dimensions are now Claim-aligned (`claimType` + `authenticityTier`
> + freeform `labels`). The **import → train → version → publish** machinery described below is
> unchanged and still accurate. Treat the gig-specific catalog/scenario/persona sections as historical.

> **Status:** planned, not built. Training work is paused; **next session** picks up here.
> Source of truth for the *training* side remains the repo-root [`../PLAN.md`](../PLAN.md); this doc is the
> full spec for the **labelling + training-management web app** (and a self-contained recap of the context).

---

## 0. Background — training & quota context (recap)

**Project / billing / region.** GCP project `REDACTED-PROJECT` (REDACTED-PROJECT-NUMBER), billing
`REDACTED-BILLING-ACCOUNT` (credits, INR), region `asia-southeast1` for tuning + data buckets.

**Quota reality (why everything is the way it is).**
- GCE GPU quota = 0 and **rejected**; `gpus_all_regions` = 0 blocks all GCE GPUs. No quota upgrades possible.
- Vertex **Custom Jobs** A100 path was unusable (preemptible CPUs=1 vs the 12 a2-highgpu-1g needs).
- **Vertex Managed OSS Tuning** (Agent Platform → Tuning) is THE path: runs on Google's **managed pool**,
  gated by `GlobalConcurrentManagedOssModelTuningJobs` = 1 (one job at a time), **covered by credits**.
- **No persistent serving** in this project (serving GPU quota = 0) → models are served **off-GCP**,
  temporary spin-up/teardown (rent a GPU → vLLM → test → tear down), or via a different account later.

**Model(s).** Primary base **Qwen 3 32B** (`qwen/qwen3@qwen3-32b`, Apache 2.0). We also want to **test
other OSS bases** (Gemma 3 27B, MedGemma, …) from the managed-tuning catalog. **Gemma 4 is NOT available**
for managed tuning (not in the catalog; custom weights must match a supported architecture) — tuning Gemma 4
would require self-managed off-GCP (TRL/Unsloth).

**Dataset format (ground truth, accepted by the managed API).** `contents`/`parts`, roles `user`/`model`:
`{"contents":[{"role":"user","parts":[{"text":"…"}]},{"role":"model","parts":[{"text":"…"}]}]}`.
(The `messages`/`content` shape in `data-samples/` is the older format — do not target it.)

**Continuation recipe (VERIFIED, learned from real 400s).** To tune on top of a prior model, POST a
`tuningJobs` to the **v1beta1** endpoint with:
- `baseModel` = architecture anchor (e.g. `qwen/qwen3@qwen3-32b`) **+** `customBaseModel` = the prior
  tune's **GCS export dir** (merged-weights root, not `adapter/`). **Do NOT use `preTunedModel`** for OSS
  (400 "base model name cannot be determined" — that route targets Gemini tunes).
- `outputUri` (**required** for OSS) + `learningRate` (Qwen **rejects** `learningRateMultiplier`).
- `supervisedTuningSpec` (SFT) or `preferenceOptimizationSpec` (DPO).
The console **"Tune a pre-tuned model"** picker is **Gemini-only** (empty for OSS) — irrelevant; use the API.
Helper script encoding this: [`../terraform/scripts/continue-tune.sh`](../terraform/scripts/continue-tune.sh).

**Current state.**
- Infra (buckets, SA, budget) deployed on credits.
- Smoke SFT tune **succeeded** → `models/REDACTED-MODEL-ID` ("vishwakarma-ai"), exported to
  `gs://REDACTED-BUCKET/model-REDACTED-MODEL-ID/custom-trained/<ts>/`.
- Round-2 **continuation mechanism proven** via `customBaseModel`.
- **Blocker = data.** The pipeline works; quality SFT/DPO data is what's missing → hence this tool.

---

## 1. What we're building

An internal web app (**`labelling.vishwakarma.ai`**) that lets a small team:
1. **Author & curate** SFT transcripts (incl. tool-call turns) and DPO preference pairs, LLM-assisted, and
   **export** them as `contents`/`parts` JSONL into the training bucket (replacing manual `upload-data.sh`).
2. **Submit & track managed tuning jobs** and **manage model versions** (per base model, with lineage and a
   serving handoff), all from one place.

---

## 2. Decisions (locked)

| Area | Decision |
|---|---|
| Stack | Kotlin + Spring Boot (MVC) + Thymeleaf + modern CSS/JS (HTMX); **Arrow-kt** FP; **Kotlin-only**; Gradle KTS; image via **Jib** (no Dockerfile) |
| Datastore | Firestore Native, **named DB** `vishwakarma-labelling`, `asia-south1`; **Firestore Java client directly** (sync), not the reactive Spring Data starter |
| Domain / ingress | `labelling.vishwakarma.ai` → **regional external ALB** (`asia-south1`, regional IP) + Google-managed cert → serverless NEG → Cloud Run (ingress = LB-only) |
| Auth | **In-app Spring Security OAuth2** (Google IdP); restrict to `vishwakarma.ai` hosted domain + allowlist; **roles Author / Reviewer / Admin** |
| Data source (v1) | **Synthetic** — LLM-assisted + manual, scenario/taxonomy-seeded (no real-log import in v1) |
| DPO | **2-candidate, human pick**; prompt author-entered **or** seeded from an approved SFT example |
| LLM drafting | Pluggable `DraftingProvider` (**Gemini** + **Claude**), **drafts tool-call turns too**, **manual fallback** by choice / missing-config |
| Tool catalog | **Admin-editable** function signatures; authors insert tool-call turns from it |
| Taxonomy | **Admin-editable** skills / intents / languages; tag + seed generation |
| Export (v1) | **Simple**: filter approved → JSONL → `data/{sft,dpo}/` + export log (no versioning/split/coverage yet) |
| Training | **Submit from UI** (Reviewer/Admin, guarded, 1-concurrent): method + base + dataset + hyperparams → POST Vertex `tuningJobs` (verified `customBaseModel` recipe) |
| Base models | **Admin-editable catalog** of supported OSS bases (Qwen3-32B, Gemma3-27B, MedGemma…); a foundation tune picks one |
| Versioning | **`vMAJOR.MINOR` per base-model family** (independent lineage each): Foundation→new major, continuation→minor. Promotion = single global **`current`** + `archived` (`candidate` deferred to eval). **Serve-this-version** helper. Default base = **latest successful** |

---

## 3. Functional spec

### 3.1 Roles & access
- **Author:** create/edit own drafts, submit for review, run LLM drafting.
- **Reviewer:** author rights + approve / send-back / export / **submit tuning jobs**.
- **Admin:** all + manage tool catalog, base-models catalog, taxonomy, scenarios, user allowlist, providers.
- Identity from Google OAuth; role from a `users` allowlist (email→role). Non-allowlisted = denied.

### 3.2 Lifecycle (SFT & DPO, identical)
`DRAFT → SUBMITTED → (reviewer) APPROVED | NEEDS_CHANGES(+comment) → author edits → resubmit`; terminal
`ARCHIVED`. Only **APPROVED** items are exportable. Review comments stored per example (thread).

### 3.3 SFT authoring/editing
- Ordered multi-turn editor: **user / model / tool** turns. A tool interaction = a model **functionCall**
  turn (pick a tool from the catalog, fill args) + a **functionResponse** turn (tool result JSON) + a
  following model text turn.
- **LLM draft:** "generate convo from scenario" (scenario template + tags → multi-turn, may include catalog
  tool-calls) and "draft next model turn." Everything editable; manual fallback.
- Tags per example: skill, intent, language, hasToolCall (auto). Inline validation against `contents`/`parts`.

### 3.4 DPO authoring
- Prompt = author-entered context **or** seeded from an approved SFT example's leading user turn(s).
- LLM generates **2 candidate** responses (catalog-aware) → human picks **chosen vs rejected** (editable), or
  enter both manually. Same lifecycle/tags.

### 3.5 Admin catalogs
- **Tool catalog:** `{name, description, params:[{name,type,required,desc}], status}` — drives tool-call
  turns + drafting prompts. *(Strawman tools: `search_workers(skill,location,date)`, `book_worker(worker_id,slot)`,
  `cancel_booking(booking_id)`, `get_refund_status(booking_id)`, `check_availability(skill,area)`, `list_skills()`.)*
- **Base-models catalog:** supported OSS bases `{publisherModel, displayName, family, active}` (seed:
  `qwen/qwen3@qwen3-32b`, `google/gemma3@gemma-3-27b-it`, MedGemma…).
- **Taxonomy:** editable skills / intents / languages. *(Strawman skills: plumber, electrician, painter,
  helper/mover, security guard, carpenter, cleaner; intents: search, book, cancel, refund, price-inquiry,
  availability, complaint, off-topic; languages: Hindi, Hinglish, English.)*
- **Scenarios:** seed library `{title, description, skill?, intent?, promptTemplate}` for generation.
- **Users:** email→role allowlist. **Providers:** select/enable Gemini/Claude, model ids, keys (Secret Manager).

### 3.6 Export
- Reviewer/Admin: pick kind + filters (approved, tags) → serialize → write
  `gs://lm-ai-training-REDACTED-PROJECT/data/{sft,dpo}/<yyyymmdd-hhmmss>.jsonl` → record an `exports` doc
  (kind, gcsUri, exampleIds, count) and stamp `exportedIn` on examples. Re-export = new snapshot file.

### 3.7 Training & model versioning (Reviewer/Admin)
- **Per-base-family `vMAJOR.MINOR`** — each base model is its **own independent lineage**:
  - **Foundation** tune (pick a base from the catalog) → new major in that family `v{maxMajor+1}.0`.
  - **Continuation** from `vA.B` → minor bump `vA.{maxMinorInA+1}` (SFT-more or DPO); the family's
    `baseModel` anchor is reused so architectures never cross. Parent pointer records exact lineage.
  - Tuned display name **`vishwakarma-ai-<base>-vMAJOR.MINOR`** (e.g. `…-qwen3-32b-v1.1`).
- **Submit a tune:** method (SFT/DPO) · **base** = continue-from-version (default **latest successful**) OR
  new foundation (pick base model) · dataset export · hyperparams (epochs/adapterSize/learningRate) → POST
  `tuningJobs` (v1beta1) with `baseModel` + `customBaseModel` (chosen version's checkpoint; omitted for
  foundation) + `outputUri` (`gs://lm-ai-serving-…/tuned/<base>-<vN>/`) + `learningRate`. **Guards:** confirm
  (spends credits); **block if any job RUNNING** (1-concurrent limit).
- **Jobs:** poll status (RUNNING/SUCCEEDED/FAILED); on SUCCEEDED finalize the version (checkpoint URI +
  Vertex model resource); on failure mark `failed`.
- **Models UI:** versions **grouped by base family**, each with its lineage (v1.0→v1.1→…). Actions:
  **new tune from this version**; **promote** = set the single global **`current`** (blessed model) or
  **`archived`** (`candidate` deferred until the eval gate exists); **Serve this version** → shows the
  `gcsCheckpointUri` + a `gcloud storage` download command + a templated **vLLM run command** for an off-GCP
  box (handoff for temporary spin-up/teardown). **No auto-deploy** (no serving here).
- **Reuse:** `TuningService` = the exact `tuningJobs` POST proven in `../terraform/scripts/continue-tune.sh`,
  via the app SA's `aiplatform.user`.

---

## 4. Firestore data model (named DB `vishwakarma-labelling`)
- `users` `{email, role, active, addedBy, addedAt}`
- `tools` `{id, name, description, params:[...], status:active|deprecated, updatedBy, updatedAt}`
- `base_models` `{id, publisherModel:"qwen/qwen3@qwen3-32b", displayName, family:"qwen3-32b", active, updatedBy}`
- `taxonomy` (single doc) `{skills:[], intents:[], languages:[]}`
- `scenarios` `{id, title, description, skill?, intent?, promptTemplate, createdBy}`
- `sft_examples` `{id, tags:{skill,intent,language,hasToolCall}, turns:[{role,kind,text?,toolName?,args?,result?}], status, source:manual|llm, llmModel?, scenarioId?, reviewComments:[{by,text,at}], createdBy, createdAt, updatedAt, exportedIn?:[]}`
- `dpo_pairs` `{id, promptTurns:[...], chosen:{parts}, rejected:{parts}, tags:{...}, source:llm2|manual, fromSftId?, status, reviewComments:[...], createdBy, ...}`
- `exports` `{id, kind:sft|dpo, gcsUri, exampleIds:[], count, createdBy, createdAt}`
- `tuning_jobs` `{id, vertexJobId, method:sft|dpo, baseKind:foundation|continuation, parentVersionId?, datasetExportId, hyperparams, status, outputUri, vertexModelResource?, submittedBy, submittedAt, finishedAt?}`
- `model_versions` `{id, baseModelId, version:"vA.B" (per-family), method, parentVersionId?, datasetExportIds:[], tuningJobId, gcsCheckpointUri, vertexModelResource, status:training|ready|failed, promotion:current|archived|none (candidate deferred to eval), evalResults?, createdBy, createdAt}`

---

## 5. Application architecture (Kotlin / Spring layers)
- **web** — controllers + Thymeleaf views + HTMX fragments.
- **security** — Spring Security OAuth2 login; map principal email → role from `users`; hosted-domain check.
- **service** — `SftService`, `DpoService`, `ExportService`, `TuningService`, `ModelVersionService`,
  `CatalogService` (tools), `BaseModelService`, `TaxonomyService`, `ScenarioService`, `UserService`
  (Arrow `Either` for typed errors).
- **drafting** — `DraftingProvider` interface (`draftSftFromScenario`, `draftNextTurn`, `draftTwoCandidates`)
  + `VertexGeminiDrafting` (calls **`asia-southeast1`** — Mumbai may not host Gemini), `ClaudeDrafting`,
  `ManualOnly`; a factory selects by config; graceful manual fallback on missing-config/error.
- **persistence** — repositories over the `google-cloud-firestore` client.
- **serialization** — `ContentsPartsSerializer` + `ToolCallMapper` (functionCall/functionResponse parts);
  validation (alternating roles, non-empty).
- **gcs** — `Exporter` over `google-cloud-storage`.
- **vertex** — `TuningService` (POST/GET `tuningJobs`, v1beta1) + status poller.

### Pages / routes
`/login` (OAuth) · `/` dashboard (counts by status/kind, recent exports, current model version) ·
`/sft` queue + `/sft/new` + `/sft/{id}` editor (+ submit/approve/sendback) · `/dpo` queue + `/dpo/new` +
`/dpo/{id}` · `/export` (run + history) · `/training` (submit tune + jobs list, Reviewer/Admin) ·
`/models` (versions by base family + lineage + promote + new-tune + **Serve-this-version**) ·
`/admin/{tools,base-models,taxonomy,scenarios,users,providers}` (admin-only). HTMX for add-turn/draft/validate.

---

## 6. Infra — `labelling-tool/infra/` (Terraform, own state; all resources `vishwakarma-labelling-*`)

> **⚠️ Shared project:** `REDACTED-PROJECT` also hosts active `REDACTED-PROJECT-DEV` infra (VPC, Cloud Run, AR,
> LBs, the 4 in-use **global** IPs, default compute SA). **Namespace everything, touch nothing existing.**

- Enable `firestore.googleapis.com`; `google_firestore_database` **named** `vishwakarma-labelling` (Native, `asia-south1`).
- `google_artifact_registry_repository` `vishwakarma-labelling` (Docker, `asia-south1`) for the app image.
- `google_cloud_run_v2_service` `vishwakarma-labelling` (`asia-south1`, min 0, **ingress
  internal-and-cloud-load-balancing**), dedicated SA.
- **App SA** `vishwakarma-labelling-sa`: `roles/datastore.user`, bucket-scoped `roles/storage.objectAdmin` on
  the training bucket, `roles/aiplatform.user` (Gemini + tuning), `roles/secretmanager.secretAccessor`.
- **Secret Manager:** OAuth client secret (+ Anthropic key if Claude enabled).
- **Regional external ALB** (`asia-south1`): regional static IP (quota free — **global IP is maxed**, regional
  is 0/8), serverless NEG → Cloud Run, region URL map + `region_target_https_proxy` + forwarding rule,
  **Google-managed regional cert** for `labelling.vishwakarma.ai`.
- **Auth = in-app (no IAP):** Google OAuth 2.0 client (consent screen = **Internal**, org `REDACTED-ORG-ID`);
  authorized redirect `https://labelling.vishwakarma.ai/login/oauth2/code/google`; client secret in Secret
  Manager. App restricts sign-in to the `vishwakarma.ai` hosted domain + allowlist.
- **Manual one-time:** OAuth consent screen + client; **DNS A record** at the `vishwakarma.ai` registrar →
  LB IP (`vishwakarma.ai` is not in Cloud DNS here).

### Preflight (validated 2026-06-20, read-only) — no hard blockers
- APIs `aiplatform/artifactregistry/compute/iap/run/secretmanager/cloudbuild` enabled; **Firestore not** (one enable).
- **Global IP maxed (4/4)** → no global LB; **`asia-south1` regional IP free (0/8)** → regional ALB.
- Cloud Run + AR **proven** in `asia-south1` (dev backend runs there).
- **Gemini drafting works** (probed `gemini-2.5-flash:generateContent` in `asia-southeast1` → OK, on credits).
- Project in org `REDACTED-ORG-ID` → OAuth consent screen can be **Internal**.

---

## 7. Serialization & open items to confirm at build time (read-only probes first)
- Exact **tool-call** representation in `contents`/`parts` (functionCall/functionResponse) — smoke file has none.
- Exact **managed preference (DPO)** dataset schema (`preferenceOptimizationSpec`) — likely contents prompt + chosen/rejected.
- Regional **managed cert** goes ACTIVE only after the DNS A record resolves (minutes–hours).

---

## 8. Build / deploy
Gradle KTS; `./gradlew jib` → AR; Terraform deploys the Cloud Run revision. **Local dev:** Firestore emulator
+ a fake `DraftingProvider` + a dev Spring profile that bypasses OAuth.

---

## 9. Verification (end-to-end)
1. `cd labelling-tool/infra && terraform apply` → Firestore DB, AR, Cloud Run, regional ALB + cert.
2. `./gradlew jib` → image → Cloud Run revision live.
3. DNS A record → cert ACTIVE → hit `labelling.vishwakarma.ai` → Google OAuth (allowed in / non-allowlisted denied).
4. **Roles:** author can't approve/export/submit-tune; reviewer can.
5. **SFT round-trip:** author drafts (manual + LLM incl. a catalog tool-call), submits; reviewer sends back
   with a comment → author fixes → approve; export → `data/sft/<ts>.jsonl` matches `contents`/`parts`;
   sanity-run `../terraform/scripts/continue-tune.sh sft <uri>`.
6. **DPO round-trip:** 2-candidate generate → pick chosen/rejected → approve → export → `continue-tune.sh dpo <uri>`.
7. **Drafting fallback:** unset provider → UI falls back to manual.
8. **Training/versioning:** foundation SFT on **Qwen3-32B** → `qwen3-32b/v1.0`; foundation SFT on
   **Gemma3-27B** → independent `gemma3-27b/v1.0`; DPO continuation on `qwen3-32b/v1.0` (default latest) →
   `v1.1`, parent in lineage; **promote** one to global `current`; **Serve this version** emits checkpoint
   URI + `gcloud` download + vLLM command; a second submit while one is RUNNING is **blocked**.

---

## 10. Out of scope (v1)
Real-log import; **dataset** versioning / train-val split / coverage dashboard (note: **model** versioning IS
in v1); derive-DPO-from-failure-modes; eval-gate scoring (versions carry an `evalResults` placeholder only,
and `candidate` promotion waits on it); multi-org / RBAC beyond the 3 roles; IAP; CI/CD (manual `jib` deploy).

---

## 11. Next-session checklist (training, resumed)
The tuning pipeline itself is proven and can proceed in parallel with / ahead of the tool:
1. Real **SFT** data (replace smoke) → managed SFT tune (Qwen 3 32B, and optionally other bases).
2. **Preference** data → DPO continuation via `continue-tune.sh dpo …` (`customBaseModel`).
3. **Eval gate** (tool-calling + Hinglish) — unlocks `candidate` promotion.
4. Export → serve off-GCP (vLLM) via the **Serve-this-version** handoff → wire the app.
