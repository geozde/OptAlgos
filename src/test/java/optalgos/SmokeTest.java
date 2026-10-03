package optalgos;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Gradle + JUnit 5 + AssertJ toolchain is wired correctly (Phase 0 "done" criterion:
 * {@code gradlew build test} green). Safe to delete once real tests exist in later phases.
 */
class SmokeTest {

    @Test
    void buildToolchainWorks() {
        assertThat(1 + 1).isEqualTo(2);
    }
}
