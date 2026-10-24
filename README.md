# receipt-eval-sandbox

Self-contained, zero-database sandbox for mastering **LLM-as-a-Judge** (via [Dokimos](https://dokimos.dev/)) and **LLM observability** (OpenTelemetry GenAI conventions + [Arize Phoenix](https://phoenix.arize.com/)) on a bilingual (JP/EN) receipt-extraction task.

- **Extractor**: Spring Boot 3.4 + Spring AI (`gpt-4o-mini` by default) turns messy Japanese receipt text into normalized JSON: merchant, ISO date, English line-item translations, expense category, and the 8%/10% consumption-tax split.
- **Judge**: Dokimos `LLMJudgeEvaluator`s score translation fidelity and category classification; deterministic evaluators gate the tax math.
- **Observability**: every extraction and judge call emits OTLP spans to Phoenix — open `http://localhost:6006` to inspect prompts, completions, token usage, and the custom `receipt.*` attributes.
- **Results history**: runs report to the Dokimos server UI at `http://localhost:8080` when `DOKIMOS_SERVER_URL` is set.

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
```

Watch traces at `http://localhost:6006` (Phoenix) and run history at `http://localhost:8080` (Dokimos).

## Evaluation pipeline (4 tiers, per `golden-receipts.json` case)

| Evaluator | Type | Threshold |
|---|---|---|
| `ExactTotalAndTaxEvaluator` | deterministic | 1.0 |
| `LineItemSumConsistencyEvaluator` | deterministic (items must reconcile with the total; discounts must be negative lines) | 1.0 |
| `TranslationFidelityJudge` | LLM judge (completeness 0.4 / translation nuance 0.4 / tax flags 0.2) | 0.8 |
| `CategoryClassificationJudge` | LLM judge (category fits merchant + items; `allowedCategories` accepted) | 0.85 |

The 15-case golden matrix (`src/test/resources/datasets/golden-receipts.json`): 3× konbini mixed tax, 3× discounts & bundles, 3× katakana electronics, 3× messy OCR (incl. `お預り`/`お釣り` confusion), 3× ambiguous categories.

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
| `APP_PORT` | `8081` | extractor HTTP port (8080 is Dokimos) |

## Version notes (vs. original spec)

- `dev.dokimos:dokimos-junit` **0.30.0** (spec said 0.15.0; 0.30.0 is current — JUnit integration is `@DatasetSource`/`Assertions.assertEval`).
- Spring AI **1.1.8** with the renamed starter `spring-ai-starter-model-openai` (the old `spring-ai-openai-spring-boot-starter` name is pre-GA).
- Spring Boot **3.4.13**; Java 21 (builds fine on newer JDKs via `--release 21`); **Gradle 9.8.2** (wrapper, Kotlin DSL, native BOM support).
- Extractor app runs on **8081** so 8080 stays free for the Dokimos UI.
