package optalgos.neighborhood;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OverlapScheduleTest {

    @Test
    void startsAtFullyPermissive() {
        var schedule = new OverlapSchedule(Duration.ofSeconds(10));
        schedule.start();

        assertThat(schedule.allowedFraction()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(schedule.isFullyTightened()).isFalse();
    }

    @Test
    void tightensToZeroWellBeforeTheRunHorizonIsUp() {
        // The anneal has to finish with time to spare, so the run ends on a stretch of genuinely
        // disjoint refinement rather than on whatever the anneal happened to leave behind.
        var schedule = new OverlapSchedule(Duration.ofMillis(40));
        schedule.start();

        sleep(30);

        assertThat(schedule.allowedFraction()).isZero();
        assertThat(schedule.isFullyTightened()).isTrue();
    }

    @Test
    void stagnationTightensTheScheduleEvenWithoutTheClock() {
        // A caller that gives the search far less time than the horizon it was built for must still
        // reach zero tolerance under its own steam - see the class Javadoc.
        var schedule = new OverlapSchedule(Duration.ofHours(1));
        schedule.start();
        assertThat(schedule.allowedFraction()).isGreaterThan(0.9);

        for (int i = 0; i < 100; i++) {
            schedule.onStagnation(0.02);
        }

        assertThat(schedule.allowedFraction()).isZero();
        assertThat(schedule.isFullyTightened()).isTrue();
    }

    @Test
    void neverRunsBackwards() {
        var schedule = new OverlapSchedule(Duration.ofSeconds(10));
        schedule.start();
        double previous = schedule.allowedFraction();

        for (int i = 0; i < 50; i++) {
            schedule.onStagnation(0.01);
            double now = schedule.allowedFraction();
            assertThat(now).isLessThanOrEqualTo(previous);
            previous = now;
        }
    }

    @Test
    void penaltyWeightGrowsAsScheduleTightens() {
        var schedule = new OverlapSchedule(Duration.ofHours(1));
        schedule.start();
        double initialWeight = schedule.penaltyWeight();

        schedule.onStagnation(1.0);

        assertThat(schedule.penaltyWeight()).isGreaterThan(initialWeight);
    }

    @Test
    void reheatRaisesToleranceAgainAndThenDecaysBack() {
        // The one deliberate exception to monotonicity, and this neighborhood's whole reason for
        // existing: a brief return to the infeasible region to escape a disjoint local optimum.
        var schedule = new OverlapSchedule(Duration.ofMillis(400));
        schedule.start();
        schedule.onStagnation(1.0);
        assertThat(schedule.allowedFraction()).isZero();

        assertThat(schedule.reheat(1.0)).isTrue();
        assertThat(schedule.allowedFraction()).isGreaterThan(0.0);
        assertThat(schedule.isReheating()).isTrue();
        assertThat(schedule.isFullyTightened()).isFalse();

        sleep(90);   // longer than a full-strength reheat's decay window (13% of a 400ms horizon)

        assertThat(schedule.allowedFraction()).isZero();
        assertThat(schedule.isReheating()).isFalse();
        assertThat(schedule.isFullyTightened()).isTrue();
    }

    @Test
    void reheatingStopsBeforeTheRunEndsSoTheTailIsAlwaysAFullyTightenedDescent() {
        var schedule = new OverlapSchedule(Duration.ofMillis(50));
        schedule.start();

        sleep(60);   // past the horizon, hence past the reheat deadline

        assertThat(schedule.isPastReheatDeadline()).isTrue();
        assertThat(schedule.reheat(1.0)).isFalse();
        assertThat(schedule.allowedFraction()).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
