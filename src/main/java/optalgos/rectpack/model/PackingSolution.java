package optalgos.rectpack.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A (possibly infeasible) geometric arrangement of {@link PlacedRectangle}s into boxes of a fixed
 * side length. Deliberately non-validating: it does not forbid overlapping placements, because the
 * relaxed-overlap local-search neighborhood needs to hold an infeasible arrangement in memory while
 * it searches (see the project plan). Feasibility is a query ({@link #hasAnyOverlap()},
 * {@link #totalOverlapPenalty()}), never a type invariant.
 *
 * <p>Mutable by design, for performance: a single placement or removal touches one box, not the
 * whole solution - needed for thousands of moves/second at n=1000. Callers that need to retain a
 * state across further mutation (GUI frame history, "best so far" bookkeeping) MUST call
 * {@link #snapshot()} at the moment they retain it; this class does not defend against aliasing.
 */
public final class PackingSolution {

    private final int boxLength;
    private final List<List<PlacedRectangle>> boxes;
    // Which box slots currently hold at least one rectangle, maintained incrementally by
    // place()/remove()/removeById() rather than rediscovered by scanning every box slot on every
    // call. This stopped being a rounding error once it was measured as a hot-path cost in its own
    // right: boxCount() (this set's size) is queried at least once per accepted step by every local-
    // search neighborhood in this package to score a candidate move, and GeometricNeighborhood and
    // RelaxedOverlapNeighborhood each ALSO re-derive "which boxes are occupied" from scratch, by
    // scanning every slot, once per step on top of that (see their mostSpaciousBoxes/
    // mostSpaciousOccupiedBoxes/randomOccupiedBoxes) - and boxSlotCount() only ever grows over a run
    // (see that method's own Javadoc: an emptied slot is never reclaimed), so every one of those scans
    // gets more wasteful, not less, exactly as a long-running consolidation search closes more and
    // more boxes: by the time an n=1000 run has consolidated down to under 100 boxes, a full-slot scan
    // is over 90% wasted work, every single step, for the rest of the run. A LinkedHashSet keeps
    // insertion order (so callers iterating it see roughly the same box order a full scan would have)
    // while making both the membership check on removal and the eventual size() query O(1) instead of
    // O(boxSlotCount). See boxCount() and occupiedBoxIndices() for the guarantees this upholds.
    private final Set<Integer> occupiedBoxIndices;
    // Used area per box slot, parallel to boxes (same index, same growth points), maintained
    // incrementally for the identical reason as occupiedBoxIndices: GeometricNeighborhood and
    // RelaxedOverlapNeighborhood both rank destination boxes by free area every single step (see
    // their mostSpaciousBoxes/mostSpaciousOccupiedBoxes), which used to mean summing every occupant's
    // area, for every occupied box, every step - an O(rectangle count) recomputation of a value that
    // only ever changes by exactly one rectangle's area at a time, on whichever one or two boxes a
    // move actually touches. See freeAreaIn() for the guarantee this upholds.
    private final List<Long> usedAreaByBox;

    private PackingSolution(int boxLength, List<List<PlacedRectangle>> boxes, Set<Integer> occupiedBoxIndices,
                             List<Long> usedAreaByBox) {
        this.boxLength = boxLength;
        this.boxes = boxes;
        this.occupiedBoxIndices = occupiedBoxIndices;
        this.usedAreaByBox = usedAreaByBox;
    }

    public static PackingSolution empty(int boxLength) {
        if (boxLength <= 0) {
            throw new IllegalArgumentException("boxLength must be positive: " + boxLength);
        }
        return new PackingSolution(boxLength, new ArrayList<>(), new LinkedHashSet<>(), new ArrayList<>());
    }

    /** A deliberately bad starting arrangement - one rectangle per box, unrotated, in the
     *  bottom-left corner - per the spec's requirement that local search start from a convincingly
     *  bad solution so its improvement is visible. */
    public static PackingSolution onePerBox(List<Rectangle> rectangles, int boxLength) {
        PackingSolution solution = empty(boxLength);
        for (Rectangle rectangle : rectangles) {
            solution.place(new PlacedRectangle(rectangle, solution.boxSlotCount(), 0, 0, Orientation.NORMAL));
        }
        return solution;
    }

    public int boxLength() {
        return boxLength;
    }

    /**
     * Number of box slots currently allocated, INCLUDING any that are empty. Not the true
     * objective - see {@link #boxCount()}. Placement code uses this to know the valid range of
     * existing indices, and to recognise when it must open a genuinely new slot (index ==
     * {@code boxSlotCount()}).
     */
    public int boxSlotCount() {
        return boxes.size();
    }

    /**
     * The TRUE OBJECTIVE to minimize: number of non-empty box slots. Deliberately not
     * {@code boxSlotCount()} and not "highest used index + 1" - a box emptied by a move leaves a
     * sparse empty slot behind rather than triggering a renumbering pass over every
     * {@code PlacedRectangle.boxIndex()} in the solution (a needless bug-injection risk for a
     * cosmetic benefit).
     */
    public int boxCount() {
        return occupiedBoxIndices.size();
    }

    /** Indices of exactly the box slots currently holding at least one rectangle - never includes an
     *  empty slot, unlike iterating {@code 0 .. boxSlotCount()} and checking each one. A read-only
     *  view backed by the live set; safe to iterate immediately, but - like every other read method
     *  on this deliberately mutable class - not safe to hold onto across a further mutation (see the
     *  class-level note; {@link #snapshot()} first if that's needed). */
    public Set<Integer> occupiedBoxIndices() {
        return Collections.unmodifiableSet(occupiedBoxIndices);
    }

    public boolean isEmptyBox(int boxIndex) {
        return rectanglesIn(boxIndex).isEmpty();
    }

    /** Box area not yet claimed by any rectangle currently in {@code boxIndex} - an upper bound a
     *  candidate must clear before a real (skyline) fit check is worth running, not a guarantee it
     *  fits (fragmentation isn't accounted for). Out-of-range indices are treated as an empty box
     *  (full free area), matching {@link #rectanglesIn}'s convention. */
    public long freeAreaIn(int boxIndex) {
        long used = boxIndex >= 0 && boxIndex < usedAreaByBox.size() ? usedAreaByBox.get(boxIndex) : 0L;
        return (long) boxLength * boxLength - used;
    }

    /** Read-only view of the rectangles currently in one box. Out-of-range indices are treated as
     *  empty rather than erroring, so callers can probe freely while searching for a slot. */
    public List<PlacedRectangle> rectanglesIn(int boxIndex) {
        if (boxIndex < 0 || boxIndex >= boxes.size()) {
            return List.of();
        }
        return Collections.unmodifiableList(boxes.get(boxIndex));
    }

    /** Read-only view of every box slot, including empty ones, for GUI iteration. */
    public List<BoxView> boxViews() {
        List<BoxView> views = new ArrayList<>(boxes.size());
        for (int i = 0; i < boxes.size(); i++) {
            views.add(new BoxView(i, rectanglesIn(i)));
        }
        return views;
    }

    public List<PlacedRectangle> allPlacedRectangles() {
        List<PlacedRectangle> all = new ArrayList<>();
        for (List<PlacedRectangle> box : boxes) {
            all.addAll(box);
        }
        return all;
    }

    /**
     * Places {@code rect} into the solution. {@code rect.boxIndex()} must be either an existing
     * slot index, or exactly {@link #boxSlotCount()} to open a genuinely new box. Does not check
     * for overlap with existing rectangles in that box - see the class-level note on feasibility -
     * but does check that the rectangle fits within the box bounds.
     */
    public void place(PlacedRectangle rect) {
        if (!rect.fitsWithinBox(boxLength)) {
            throw new IllegalArgumentException(
                    "Rectangle does not fit within box bounds (L=" + boxLength + "): " + rect);
        }
        int index = rect.boxIndex();
        boolean wasEmptyBefore;
        if (index == boxes.size()) {
            boxes.add(new ArrayList<>());
            usedAreaByBox.add(0L);
            wasEmptyBefore = true;
        } else if (index < 0 || index > boxes.size()) {
            throw new IllegalArgumentException(
                    "boxIndex " + index + " is neither an existing slot nor the next new slot (" + boxes.size() + ")");
        } else {
            wasEmptyBefore = boxes.get(index).isEmpty();
        }
        boxes.get(index).add(rect);
        usedAreaByBox.set(index, usedAreaByBox.get(index) + (long) rect.effectiveWidth() * rect.effectiveHeight());
        if (wasEmptyBefore) {
            occupiedBoxIndices.add(index);
        }
    }

    /** Removes exactly this placement. Leaves the box slot in place, possibly now empty. Returns
     *  false if this exact placement wasn't present. */
    public boolean remove(PlacedRectangle rect) {
        if (rect.boxIndex() < 0 || rect.boxIndex() >= boxes.size()) {
            return false;
        }
        List<PlacedRectangle> box = boxes.get(rect.boxIndex());
        boolean removed = box.remove(rect);
        if (removed) {
            int index = rect.boxIndex();
            usedAreaByBox.set(index, usedAreaByBox.get(index) - (long) rect.effectiveWidth() * rect.effectiveHeight());
            if (box.isEmpty()) {
                occupiedBoxIndices.remove(index);
            }
        }
        return removed;
    }

    /** Removes whichever placement currently exists for this rectangle id, wherever it is -
     *  convenient for moves that only know a rectangle's identity, not its current PlacedRectangle.
     *  Returns the removed placement, or null if this id wasn't placed. */
    public PlacedRectangle removeById(int rectangleId) {
        for (int boxIndex = 0; boxIndex < boxes.size(); boxIndex++) {
            List<PlacedRectangle> box = boxes.get(boxIndex);
            for (int i = 0; i < box.size(); i++) {
                if (box.get(i).rectangle().id() == rectangleId) {
                    PlacedRectangle removed = box.remove(i);
                    usedAreaByBox.set(boxIndex, usedAreaByBox.get(boxIndex)
                            - (long) removed.effectiveWidth() * removed.effectiveHeight());
                    if (box.isEmpty()) {
                        occupiedBoxIndices.remove(boxIndex);
                    }
                    return removed;
                }
            }
        }
        return null;
    }

    /** True if any two rectangles sharing a box have interior overlap. Always false for a solution
     *  built only through a placement rule that enforces disjointness; may be true mid-search for
     *  the relaxed-overlap neighborhood. */
    public boolean hasAnyOverlap() {
        for (List<PlacedRectangle> box : boxes) {
            for (int i = 0; i < box.size(); i++) {
                for (int j = i + 1; j < box.size(); j++) {
                    if (!box.get(i).isOpenlyDisjointFrom(box.get(j))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Sum of pairwise overlap ratios across all boxes - used only by the relaxed-overlap
     *  neighborhood's search objective; always 0 for a feasible solution. Equivalent to
     *  {@link #totalOverlapPenalty(double)} with an allowed fraction of 0. */
    public double totalOverlapPenalty() {
        return totalOverlapPenalty(0.0);
    }

    /** Sum of pairwise overlap EXCEEDING {@code allowedFraction} across all boxes - the annealed
     *  version {@link optalgos.neighborhood.RelaxedOverlapNeighborhood} scores against as its
     *  allowed-overlap threshold tightens from 1.0 toward 0.0 over the course of a run. */
    public double totalOverlapPenalty(double allowedFraction) {
        double total = 0.0;
        for (List<PlacedRectangle> box : boxes) {
            for (int i = 0; i < box.size(); i++) {
                for (int j = i + 1; j < box.size(); j++) {
                    double excess = box.get(i).overlapRatioWith(box.get(j)) - allowedFraction;
                    if (excess > 0) {
                        total += excess;
                    }
                }
            }
        }
        return total;
    }

    /** Deep-enough copy: independent box lists, sharing immutable {@link PlacedRectangle} instances
     *  (they're records, safe to share). Callers retaining a state across further mutation of the
     *  original MUST call this - see the class-level note. */
    public PackingSolution snapshot() {
        List<List<PlacedRectangle>> copy = new ArrayList<>(boxes.size());
        for (List<PlacedRectangle> box : boxes) {
            copy.add(new ArrayList<>(box));
        }
        return new PackingSolution(boxLength, copy, new LinkedHashSet<>(occupiedBoxIndices), new ArrayList<>(usedAreaByBox));
    }

    @Override
    public String toString() {
        return "PackingSolution{boxLength=" + boxLength + ", boxCount=" + boxCount()
                + ", slots=" + boxes.size() + ", rectangles=" + allPlacedRectangles().size() + "}";
    }
}
