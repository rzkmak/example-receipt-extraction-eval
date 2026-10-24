plugins {
    java
    id("org.springframework.boot") version "3.4.13"
}

group = "com.sandbox"
version = "0.1.0-SNAPSHOT"
description = "Bilingual receipt extractor with Dokimos LLM-as-a-Judge evaluation and Phoenix OTel observability"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

val springAiVersion = "1.1.8"
val dokimosVersion = "0.30.0"

dependencies {
    // Native BOM support (no io.spring.dependency-management plugin needed):
    // Boot's BOM manages Spring/OTel/Micrometer versions, Spring AI's BOM the rest.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.13"))
    implementation(platform("org.springframework.ai:spring-ai-bom:$springAiVersion"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Tracing: micrometer observations -> OpenTelemetry spans -> OTLP gRPC to Phoenix
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Layer 4 (three pillars): Prometheus scrape endpoint for the aggregate
    // SLIs (cost counters, duration percentiles, invariant violations), and
    // JSON-structured logs with MDC correlation.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:8.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")

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
