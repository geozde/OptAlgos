package optalgos.rectpack.placement;

import optalgos.algorithm.greedy.SolutionBuilder;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;

import java.util.List;

/**
 * How a single rectangle gets placed feasibly into a (possibly already partly filled) set of boxes.
 * The one placement primitive shared by both greedy selection strategies and the rule-based/
 * permutation local-search neighborhood, so all three can never diverge in placement behavior - only
 * in which rectangle they hand this rule next (see the project plan).
 */
public interface PlacementRule extends SolutionBuilder<Rectangle, PackingSolution> {

    /** Commits {@code rectangle} into {@code partial}, opening a new box if nothing existing fits.
     *  Mutates and returns {@code partial} - see {@link PackingSolution}'s mutability note. */
    PackingSolution place(PackingSolution partial, Rectangle rectangle);

    /** Non-committing: how much this rectangle would waste if placed into {@code partial} right
     *  now. Used by the adaptive greedy strategy to compare candidates without actually placing any
     *  of them. */
    double placementCost(PackingSolution partial, Rectangle rectangle);

    @Override
    default PackingSolution extend(PackingSolution partial, Rectangle rectangle) {
        return place(partial, rectangle);
    }

    default PackingSolution placeAll(PackingSolution empty, List<Rectangle> order) {
        PackingSolution current = empty;
        for (Rectangle rectangle : order) {
            current = place(current, rectangle);
        }
        return current;
    }
}
