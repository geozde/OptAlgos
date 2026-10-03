package optalgos.greedystrategy;

import optalgos.algorithm.greedy.SelectionStrategy;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * implementation of SelectionStrategy to select which rect to place next
 *
 * idea "Put the big stones in first"
 * 
 * Static selection. Order all rects in order of decreasing area. 
 * Ignores the partial solution.
 */
public final class DecreasingAreaStrategy implements SelectionStrategy<Rectangle, PackingSolution> {

    /** remaining candidates are ordered by decreasing area
     * partial is ignored
     */
    @Override
    public Rectangle selectNext(PackingSolution partial, List<Rectangle> remaining) {
        return Collections.max(remaining, Comparator.comparingInt(Rectangle::area));
    }
}
