package optalgos.algorithm.greedy;

/** Generic class that observes a greedy instance's run.
 *  used by the GUI to stream frames, and by tests */
public interface GreedyListener<S> {

    // must be implemented!
    void onStepCompleted(S partial, int step, int totalSteps);

    /**
     * optinal
     * "search is finished now"
     * called exactly once at the end
     */
    default void onFinished(S finalSolution) {
    }

    /** gives us a "do nothing" listener for tests */
    static <S> GreedyListener<S> noOp() {
        return new GreedyListener<>() {
            @Override
            public void onStepCompleted(S partial, int step, int totalSteps) {
            }
        };
    }
}
