package optalgos.algorithm.localsearch;

/** observes a Local Search.
 * Need it to enable GUI, tests and benchmark to track the progress without linking.
 * Benchmark and simple tests can use {@link #noOp()} to ignore intermediate steps.
 */
public interface LocalSearchListener<S> {

    // "something happened, here's the new state" 
    void onAccepted(S state, long iteration);

    /**
     * optinal
     * "search is finished now"
     * called exactly once at the end
     */
    default void onFinished(S finalState, long iterations) {
    }

    /**can be used for neighborhood.onPlateau intermediate steps while trying to find an escape
     * needed for GUI so it can distinguish "real" steps from some trials to escape plateau
     * for most listeners just leave it empty */
    default void onFinishingStep(S state) {
    }

    /** gives us a "do nothing" listener for tests */
    static <S> LocalSearchListener<S> noOp() {
        return new LocalSearchListener<>() {
            @Override
            public void onAccepted(S state, long iteration) {
            }
        };
    }
}
