plugins {
    application
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    // Placeholder until Phase 7 (GUI). Phase 0 just proves the toolchain builds/runs end to end.
    mainClass.set("optalgos.gui.Main")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("benchmark") {
    group = "application"
    description = "Runs the headless benchmark harness. Pass mode via --args=\"quick\" (default) or --args=\"thorough\"."
    mainClass.set("optalgos.benchmark.BenchmarkRunner")
    classpath = sourceSets["main"].runtimeClasspath
}
