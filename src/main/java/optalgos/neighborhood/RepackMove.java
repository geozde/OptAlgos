package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;

import java.util.List;

/**
 * Takes a set of rectangles out of the solution and puts them back down somewhere.
 * 
 * This is for the steps that only make sense when moving several rectangles at a time. 
 */
public record RepackMove(List<PlacedRectangle> removed, List<PlacedRectangle> added, double scoreDelta)
        implements Move<PackingSolution> {

    public RepackMove {
        removed = List.copyOf(removed);
        added = List.copyOf(added);
    }

    @Override
    public PackingSolution apply(PackingSolution current) {
        for (PlacedRectangle placed : removed) {
            current.remove(placed);
        }
        for (PlacedRectangle placed : added) {
            current.place(placed);
        }
        return current;
    }
}
