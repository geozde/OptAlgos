package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.rectpack.model.PermutationSolution;

/**
 * Replaces the current permutation solution with an existing candidate.
 * Cheaper than re-deriving.
 */
public record PermutationMove(PermutationSolution candidate, double scoreDelta) implements Move<PermutationSolution> {

    @Override
    public PermutationSolution apply(PermutationSolution current) {
        return candidate;
    }
}
