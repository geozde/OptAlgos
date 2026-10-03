package optalgos.benchmark;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runs a supplier on its own dedicated platform thread and measures that thread's CPU time via
 * {@link ThreadMXBean} - not wall-clock time, per the spec's explicit request to log "CPU thread
 * time". A few gotchas worth stating plainly (see the project plan):
 * <ul>
 *   <li>CPU time is cumulative since the thread started, so this always spins up a fresh thread per
 *       call rather than reusing a pooled one - reusing one would silently include earlier calls'
 *       time in the measurement.</li>
 *   <li>Never use a virtual thread here: {@code ThreadMXBean} CPU-time accounting targets
 *       platform/carrier threads, and a virtual thread's carrier can change underneath it, making
 *       attribution unreliable.</li>
 *   <li>The worker thread's own id is measured (via {@code Thread.threadId()}, not the deprecated
 *       {@code getId()}), not the calling thread's.</li>
 * </ul>
 */
public final class ThreadTimedExecutor {

    private ThreadTimedExecutor() {
    }

    public record TimedResult<S>(S result, long cpuTimeNanos) {

        public double cpuTimeSeconds() {
            return cpuTimeNanos / 1_000_000_000.0;
        }
    }

    public static <S> TimedResult<S> runTimed(Supplier<S> algorithmInvocation) {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeSupported()) {
            throw new IllegalStateException("Thread CPU time measurement is not supported on this JVM");
        }
        bean.setThreadCpuTimeEnabled(true);

        AtomicReference<S> resultRef = new AtomicReference<>();
        AtomicReference<RuntimeException> failureRef = new AtomicReference<>();
        long[] cpuTimeNanos = {0};

        Thread worker = new Thread(() -> {
            long threadId = Thread.currentThread().threadId();
            long startNanos = bean.getThreadCpuTime(threadId);
            try {
                resultRef.set(algorithmInvocation.get());
            } catch (RuntimeException e) {
                failureRef.set(e);
            } finally {
                cpuTimeNanos[0] = bean.getThreadCpuTime(threadId) - startNanos;
            }
        }, "benchmark-worker");

        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for benchmark worker", e);
        }

        if (failureRef.get() != null) {
            throw failureRef.get();
        }
        return new TimedResult<>(resultRef.get(), cpuTimeNanos[0]);
    }
}
