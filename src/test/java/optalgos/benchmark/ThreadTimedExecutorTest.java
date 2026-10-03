package optalgos.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadTimedExecutorTest {

    @Test
    void returnsTheSupplierResult() {
        var result = ThreadTimedExecutor.runTimed(() -> 42);
        assertThat(result.result()).isEqualTo(42);
    }

    @Test
    void measuresPositiveCpuTimeForActualWork() {
        var result = ThreadTimedExecutor.runTimed(() -> {
            long sum = 0;
            for (int i = 0; i < 50_000_000; i++) {
                sum += i;
            }
            return sum;
        });
        assertThat(result.cpuTimeNanos()).isGreaterThan(0);
        assertThat(result.cpuTimeSeconds()).isGreaterThan(0.0);
    }

    @Test
    void propagatesExceptionsFromTheSupplier() {
        assertThatThrownBy(() -> ThreadTimedExecutor.runTimed(() -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void runsOnADifferentThreadThanTheCaller() {
        long callerThreadId = Thread.currentThread().threadId();
        var result = ThreadTimedExecutor.runTimed(() -> Thread.currentThread().threadId());
        assertThat(result.result()).isNotEqualTo(callerThreadId);
    }
}
