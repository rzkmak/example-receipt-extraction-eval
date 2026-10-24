# example-receipt-extraction-eval

> **Why this project exists:** this is a **learning sandbox**, not a production app. The goal is to learn — end to end and hands-on — how to orchestrate **observability and evaluation for LLM applications**: closed-loop tracing, LLM-as-a-Judge evaluation, cost telemetry, guardrails, and prompt-experiment lineage. The receipt extractor is deliberately tiny so that almost all of the code is about the observability/evaluation machinery, not the business logic — swap the domain and the patterns carry over to any LLM app.

Self-contained, zero-database sandbox for mastering **LLM-as-a-Judge** (via [Dokimos](https://dokimos.dev/)) and **LLM observability** (OpenTelemetry GenAI conventions + [Arize Phoenix](https://phoenix.arize.com/)) on a bilingual (JP/EN) receipt-extraction task.

- **Extractor**: Spring Boot 3.4 + Spring AI (`gpt-4o-mini` by default) turns messy Japanese receipt text into normalized JSON: merchant, ISO date, English line-item translations, expense category, and the 8%/10% consumption-tax split.
- **Judge**: Dokimos `LLMJudgeEvaluator`s score translation fidelity and category classification; deterministic evaluators gate the tax math.
- **Observability**: every extraction and judge call emits OTLP spans to Phoenix — open `http://localhost:6006` to inspect prompts, completions, token usage, and the custom `receipt.*` attributes.
- **Results history**: runs report to the Dokimos server UI at `http://localhost:8080` when `DOKIMOS_SERVER_URL` is set.
- **V2 observability** (closed loop): eval scores are attached back to the traces that produced them, USD cost + prompt-cache economics are tracked per role, PII never reaches telemetry, and live traffic is checked with reference-free math invariants — see [LLM observability V2](#llm-observability-v2-7-layers).

## Quick start

```bash
# 1. Infrastructure (Phoenix + Dokimos server + its Postgres)
docker compose up -d

# 2. Configure
export OPENAI_API_KEY=sk-...        # extractor + judge
export DOKIMOS_SERVER_URL=http://localhost:8080   # optional: report runs to the UI

# 3. Offline checks (no LLM calls)
./gradlew test

# 4. Full evaluation harness (per-case JUnit gating)
./gradlew test --tests "com.sandbox.receipt.ReceiptEvaluationTest"

# 5. Whole-dataset experiment with pass-rate gate
./gradlew test --tests "com.sandbox.receipt.ReceiptExperimentTest"

# 6. Serve the extractor
./gradlew bootRun
curl -s localhost:8081/api/v1/receipts/extract \
  -H 'Content-Type: application/json' \
  -d '{"rawInput":"セブン-イレブン 目黒駅東口店\n※手巻おにぎり 熟成紅しゃけ ¥189\nレジ袋 ¥3\n合計 ¥352","testcaseId":"manual-1"}'

# 7. Vision path: extract directly from a receipt photo (no OCR pre-pass).
#    Requires a vision-capable extractor model (gpt-4o-mini is one).
curl -s localhost:8081/api/v1/receipts/extract-image \
  -F "file=@receipt.jpg;type=image/jpeg" \
  -F "testcaseId=manual-img-1"
```

Watch traces at `http://localhost:6006` (Phoenix) and run history at `http://localhost:8080` (Dokimos).

A [Bruno](https://www.usebruno.com/) collection lives in `bruno/` with both endpoints ready to fire — open that folder in Bruno, pick the `Local` environment, and set `receiptImagePath` to a real receipt photo for the image request.

## Evaluation pipeline (4 tiers, per `golden-receipts.json` case)

| Evaluator | Type | Threshold |
|---|---|---|
| `ExactTotalAndTaxEvaluator` | deterministic | 1.0 |
| `LineItemSumConsistencyEvaluator` | deterministic (items must reconcile with the total; discounts must be negative lines) | 1.0 |
| `TranslationFidelityJudge` | LLM judge (completeness 0.4 / translation nuance 0.4 / tax flags 0.2) | 0.8 |
| `CategoryClassificationJudge` | LLM judge (category fits merchant + items; `allowedCategories` accepted) | 0.85 |

The 15-case golden matrix (`src/test/resources/datasets/golden-receipts.json`): 3× konbini mixed tax, 3× discounts & bundles, 3× katakana electronics, 3× messy OCR (incl. `お預り`/`お釣り` confusion), 3× ambiguous categories.

## LLM observability V2 (7 layers)

Every extraction runs as one trace:

```
receipt.pipeline                          lineage, testcase_id, prompt.template.sha256, gen_ai.usage.*
├── guardrail.pii_redact                  PII scrubbed BEFORE any prompt attribute exists
├── spring_ai.chat.*                      Spring AI's own span (model call, prompt & completion)
├── receipt.schema_validation             raw string -> ReceiptData, error type + retry telemetry
└── schema.validate_and_invariants        reference-free invariants + input drift characteristics
```

| # | Layer | Where | Key telemetry |
|---|---|---|---|
| 1 | Eval feedback loop | `EvalSpanRegistry` + `SpanAnnotatingEvaluator` (test scope) | `dokimos.eval.<name>.score/passed/reason` span attrs; async `POST /v1/span_annotations` to Phoenix. The JUnit harness keeps the parent span open until evaluators finish, so in-process attribute writes land before export. |
| 2 | Cost & prompt caching | `LLMCostRecorder` (extractor + judge via `CostTrackingChatModel`) | span attrs `gen_ai.usage.{input,output,cache_read_input}_tokens`, `gen_ai.usage.cost_usd`, `gen_ai.role`; metrics `llm.cost.usd.total{role,model}` (counter), `llm.prompt.cache.hit.ratio{model}` (gauge) |
| 3 | Schema reliability | `receipt.schema_validation` span in `ReceiptExtractorService` | `schema.validation.success`, `schema.error.type` (`JsonParseException` / `MismatchedInputException` / `MissingRequiredField`), `schema.retry.attempt_count`, `schema.raw_malformed_payload` (failures only); self-correction retry re-prompts the model with the invalid output |
| 4 | Three pillars | OTLP → Phoenix; Micrometer (`/actuator/prometheus`); Logstash JSON logs | `receipt.extraction.duration{model,difficulty}` timer, `receipt.tokens.throughput.tps`, `receipt.invariant.violation.total{type}`; logs carry `trace_id`, `span_id`, `testcase_id` via MDC |
| 5 | PII guardrails | `PiiScrubber`, runs before `ChatClient` | card numbers (Luhn-validated, 12–19 digits) → `[REDACTED_CARD]`, `会員番号`/`ポイントカード` → `[REDACTED_MEMBER_ID]`, `TEL:` → `[REDACTED_PHONE]`; span attrs `guardrail.pii.detected/redacted_count/entity_types` |
| 6 | Lineage | `ExperimentLineageSpanProcessor` (auto-registered as a `SpanProcessor` bean) | `dokimos.experiment.name/run_id`, `prompt.template.id/version` on every span; `prompt.template.sha256` (first 12 hex of SHA-256 of the effective system prompt) detects uncommitted prompt edits |
| 7 | Online drift & invariants | `ReceiptInvariants`, live mode only for HIGH_RISK | `invariant.line_items_sum_matches_subtotal`, `invariant.tax_bounds_valid` ([0%, 10.5%] of subtotal), `invariant.date_not_future`, drift `input.char_length`, `input.cjk_char_ratio`; failing live extractions get `sampling.priority=HIGH_RISK` and are forwarded for async judge auditing when `DOKIMOS_AUDIT_ENABLED=true` |

All of it is configured under `receipt.observability.*` in `application.yml` (see the model pricing table there — judge `gpt-4o` tokens cost ~15x extractor `gpt-4o-mini` tokens, which is exactly why the cost metric carries a `role` tag). The offline test `ObservabilityPipelineTest` verifies layers 2, 3, 5, 6 and 7 against an in-memory span exporter — no API key, no Phoenix required.

### Image input (vision path)

`POST /api/v1/receipts/extract-image` accepts a multipart receipt photo (PNG/JPEG/WebP, ≤10 MB) and sends it as media to a vision-capable extractor model — no OCR pre-pass. The pipeline (schema retry, invariants, HIGH_RISK tagging, cost tracking, lineage) is identical to the text path; only two things differ:

- **PII**: pixels can't be regex-scrubbed. The guardrail span records `guardrail.pii.input_scrubbed=false`, anything the model transcribed is scrubbed *before* it lands in a span attribute (e.g. the malformed-payload capture), and for zero PII in telemetry set `CHAT_COMPLETION_TELEMETRY=false` to disable completion capture.
- **Drift**: `input.image.bytes` / `input.image.media_type` replace the text-based CJK-ratio characteristics.

`ReceiptImageExtractionTest` verifies all of this offline with a capturing stub model.

## Experiments (Milestone 3)

| Experiment | How |
|---|---|
| **A — prompt regression** | `RECEIPT_TAX_HINT=false ./gradlew test --tests "com.sandbox.receipt.ReceiptEvaluationTest"` — the `※ = 8%` hint disappears from the system prompt; the suite should fail on tax-flag-heavy cases. |
| **B — model swap** | `EXTRACTOR_MODEL=<smaller-model>` (for a local Ollama: also set `OPENAI_BASE_URL=http://localhost:11434`), keep `JUDGE_MODEL=gpt-4o`. Compare pass rate, latency and token usage across Dokimos runs. |
| **C — judge bias check** | Feed a verbose-but-literal translation vs a concise-but-accurate one through `TranslationFidelityJudge` (its rubric weights semantics, not length). |

`ReceiptExperimentTest` accepts `RECEIPT_PASS_RATE_THRESHOLD` (default 0.6) as the CI gate and reports to the Dokimos server when `DOKIMOS_SERVER_URL` is set.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | — (required for LLM tests) | extractor + judge |
| `OPENAI_BASE_URL` | `https://api.openai.com` | extractor endpoint; any OpenAI-compatible host (Ollama, llama.cpp, vLLM) |
| `JUDGE_BASE_URL` | falls back to `OPENAI_BASE_URL` | judge endpoint override |
| `EXTRACTOR_MODEL` | `gpt-4o-mini` | extractor model |
| `JUDGE_MODEL` | `gpt-4o` | judge model |
| `RECEIPT_TAX_HINT` | `true` | `false` = Experiment A |
| `DOKIMOS_SERVER_URL` | unset | report runs to the UI |
| `RECEIPT_PASS_RATE_THRESHOLD` | `0.6` | experiment CI gate |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4317` | Phoenix OTLP gRPC |
| `PHOENIX_URL` | `http://localhost:6006` | Phoenix HTTP API (span annotations) |
| `PHOENIX_ANNOTATIONS_ENABLED` | `true` | eval verdicts → Phoenix `/v1/span_annotations` |
| `DOKIMOS_AUDIT_ENABLED` | `false` | forward HIGH_RISK live extractions for async judge audit |
| `EXPERIMENT_NAME` / `EXPERIMENT_RUN_ID` | baseline name / per-process timestamp | lineage stamped on every span |
| `SCHEMA_MAX_ATTEMPTS` | `2` | model calls per extraction (1 + retries) |
| `PII_REDACTION_ENABLED` | `true` | pre-inference PII scrubbing |
| `APP_PORT` | `8081` | extractor HTTP port (8080 is Dokimos) |

## Version notes (vs. original spec)

- `dev.dokimos:dokimos-junit` **0.30.0** (spec said 0.15.0; 0.30.0 is current — JUnit integration is `@DatasetSource`/`Assertions.assertEval`).
- Spring AI **1.1.8** with the renamed starter `spring-ai-starter-model-openai` (the old `spring-ai-openai-spring-boot-starter` name is pre-GA).
- Spring Boot **3.4.13**; Java 21 (builds fine on newer JDKs via `--release 21`); **Gradle 9.8.2** (wrapper, Kotlin DSL, native BOM support).
- Extractor app runs on **8081** so 8080 stays free for the Dokimos UI.
