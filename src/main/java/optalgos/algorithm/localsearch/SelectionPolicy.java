package optalgos.algorithm.localsearch;

import java.util.List;
import java.util.Optional;

/** How LocalSearch picks a candidate move at each step. */
public enum SelectionPolicy {
    FIRST_IMPROVEMENT,
    BEST_IMPROVEMENT;

    /** entry point to switch one of the strategies */
    <S> Optional<? extends Move<S>> select(List<? extends Move<S>> moves) {
        return switch (this) {
            case FIRST_IMPROVEMENT -> selectFirstImproving(moves);
            case BEST_IMPROVEMENT -> selectBestImproving(moves);
        };
    }

    /** walk list in order and return first move which improves score */
    private static <S> Optional<? extends Move<S>> selectFirstImproving(List<? extends Move<S>> moves) {
        for (Move<S> move : moves) {
            // remember: we are minimising!
            if (move.scoreDelta() < 0) {
                return Optional.of(move);
            }
        }
        return Optional.empty();
    }

    /** scan hole list of moves and pick move with biggest improvement */
    private static <S> Optional<? extends Move<S>> selectBestImproving(List<? extends Move<S>> moves) {
        Move<S> best = null;
        for (Move<S> move : moves) {
            if (move.scoreDelta() < 0 && (best == null || move.scoreDelta() < best.scoreDelta())) {
                best = move;
            }
        }
        return Optional.ofNullable(best);
    }
}
