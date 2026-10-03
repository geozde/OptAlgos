package optalgos.rectpack.placement;

import optalgos.rectpack.model.PlacedRectangle;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A read-only skyline profile for one box, built fresh from its current rectangles whenever queried
 * (see {@link SkylinePlacement} for why no persistent cache is kept across moves - a box's occupancy
 * is inherently bounded, so this rebuild is cheap and avoids an entire class of cache-invalidation
 * bugs). Each segment records the topmost occupied y-coordinate over its x-range. Built via a
 * max-height sweep over every rectangle's x-breakpoints, not by naively replaying placements in
 * list order - two rectangles can share an x-range at different heights (e.g. mid-search, before a
 * local-search move has settled), so "last one replayed wins" would silently under-count height.
 */
final class Skyline {

    record Segment(int startX, int width, int height) {
        int endX() {
            return startX + width;
        }
    }

    private final List<Segment> segments;
    private final int boxLength;

    private Skyline(List<Segment> segments, int boxLength) {
        this.segments = segments;
        this.boxLength = boxLength;
    }

    static Skyline of(List<PlacedRectangle> rectanglesInBox, int boxLength) {
        List<Integer> xs = breakpoints(rectanglesInBox, boxLength);
        List<Segment> built = new ArrayList<>();
        for (int i = 0; i < xs.size() - 1; i++) {
            int start = xs.get(i);
            int end = xs.get(i + 1);
            int height = 0;
            for (PlacedRectangle rect : rectanglesInBox) {
                if (rect.x() <= start && start < rect.right()) {
                    height = Math.max(height, rect.top());
                }
            }
            built.add(new Segment(start, end - start, height));
        }
        return new Skyline(mergeAdjacentEqualHeights(built), boxLength);
    }

    private static List<Integer> breakpoints(List<PlacedRectangle> rectangles, int boxLength) {
        TreeSet<Integer> xs = new TreeSet<>();
        xs.add(0);
        xs.add(boxLength);
        for (PlacedRectangle rect : rectangles) {
            if (rect.x() > 0 && rect.x() < boxLength) {
                xs.add(rect.x());
            }
            if (rect.right() > 0 && rect.right() < boxLength) {
                xs.add(rect.right());
            }
        }
        return new ArrayList<>(xs);
    }

    /** Highest occupied y across [x, x+width). */
    int heightOver(int x, int width) {
        int maxHeight = 0;
        int end = x + width;
        for (Segment segment : segments) {
            if (segment.endX() <= x || segment.startX() >= end) {
                continue;
            }
            maxHeight = Math.max(maxHeight, segment.height());
        }
        return maxHeight;
    }

    boolean fits(int x, int width, int height) {
        return x >= 0 && x + width <= boxLength && heightOver(x, width) + height <= boxLength;
    }

    /** Area strictly below a rectangle of this footprint, dropped at the height the skyline forces
     *  it to, but above the skyline itself - used to break ties toward the tightest-fitting
     *  position. */
    long wastedAreaAt(int x, int width) {
        int placementY = heightOver(x, width);
        long wasted = 0;
        int end = x + width;
        for (Segment segment : segments) {
            if (segment.endX() <= x || segment.startX() >= end) {
                continue;
            }
            int overlapStart = Math.max(segment.startX(), x);
            int overlapEnd = Math.min(segment.endX(), end);
            wasted += (long) (overlapEnd - overlapStart) * (placementY - segment.height());
        }
        return wasted;
    }

    /** Every x at which a segment begins - the only positions worth trying, since sliding within a
     *  flat sub-segment never changes {@link #heightOver}. Standard simplification for skyline
     *  packing (Jylänki); not exhaustively optimal in every corner case, but the spec explicitly
     *  invites deviating from exhaustive search for performance. */
    List<Integer> candidateXPositions() {
        List<Integer> xs = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            xs.add(segment.startX());
        }
        return xs;
    }

    private static List<Segment> mergeAdjacentEqualHeights(List<Segment> segs) {
        List<Segment> merged = new ArrayList<>();
        for (Segment s : segs) {
            if (!merged.isEmpty()) {
                Segment last = merged.get(merged.size() - 1);
                if (last.endX() == s.startX() && last.height() == s.height()) {
                    merged.set(merged.size() - 1, new Segment(last.startX(), last.width() + s.width(), last.height()));
                    continue;
                }
            }
            merged.add(s);
        }
        return merged;
    }
}
