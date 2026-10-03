package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;

/**
 * Relocates one rectangle from parameter from to parameter to.
 * It covers movement within a box, rotation in place, and moving to a different box.
 */
public record RelocationMove(PlacedRectangle from, PlacedRectangle to, double scoreDelta) implements Move<PackingSolution> {

    @Override
    public PackingSolution apply(PackingSolution current) {
        current.remove(from);
        current.place(to);
        return current;
    }
}
