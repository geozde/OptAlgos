package optalgos.benchmark;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes benchmark results as CSV, one row per (case, instance, algorithm) combination - easy to
 * skim directly and easy to drop into a spreadsheet for comparing algorithm quality, per the spec.
 */
public final class ResultLogger implements AutoCloseable {

    private final PrintWriter writer;

    private ResultLogger(Writer writer) {
        this.writer = new PrintWriter(writer);
        this.writer.println("caseIndex,numRectangles,boxLength,instanceIndex,algorithm,boxCount,areaLowerBound,cpuTimeSeconds");
    }

    public static ResultLogger toFile(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        return new ResultLogger(Files.newBufferedWriter(path));
    }

    public static ResultLogger toWriter(Writer writer) {
        return new ResultLogger(writer);
    }

    public static ResultLogger toConsole() {
        return new ResultLogger(new OutputStreamWriter(System.out));
    }

    public void log(int caseIndex, int numRectangles, int boxLength, int instanceIndex, String algorithm,
                     int boxCount, long areaLowerBound, double cpuTimeSeconds) {
        // Locale.ROOT deliberately, not the JVM default: on a machine whose default locale uses a
        // comma decimal separator (e.g. German), "%.4f" would otherwise emit a comma - indistinguishable
        // from this file's own field separator, silently corrupting the CSV.
        writer.printf(Locale.ROOT, "%d,%d,%d,%d,%s,%d,%d,%.4f%n",
                caseIndex, numRectangles, boxLength, instanceIndex, algorithm, boxCount, areaLowerBound, cpuTimeSeconds);
        writer.flush();
    }

    @Override
    public void close() {
        writer.close();
    }
}
