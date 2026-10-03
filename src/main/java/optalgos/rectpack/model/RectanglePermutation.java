package optalgos.rectpack.model;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordering of rectangles - the solution representation the rule-based/permutation local-search
 * neighborhood searches over, instead of a geometric arrangement directly. Materializing an order
 * into an actual {@link PackingSolution} is the job of a placement rule, not this class - see
 * {@link PermutationSolution} and {@code optalgos.rectpack.placement.PlacementRule}.
 */
public record RectanglePermutation(List<Rectangle> order) {

    public RectanglePermutation {
        order = List.copyOf(order);
    }

    public int size() {
        return order.size();
    }

    public RectanglePermutation withSwap(int i, int j) {
        List<Rectangle> copy = new ArrayList<>(order);
        Rectangle tmp = copy.get(i);
        copy.set(i, copy.get(j));
        copy.set(j, tmp);
        return new RectanglePermutation(copy);
    }

    /**
     * Lifts the {@code length} consecutive rectangles starting at {@code from} out of the order and
     * re-inserts them, in their existing internal order, at position {@code to} of what remains - the
     * standard block-relocation ("or-opt") step; at {@code length == 1} it is the plain relocation of
     * a single rectangle.
     *
     * <p>Moving a short run in one step rather than one rectangle at a time is what lets a search over
     * this representation cross a distance a single-element step cannot: a handful of large rectangles
     * stranded together at the wrong end of the order are exactly the situation that costs boxes, and
     * relocating them one at a time only pays off once the last of them has followed, which a search
     * accepting only individually-improving steps never gets to see.
     */
    public RectanglePermutation withBlockMoved(int from, int length, int to) {
        List<Rectangle> copy = new ArrayList<>(order);
        List<Rectangle> block = new ArrayList<>(copy.subList(from, from + length));
        copy.subList(from, from + length).clear();
        copy.addAll(Math.min(to, copy.size()), block);
        return new RectanglePermutation(copy);
    }
}
