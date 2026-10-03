package optalgos.rectpack.model;

import java.util.List;

/** Read-only view of one box's contents, for GUI iteration over {@link PackingSolution#boxViews()}. */
public record BoxView(int boxIndex, List<PlacedRectangle> rectangles) {

    public boolean isEmpty() {
        return rectangles.isEmpty();
    }

    /**
     * How much overlap this one box currently carries - the per-box counterpart to
     * {@link PackingSolution#totalOverlapPenalty()}, which only ever reports a solution-wide sum.
     * Built for display (see the GUI's box captions) rather than for scoring: a search objective wants
     * one additive number over the whole solution, a viewer looking at a single box wants to know how
     * bad THIS box is, in the spec's own units.
     *
     * <p>Costs one pass over the box's rectangle pairs; the caller decides how often that is worth
     * paying (the GUI does it once per box per repaint, on already-snapshotted states, never on the
     * live solution a search is mutating).
     */
    public BoxOverlap overlap() {
        double worst = 0.0;
        int pairs = 0;
        for (int i = 0; i < rectangles.size(); i++) {
            for (int j = i + 1; j < rectangles.size(); j++) {
                double ratio = rectangles.get(i).overlapRatioWith(rectangles.get(j));
                if (ratio > 0) {
                    pairs++;
                    worst = Math.max(worst, ratio);
                }
            }
        }
        return pairs == 0 ? BoxOverlap.NONE : new BoxOverlap(pairs, worst);
    }
}
