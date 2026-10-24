plugins {
    java
    id("org.springframework.boot") version "4.1.2"
}

group = "com.sandbox"
version = "0.1.0-SNAPSHOT"
description = "Bilingual receipt extractor with Dokimos LLM-as-a-Judge evaluation and Phoenix OTel observability"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    mavenCentral()
}

val springAiVersion = "2.0.2"
val dokimosVersion = "0.30.0"

dependencies {
    // Native BOM support (no io.spring.dependency-management plugin needed):
    // Boot's BOM manages Spring/OTel/Micrometer versions, Spring AI's BOM the rest.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))

    // Boot 4 renamed the servlet web starter (modularization): web -> webmvc.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Tracing: micrometer observations -> OpenTelemetry spans -> OTLP gRPC to
    // Phoenix. Boot 4 moved the OTel auto-configuration (the OpenTelemetry SDK
    // bean, OTLP export) into this starter; the bridge and exporter come with it.
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    // Layer 4 (three pillars): Prometheus scrape endpoint for the aggregate
    // SLIs (cost counters, duration percentiles, invariant violations), and
    // JSON-structured logs with MDC correlation. 9.0 = Jackson 3 (Boot 4's JSON).
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:9.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

    // Boot 4 modularization: TestRestTemplate moved into spring-boot-resttestclient
    // and is only auto-configured with @AutoConfigureTestRestTemplate.
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")

    // In-memory span exporter so offline tests can assert span attributes.
    testImplementation("io.opentelemetry:opentelemetry-sdk-testing")

    // Dokimos: LLM-as-a-Judge evaluation framework (test scope only)
    testImplementation("dev.dokimos:dokimos-junit:$dokimosVersion")
    testImplementation("dev.dokimos:dokimos-spring-ai:$dokimosVersion")
    testImplementation("dev.dokimos:dokimos-server-client:$dokimosVersion")
}

tasks.withType<JavaCompile> {
    // Spring needs runtime-visible parameter names (constructor injection etc.)
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// ── Jackson 3 guard ─────────────────────────────────────────────────────────
// Jackson 2 (com.fasterxml.jackson) sits on the compile classpath transitively
// — the official OpenAI Java SDK inside Spring AI 2.0 uses it internally — so
// a stray Jackson 2 reference in first-party code would compile and silently
// run Jackson 2. Both spellings are guarded: any occurrence (import or
// fully-qualified) in src/main, and import statements in src/test, where
// ContextSmokeTest names the Jackson 2 class on purpose to assert it is NOT
// auto-configured. The com.fasterxml.jackson.annotation package is exempt:
// Jackson 3 deliberately keeps using the shared 2.x annotations artifact.
val jackson2Reference = Regex("""com\.fasterxml\.jackson\.(?!annotation)""")
val jackson2Import = Regex("""import\s+(static\s+)?com\.fasterxml\.jackson\.(?!annotation)""")

val verifyJackson3 = tasks.register("verifyJackson3") {
    group = "verification"
    description = "Fails if first-party code references legacy Jackson 2 instead of Jackson 3 (tools.jackson)."
    doLast {
        val offenders =
            fileTree("src/main/java") { include("**/*.java") }
                .filter { it.readText().contains(jackson2Reference) }
                .map { it.absolutePath } +
                fileTree("src/test/java") { include("**/*.java") }
                    .filter { it.readText().contains(jackson2Import) }
                    .map { it.absolutePath }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Legacy Jackson 2 references in first-party code — use tools.jackson " +
                    "(com.fasterxml.jackson.annotation is the one allowed package):\n  " +
                    offenders.joinToString("\n  "))
        }
    }
}

tasks.named("check") {
    dependsOn(verifyJackson3)
}

// ── Sandbox infrastructure (docker compose) ────────────────────────────────
// ./gradlew composeUp   -> Phoenix (6006/4317) + Dokimos server (8080) + Postgres
// ./gradlew composeDown -> stop and remove the stack
tasks.register<Exec>("composeUp") {
    group = "sandbox"
    description = "Starts the sandbox infrastructure (Phoenix, Dokimos server, Postgres) via docker compose."
    // --wait blocks until containers are running/healthy, so a following
    // bootRun or evaluation run can rely on the OTLP endpoint being up.
    commandLine("docker", "compose", "up", "-d", "--wait")
}

tasks.register<Exec>("composeDown") {
    group = "sandbox"
    description = "Stops and removes the sandbox infrastructure containers."
    commandLine("docker", "compose", "down")
}
