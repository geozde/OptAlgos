package optalgos.benchmark;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunnerTest {

    @Test
    void runsEveryAlgorithmOnEveryInstanceWithoutCrashing() {
        List<BenchmarkCase> tinyCases = List.of(
                new BenchmarkCase(2, 15, 3, 8, 3, 8, 15)
        );
        StringWriter sink = new StringWriter();

        try (ResultLogger logger = ResultLogger.toWriter(sink)) {
            BenchmarkRunner.run(tinyCases, Duration.ofMillis(200), logger);
        }

        String[] lines = sink.toString().split("\\R");
        // 1 header + (2 instances x 5 algorithm configurations) = 11 lines
        assertThat(lines).hasSize(11);
        String output = sink.toString();
        assertThat(output).contains("greedy-decreasing-area")
                .contains("greedy-best-fit-adaptive")
                .contains("local-search-geometric")
                .contains("local-search-permutation")
                .contains("local-search-relaxed-overlap");
    }
}
