package optalgos.benchmark;

import org.junit.jupiter.api.Test;

import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ResultLoggerTest {

    @Test
    void writesAHeaderAndOneRowPerLogCall() {
        StringWriter sink = new StringWriter();
        try (ResultLogger logger = ResultLogger.toWriter(sink)) {
            logger.log(0, 20, 30, 0, "greedy-decreasing-area", 5, 4, 0.0123);
            logger.log(0, 20, 30, 1, "local-search-geometric", 4, 4, 1.5);
        }

        String[] lines = sink.toString().split("\\R");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo("caseIndex,numRectangles,boxLength,instanceIndex,algorithm,boxCount,areaLowerBound,cpuTimeSeconds");
        assertThat(lines[1]).isEqualTo("0,20,30,0,greedy-decreasing-area,5,4,0.0123");
        assertThat(lines[2]).isEqualTo("0,20,30,1,local-search-geometric,4,4,1.5000");
    }
}
