# JIRA drafts — Stage 4 GENERATE follow-ups

> **Round 2 (2026-07-22, generation-quality)** — filed after live notebook review, same epic VA-48:
> - [VA-163](https://vishx.atlassian.net/browse/VA-163) **Bug, In Progress** — GQ-1…4: bracket-tag leak (claim ids + `[F1]`), ledger-verbatim guest questions, the UCEC601 anchor/evidence **denial** (planner label invariant), judge truncation no-vote. Code via ultracode workflow this session.
> - [VA-164](https://vishx.atlassian.net/browse/VA-164) **Task ⚠CAL** — KB visibility tier (LLD §9.5, GQ-6): posture-labeled full-ledger background (`ASSERT`/`HEDGE`/`ACKNOWLEDGE-ONLY`, weak claims tagged never filtered), judge symmetry, blocked on the A/B overclaim gate.
> - LLD v1.9 pushed (page 251953154 v10): §9.2/§9.3 GQ amendments, new §9.5, §17 GQ round. VA-161 carries a scope comment (judge site pulled forward).

Follow-up tickets that fall out of commit `73b294d`
*fix(stage4): stop 3.x thinkingLevel mapping from starving GENERATE output* (2026-07-21).
That fix closed the live GENERATE clip, but left two loose ends worth tracking rather than doing inline.

- **Target epic:** [VA-48](https://vishx.atlassian.net/browse/VA-48) — *Stage 4 — Conversation Synthesis & Tuning* (label `stage4`). No existing VA-48 child (VA-49…67, VA-80) covers either item — these are net-new.
- **Status:** ✅ *posted 2026-07-22* — Ticket 1 = [VA-161](https://vishx.atlassian.net/browse/VA-161), Ticket 2 = [VA-162](https://vishx.atlassian.net/browse/VA-162) (Relates-linked). Both To Do under VA-48, label `stage4`.
- **Not a ticket:** the fix itself was a direct bug fix with no ticket; these two only reference the commit.

---

## Ticket 1 = VA-161 — [BE] Propagate the `GeminiTruncation` retry policy beyond GENERATE

- **Type:** Task · **Epic:** VA-48 · **Labels:** `stage4` · **Status target:** To Do

### Overview
Commit `73b294d` made `GeminiDrafting.extractText` throw a distinct `GeminiTruncation` on
`finishReason == MAX_TOKENS` (instead of a downstream Jackson end-of-input error), and taught **only**
`GeminiStage4Drafter` (GENERATE) to catch it and retry at double the cap. Every **other** `gemini.generate(...)`
call site now receives that same exception thrown straight up — a clearer error, but a hard failure with no
recovery. Audit each site and decide, per site, between: **(a)** adopt the double-cap retry, **(b)** raise the
static cap, or **(c)** deliberately fail-fast on the clearer error. Fold the chosen policy into a shared helper so
the truncation contract is defined once, not re-implemented per caller.

### Why now (concrete exposure)
The fix's `GeminiThinking` change (`budget < HIGH_THRESHOLD=4096 → low`) globally protects every **fixed-budget**
site, since all sibling budgets are ≤ 2048. Two residual risks remain:

- **Config-driven starvation.** `ClaimJudgeService` (Stage 3) runs `config.stage3().judgeThinkingBudget`
  (default 512) against an 8192 cap. It is admin-tunable: set to ≥ 4096 it maps to `high` on a `gemini-3.x`
  model and can re-trip the exact starvation the GENERATE fix removed — now silently, via config.
- **Tight-cap hard-fails.** The smallest caps — `Stage4EvalGrading` (2048), `Stage4DpoGeneration` (2048),
  `Stage4Judging` (4096) — are the likeliest to hit `MAX_TOKENS` on a verbose model, and they now hard-fail
  where a doubling retry would have saved the run.

### Call sites in scope (cap / thinking budget)
| Site | maxTokens | thinkingBudget | Note |
|---|---|---|---|
| `stage4/Stage4EvalGrading` | 2048 | 1024 | tight cap, hard-fail on truncation |
| `stage4/Stage4DpoGeneration` | 2048 | 512 | tight cap, hard-fail on truncation |
| `stage4/Stage4Judging` | 4096 | 2048 | hard-fail on truncation |
| `stage3/ClaimJudgeService` | 8192 | `judgeThinkingBudget` (512, **admin-tunable**) | config path can re-trip starvation |
| `stage2/SpeakerAttribution` | 4096 | 2048 | |
| `stage2/ClaimExtractor` | 65535 | — | large cap; low risk, include for completeness |
| `stage3/EntityExtraction` | 16384 | 2048 | large cap |
| `service/QuestionService` | 8192 | 1024 | |

### Acceptance
- One shared truncation-handling seam (helper/policy) rather than a per-caller catch.
- Every site above has an explicit, reviewed decision (retry / raise cap / fail-fast) recorded in code.
- A guard so an admin `judgeThinkingBudget ≥ HIGH_THRESHOLD` on a 3.x model cannot silently starve output
  (clamp, warn, or bounded retry — implementer's call).
- Unit coverage: a truncating fake proves the chosen behavior at ≥ 2 representative sites.

### Refs
`drafting/GeminiDrafting.kt` (`GeminiTruncation`, `extractText`), `drafting/GeminiThinking.kt` (`HIGH_THRESHOLD`),
`stage4/Stage4Generation.kt` (`GeminiStage4Drafter` — the reference retry). Introduced by `73b294d`.

---

## Ticket 2 = VA-162 — [DO] Stage 4 GENERATE — `level:high` repin + live re-validation ⚠CAL

- **Type:** Task · **Epic:** VA-48 · **Labels:** `stage4` · **Status target:** To Do · ⚠CAL owner-run

### Overview
Owner decision 2026-07-22: Stage 4 GENERATE moves to deep thinking — pin directive `thinking = level:high` on
the `stage4-generate` provider row (the caller's 1024 budget is then intentionally ignored). Two things make the
2026-07-21 clip failure structurally unreachable at `high`: `73b294d` (typed `GeminiTruncation` on
`finishReason == MAX_TOKENS` + doubling retry) and the follow-up ladder hardening (`ATTEMPTS` 2 → 3, doubling
clamped at the 65535 flash output ceiling: 16384 → 32768 → 65535 — unit-backed). But the flip is a **stage
repin = calibration event**, and the fix chain has only unit coverage while the original failure was live-only —
so the repin lands together with one recorded live pass.

### Steps / acceptance
- Set `thinking = level:high` on the `stage4-generate` pin row (admin console, VA-76 surface).
- Run GENERATE for a QD-2-style 3–5-exchange conversation on `gemini-3.5-flash`.
- Confirm: the request carries `thinkingLevel: high`; full multi-exchange output parses; any `MAX_TOKENS` clip
  recovers within the ladder (the `generation clipped at N tokens` WARN, then success at the doubled cap);
  no `generation failed after 3 attempts`.
- Record the repin + run as one calibration event (model, pin directive, caps walked, thinking spend reported in
  the truncation message if any, outcome) — same shape as the other ⚠CAL gates (cf. VA-151 / VA-155).
- Needs app :8090 + emulator :8082; owner-run under the standing live-test rule.

### Refs
`stage4/Stage4Generation.kt` (`GeminiStage4Drafter`: `maxTokens = 16_384`, `ATTEMPTS = 3`, `MAX_CAP = 65_535`),
`drafting/GeminiThinking.kt` (the `level:` directive branch), `drafting/GeminiDrafting.kt` (`GeminiTruncation`).
Fix commit `73b294d`; ladder hardening commit pending (message delivered in chat).

---

## Candidate — not filed (raise only if you want it)

**[BE] Make thinking level an explicit per-stage pin, not derived from budget magnitude.**
`HIGH_THRESHOLD = 4096` couples "budget size" to "thinking depth" for `gemini-3.x`: a stage that legitimately
wants a *small* output cap **and** deep thinking cannot express it. Cleaner long-term shape is an explicit
`thinking` dimension on the per-stage pin (VA-76 territory) so the level is chosen, not inferred. Left out of the
two above because it is design work, not fix-hardening — pull it in if you'd rather track it now.
