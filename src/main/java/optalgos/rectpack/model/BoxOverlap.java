package optalgos.rectpack.model;

/**
 * How much overlap a single box carries, in the spec's own terms - see {@link BoxView#overlap()}.
 *
 * <p>Two numbers rather than one, because neither alone describes a box honestly. {@code worstRatio}
 * is the spec's overlap measure (shared area over the LARGER of the two rectangles' areas) reduced to
 * the single worst pair in the box: it is bounded to [0, 1], so it reads directly as a percentage and
 * is comparable between boxes, and it is exactly the quantity {@code RelaxedOverlapNeighborhood}'s
 * annealed allowance is a threshold on - a box whose worst pair sits at 40% is precisely a box that
 * would be legal at an allowance of 40% and illegal below it. But a single worst pair says nothing
 * about how widespread the violation is, and one 40% pair is a very different box from twelve of them,
 * so {@code pairCount} carries the extent alongside the severity.
 *
 * <p>Deliberately NOT a sum of pairwise ratios (what the search objective uses): that is unbounded and
 * grows with the number of rectangles in a box, so it can neither be shown as a percentage nor
 * compared between boxes of different occupancy - fine for ranking candidate moves, useless as a
 * number to put in front of a viewer.
 */
public record BoxOverlap(int pairCount, double worstRatio) {

    public static final BoxOverlap NONE = new BoxOverlap(0, 0.0);

    public boolean isPresent() {
        return pairCount > 0;
    }

    /** {@link #worstRatio} as whole percent, the unit the spec states the allowance in. */
    public int worstPercent() {
        return (int) Math.round(worstRatio * 100);
    }
}
