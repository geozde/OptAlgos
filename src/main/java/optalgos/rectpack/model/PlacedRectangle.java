package optalgos.rectpack.model;

/**
 * A {@link Rectangle} positioned at (x, y) within a specific box, in a given orientation. All
 * geometric queries here are pure and self-contained so they can be unit-tested directly; solution-
 * wide aggregation (over many rectangles/boxes) lives on {@link PackingSolution}.
 */
public record PlacedRectangle(Rectangle rectangle, int boxIndex, int x, int y, Orientation orientation) {

    public PlacedRectangle {
        if (rectangle == null) {
            throw new NullPointerException("rectangle must not be null");
        }
        if (orientation == null) {
            throw new NullPointerException("orientation must not be null");
        }
        if (boxIndex < 0) {
            throw new IllegalArgumentException("boxIndex must be >= 0: " + boxIndex);
        }
    }

    public int effectiveWidth() {
        return orientation == Orientation.ROTATED ? rectangle.height() : rectangle.width();
    }

    public int effectiveHeight() {
        return orientation == Orientation.ROTATED ? rectangle.width() : rectangle.height();
    }

    public int right() {
        return x + effectiveWidth();
    }

    public int top() {
        return y + effectiveHeight();
    }

    public boolean fitsWithinBox(int boxLength) {
        return x >= 0 && y >= 0 && right() <= boxLength && top() <= boxLength;
    }

    /**
     * True iff this rectangle and {@code other} are "openly disjoint" per the spec: they may share
     * boundary points/edges but no interior point. Rectangles in different boxes are always
     * disjoint. Uses strict inequalities throughout so that exactly-touching rectangles count as
     * disjoint, not overlapping - an easy off-by-one to get backwards.
     */
    public boolean isOpenlyDisjointFrom(PlacedRectangle other) {
        if (this.boxIndex != other.boxIndex) {
            return true;
        }
        boolean separatedOnX = this.right() <= other.x || other.right() <= this.x;
        boolean separatedOnY = this.top() <= other.y || other.top() <= this.y;
        return separatedOnX || separatedOnY;
    }

    /**
     * Overlap ratio per the spec: shared area divided by the larger of the two rectangles' areas.
     * Zero for disjoint rectangles (including merely touching ones) or rectangles in different
     * boxes.
     */
    public double overlapRatioWith(PlacedRectangle other) {
        if (this.boxIndex != other.boxIndex) {
            return 0.0;
        }
        int overlapWidth = Math.min(this.right(), other.right()) - Math.max(this.x, other.x);
        int overlapHeight = Math.min(this.top(), other.top()) - Math.max(this.y, other.y);
        if (overlapWidth <= 0 || overlapHeight <= 0) {
            return 0.0;
        }
        long sharedArea = (long) overlapWidth * overlapHeight;
        long thisArea = (long) this.effectiveWidth() * this.effectiveHeight();
        long otherArea = (long) other.effectiveWidth() * other.effectiveHeight();
        long maxArea = Math.max(thisArea, otherArea);
        return maxArea == 0 ? 0.0 : (double) sharedArea / maxArea;
    }
}
