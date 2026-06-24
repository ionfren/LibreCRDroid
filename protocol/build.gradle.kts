// Pure Kotlin/JVM library — NO Android dependencies. This is the byte-perfect
// protocol layer (framing, CRC, AES-CCM, white-box LibAES, P-256, data plane).
// Being JVM-only means it runs as plain JUnit on the dev machine, so the
// golden-vector cross-validation against Swift runs without an emulator.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        // The white-box builders use ULong/UInt(Array) for exact wrapping math.
        optIn.add("kotlin.ExperimentalUnsignedTypes")
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
