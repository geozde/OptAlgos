package optalgos.neighborhood;

import java.time.Duration;

/**
 * Tracks annealed "allowed overlap fraction" for RelaxedOverlapNeighborhood: 
 * 
 * starts at 1.0 (any overlap tolerated) and falls to 0.0 (fully disjoint required), 
 * with the penalty growing harsher
 * 
 * owned by exactly one neighborhood instance.
 *
 * Driven by the clock, not by a plateau counter.
 */
final class OverlapSchedule {

    /** Default horizon */
    static final Duration DEFAULT_RUN_HORIZON = Duration.ofSeconds(10);

    /** Tolerance reaches zero this far into the horizon, leaving the rest of the run for genuine
     *  disjoint refinement. Tightening too late leaves no time to convert the compact-but-infeasible
     *  arrangement into a packing. Too early wastes the relaxation entirely. */
    private static final double ANNEAL_FRACTION = 0.45;

    /** No reheat is started past this point
     *  So the tail of every run is a pure, uninterrupted descent to a feasible solution. */
    private static final double REHEAT_DEADLINE_FRACTION = 0.80;

    /** Tolerance a reheat of strength 1 restores, and the span it decays back over (as a fraction of
     *  the horizon). A weaker reheat gets a proportionally shorter window, so every melt is followed
     *  by a descent scaled to how much was melted. */
    private static final double REHEAT_PEAK = 0.85;
    private static final double REHEAT_DECAY_FRACTION = 0.13;
    /**
     * A reheat is followed by at least this many decay windows before another may start, so every
     * reheat gets a descent long enough to actually re-converge. 
     */
    private static final int REHEAT_COOLDOWN_DECAY_WINDOWS = 3;

    private static final double BASE_PENALTY_WEIGHT = 25.0;
    private static final double MAX_PENALTY_WEIGHT = 4000.0;

    private final long runHorizonNanos;
    private final long annealNanos;
    private final long reheatDeadlineNanos;
    private final long reheatDecayNanos;

    private long startNanos = -1;
    private double stagnationProgress = 0.0;
    private double progress = 0.0;
    private long reheatStartedNanos = -1;
    private double reheatStrength = 0.0;

    OverlapSchedule() {
        this(DEFAULT_RUN_HORIZON);
    }

    OverlapSchedule(Duration runHorizon) {
        this.runHorizonNanos = Math.max(1L, runHorizon.toNanos());
        this.annealNanos = Math.max(1L, (long) (runHorizonNanos * ANNEAL_FRACTION));
        this.reheatDeadlineNanos = (long) (runHorizonNanos * REHEAT_DEADLINE_FRACTION);
        this.reheatDecayNanos = Math.max(1L, (long) (runHorizonNanos * REHEAT_DECAY_FRACTION));
    }

    /** Starts the clock. Called once, from the neighborhood's initialSolution(). */
    void start() {
        startNanos = System.nanoTime();
        stagnationProgress = 0.0;
        progress = 0.0;
        reheatStartedNanos = -1;
    }

    /** The currently tolerated overlap ratio for a pair of rectangles*/
    double allowedFraction() {
        return Math.max(1.0 - progress(), reheatBonus());
    }

    /** How costly one unit of excess overlap is in the search objective */
    double penaltyWeight() {
        double t = progress();
        return BASE_PENALTY_WEIGHT + t * t * (MAX_PENALTY_WEIGHT - BASE_PENALTY_WEIGHT);
    }

    boolean isFullyTightened() {
        return progress() >= 1.0 && reheatBonus() <= 0.0;
    }

    /** Advances the anneal by amount on top of whatever the clock has reached. */
    void onStagnation(double amount) {
        stagnationProgress = Math.min(1.0, stagnationProgress + Math.max(0.0, amount));
    }

    /** Re-permits overlap briefly so the search can escape a local optimum */
    boolean reheat(double strength) {
        if (startNanos < 0 || elapsedNanos() >= reheatDeadlineNanos) {
            return false;
        }
        if (progress() < 1.0) {
            // The anneal has not finished on its own yet.
            return false;
        }
        double clamped = Math.max(0.05, Math.min(1.0, strength));
        if (reheatStartedNanos >= 0 && System.nanoTime() - reheatStartedNanos
                < decayNanosFor(reheatStrength) * REHEAT_COOLDOWN_DECAY_WINDOWS) {
            return false;
        }
        reheatStartedNanos = System.nanoTime();
        reheatStrength = clamped;
        return true;
    }

    boolean isReheating() {
        return reheatBonus() > 0.0;
    }
    
    boolean isPastReheatDeadline() {
        return startNanos >= 0 && elapsedNanos() >= reheatDeadlineNanos;
    }

    private double reheatBonus() {
        if (reheatStartedNanos < 0) {
            return 0.0;
        }
        long decayNanos = decayNanosFor(reheatStrength);
        long since = System.nanoTime() - reheatStartedNanos;
        if (since >= decayNanos) {
            return 0.0;
        }
        return REHEAT_PEAK * reheatStrength * (1.0 - since / (double) decayNanos);
    }

    private long decayNanosFor(double strength) {
        return Math.max(1L, (long) (reheatDecayNanos * Math.max(0.05, strength)));
    }

    private double progress() {
        double timeProgress = startNanos < 0 ? 0.0 : Math.min(1.0, elapsedNanos() / (double) annealNanos);
        progress = Math.max(progress, Math.max(timeProgress, stagnationProgress));
        return progress;
    }

    /** How much of the run has been spent so far - the neighborhood needs this to judge how
     *  expensive its own convergence was before deciding how hard it can afford to melt. */
    long elapsedNanos() {
        return startNanos < 0 ? 0L : System.nanoTime() - startNanos;
    }

    /** Time left before reheating is cut off for good - the budget any further melt has to
     *  re-freeze inside. */
    long nanosUntilReheatDeadline() {
        return startNanos < 0 ? 0L : Math.max(0L, reheatDeadlineNanos - elapsedNanos());
    }
}
