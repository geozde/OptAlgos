package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;

/**
 * Exchanges two rectangles' boxes.
 * a moves to where b was, and b moves where a was.
 */
public record SwapMove(PlacedRectangle aFrom, PlacedRectangle aTo, PlacedRectangle bFrom, PlacedRectangle bTo,
                        double scoreDelta) implements Move<PackingSolution> {

    @Override
    public PackingSolution apply(PackingSolution current) {
        current.remove(aFrom);
        current.remove(bFrom);
        current.place(aTo);
        current.place(bTo);
        return current;
    }
}
