package com.sandbox.receipt;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import dev.dokimos.core.Dataset;
import dev.dokimos.core.Example;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Loads the golden dataset from {@code classpath:datasets/golden-receipts.json}
 * and bridges it into both directions we need:
 *
 * <ul>
 *   <li>{@link GoldenReceipt} records for per-case JUnit evaluation and
 *       integrity checks, and</li>
 *   <li>a Dokimos {@link Dataset} for whole-dataset {@code Experiment} runs
 *       (Milestone 3), with the expectation stored as the primary expected
 *       output so LLM judges receive it as {@code EXPECTED_OUTPUT}.</li>
 * </ul>
 */
public final class GoldenReceipts {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final ObjectReader READER =
            MAPPER.readerForListOf(GoldenReceipt.class);

    private GoldenReceipts() {
    }

    public static List<GoldenReceipt> load() {
        try (InputStream in = GoldenReceipts.class.getResourceAsStream("/datasets/golden-receipts.json")) {
            if (in == null) {
                throw new IllegalStateException("golden-receipts.json not found on the test classpath");
            }
            return READER.readValue(in);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to parse golden-receipts.json", e);
        }
    }

    /** Dokimos dataset whose examples carry rawInput as the primary input and
     *  the expectation as the primary expected output. */
    public static Dataset asDokimosDataset() {
        Dataset.Builder builder = Dataset.builder()
                .name("Golden Receipts")
                .description("Bilingual Japanese/English receipt extraction, 15 curated cases");
        for (GoldenReceipt rc : load()) {
            builder.addExample(Example.builder()
                    // key "input"/"output" make the primary input()/expectedOutput() accessors work
                    .input("input", rc.rawInput())
                    .input("id", rc.id())
                    .expectedOutput("output", rc.expected())
                    .metadata("id", rc.id())
                    .metadata("difficulty", rc.difficulty() == null ? "UNKNOWN" : rc.difficulty())
                    .metadata("notes", rc.notes() == null ? "" : rc.notes())
                    .build());
        }
        return builder.build();
    }
}
