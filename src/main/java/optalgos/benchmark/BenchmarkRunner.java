package optalgos.benchmark;

import optalgos.algorithm.greedy.GreedyListener;
import optalgos.algorithm.localsearch.LocalSearchListener;
import optalgos.algorithm.localsearch.TerminationCriterion;
import optalgos.rectpack.RectpackAlgorithms;
import optalgos.rectpack.generator.InstanceGenerator;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Standalone, GUI-free entry point for comparing every algorithm x neighborhood/strategy combination
 * across many instances, logging box count and CPU thread time (see the project plan). No GUI
 * dependency - this is a plain {@code main}, runnable via {@code gradlew benchmark} or
 * {@code gradlew benchmark --args="thorough"}.
 *
 * <p>Two presets:
 * <ul>
 *   <li>{@code quick} (default) - small instances, finishes in a couple of minutes; demoable live at
 *       the Testat.</li>
 *   <li>{@code thorough} - larger instances up to 1000 rectangles, meant to be run once ahead of time,
 *       with the resulting log brought to the Testat as evidence.</li>
 * </ul>
 */
public final class BenchmarkRunner {

    // 10s matches the spec's own number directly - confirmed acceptable as a soft target (occasional
    // overrun up to ~12s is fine) rather than a hard wall, so there's no need to sacrifice search
    // time to a stricter internal margin than that. TerminationCriterion only checks BETWEEN
    // iterations, and a single permutation-neighborhood iteration at n=1000 (up to 16 expensive
    // "materializeFrom" partial re-placements per candidateMoves() call - see its class Javadoc) can
    // itself take over a second, so real runs land in roughly the 10-11.5s range at that scale -
    // comfortably inside the accepted ~12s ceiling, verified against the worst overrun observed on a
    // real benchmark instance.
    private static final Duration LOCAL_SEARCH_BUDGET = Duration.ofMillis(10_000);

    private static final List<BenchmarkCase> QUICK_CASES = List.of(
            new BenchmarkCase(3, 20, 3, 10, 3, 10, 20),
            new BenchmarkCase(3, 50, 3, 15, 3, 15, 30),
            new BenchmarkCase(2, 100, 3, 20, 3, 20, 50)
    );

    private static final List<BenchmarkCase> THOROUGH_CASES = List.of(
            new BenchmarkCase(3, 50, 3, 15, 3, 15, 30),
            new BenchmarkCase(3, 200, 3, 20, 3, 20, 50),
            new BenchmarkCase(3, 500, 3, 25, 3, 25, 70),
            new BenchmarkCase(3, 1000, 3, 25, 3, 25, 80)
    );

    private BenchmarkRunner() {
    }

    public static void main(String[] args) throws IOException {
        String mode = args.length > 0 ? args[0] : "quick";
        List<BenchmarkCase> cases = switch (mode) {
            case "quick" -> QUICK_CASES;
            case "thorough" -> THOROUGH_CASES;
            default -> throw new IllegalArgumentException("Unknown mode '" + mode + "' - expected 'quick' or 'thorough'");
        };

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path outputPath = Path.of("build", "benchmark-results", mode + "-" + timestamp + ".csv");

        System.out.println("Running benchmark: mode=" + mode + ", " + cases.size() + " case(s), output=" + outputPath);
        System.out.println("Local search budget per run: " + (LOCAL_SEARCH_BUDGET.toMillis() / 1000.0) + "s");

        try (ResultLogger logger = ResultLogger.toFile(outputPath)) {
            run(cases, LOCAL_SEARCH_BUDGET, logger);
        }

        System.out.println("Done. Results written to " + outputPath);
    }

    /** Runs every case against every algorithm configuration, logging as it goes. Takes the local
     *  search time budget as a parameter (rather than hardcoding {@link #LOCAL_SEARCH_BUDGET}) so
     *  tests can exercise the full pipeline with a short budget instead of the real 10 seconds. */
    public static void run(List<BenchmarkCase> cases, Duration localSearchBudget, ResultLogger logger) {
        for (int caseIndex = 0; caseIndex < cases.size(); caseIndex++) {
            runCase(caseIndex, cases.get(caseIndex), localSearchBudget, logger);
        }
    }

    private static void runCase(int caseIndex, BenchmarkCase benchmarkCase, Duration localSearchBudget, ResultLogger logger) {
        System.out.printf("Case %d: %d instance(s) of %d rectangles, L=%d%n",
                caseIndex, benchmarkCase.numInstances(), benchmarkCase.numRectangles(), benchmarkCase.boxLength());

        for (int instanceIndex = 0; instanceIndex < benchmarkCase.numInstances(); instanceIndex++) {
            long seed = seedFor(caseIndex, instanceIndex);
            List<Rectangle> rectangles = new InstanceGenerator(seed).generate(benchmarkCase.toInstanceConfig());
            long areaLowerBound = areaLowerBound(rectangles, benchmarkCase.boxLength());
            int boxLength = benchmarkCase.boxLength();

            runAndLog(caseIndex, benchmarkCase, instanceIndex, "greedy-decreasing-area", areaLowerBound, logger, () ->
                    RectpackAlgorithms.decreasingAreaGreedy()
                            .run(PackingSolution.empty(boxLength), rectangles, GreedyListener.noOp())
                            .boxCount());

            runAndLog(caseIndex, benchmarkCase, instanceIndex, "greedy-best-fit-adaptive", areaLowerBound, logger, () ->
                    RectpackAlgorithms.bestFitAdaptiveGreedy(new Random(seed))
                            .run(PackingSolution.empty(boxLength), rectangles, GreedyListener.noOp())
                            .boxCount());

            runAndLog(caseIndex, benchmarkCase, instanceIndex, "local-search-geometric", areaLowerBound, logger, () ->
                    RectpackAlgorithms.geometricLocalSearch(rectangles, boxLength, new Random(seed))
                            .run(TerminationCriterion.maxDuration(localSearchBudget), LocalSearchListener.noOp())
                            .boxCount());

            runAndLog(caseIndex, benchmarkCase, instanceIndex, "local-search-permutation", areaLowerBound, logger, () ->
                    RectpackAlgorithms.permutationLocalSearch(rectangles, boxLength, new Random(seed))
                            .run(TerminationCriterion.maxDuration(localSearchBudget), LocalSearchListener.noOp())
                            .placement().boxCount());

            runAndLog(caseIndex, benchmarkCase, instanceIndex, "local-search-relaxed-overlap", areaLowerBound, logger, () ->
                    RectpackAlgorithms.relaxedOverlapLocalSearch(rectangles, boxLength, new Random(seed))
                            .run(TerminationCriterion.maxDuration(localSearchBudget), LocalSearchListener.noOp())
                            .boxCount());
        }
    }

    private static void runAndLog(int caseIndex, BenchmarkCase benchmarkCase, int instanceIndex, String algorithmName,
                                   long areaLowerBound, ResultLogger logger, Supplier<Integer> algorithmInvocation) {
        ThreadTimedExecutor.TimedResult<Integer> timed = ThreadTimedExecutor.runTimed(algorithmInvocation);
        logger.log(caseIndex, benchmarkCase.numRectangles(), benchmarkCase.boxLength(), instanceIndex, algorithmName,
                timed.result(), areaLowerBound, timed.cpuTimeSeconds());
        System.out.printf(Locale.ROOT, "  [%s] instance %d: %d boxes (lower bound %d) in %.2fs CPU%n",
                algorithmName, instanceIndex, timed.result(), areaLowerBound, timed.cpuTimeSeconds());
    }

    private static long areaLowerBound(List<Rectangle> rectangles, int boxLength) {
        long totalArea = rectangles.stream().mapToLong(Rectangle::area).sum();
        long boxArea = (long) boxLength * boxLength;
        return (totalArea + boxArea - 1) / boxArea;
    }

    /** Deterministic per-(case, instance) seed, so a benchmark run is reproducible. */
    private static long seedFor(int caseIndex, int instanceIndex) {
        return caseIndex * 1_000_003L + instanceIndex * 97L + 1;
    }
}
