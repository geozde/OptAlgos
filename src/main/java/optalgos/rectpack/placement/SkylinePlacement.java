package optalgos.rectpack.placement;

import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;

import java.util.List;
import java.util.Optional;

/**
 * Skyline-based placement: for each open box, tracks a profile of the topmost occupied height at
 * each x-position, and places a rectangle at the position (across both orientations, across all open
 * boxes) that wastes the least area beneath it - falling back to a new box if nothing existing fits.
 * See the project plan for why skyline was chosen over shelf/level packing.
 */
public final class SkylinePlacement implements PlacementRule {

    /** Where {@code rectangle} would go within a single box, and how much it would waste there. */
    public record BoxPosition(int x, int y, Orientation orientation, long wastedArea) {
    }

    private record Best(int boxIndex, BoxPosition position) {
    }

    @Override
    public PackingSolution place(PackingSolution partial, Rectangle rectangle) {
        Best best = findBest(partial, rectangle);
        PlacedRectangle toPlace = best != null
                ? new PlacedRectangle(rectangle, best.boxIndex(), best.position().x(), best.position().y(), best.position().orientation())
                : new PlacedRectangle(rectangle, partial.boxSlotCount(), 0, 0, Orientation.NORMAL);
        partial.place(toPlace);
        return partial;
    }

    @Override
    public double placementCost(PackingSolution partial, Rectangle rectangle) {
        Best best = findBest(partial, rectangle);
        return best != null ? (double) best.position().wastedArea() : (double) rectangle.area();
    }

    /**
     * A box's skyline profile, captured once and reusable across several {@link #bestPositionInBox
     * (BoxProfile, Rectangle)} queries against the SAME occupants - for a caller (a local-search
     * neighborhood's {@code candidateMoves}, typically) that evaluates multiple candidate rectangles
     * against the same destination box within a single decision pass, rather than the one-shot
     * {@link #bestPositionInBox(List, int, Rectangle)} overload rebuilding an identical {@link
     * Skyline} from scratch for every one of those candidates. Deliberately NOT held by this class
     * itself across separate calls, and never valid across a move actually being applied - build a
     * fresh one afterward. See {@link Skyline}'s own Javadoc for why a persistent, cross-move cache
     * inside this class was rejected; this stays a caller-owned, single-decision-pass-scoped batch,
     * not a standing cache with its own invalidation to get wrong.
     */
    public static final class BoxProfile {
        private final Skyline skyline;
        private final int boxLength;

        private BoxProfile(Skyline skyline, int boxLength) {
            this.skyline = skyline;
            this.boxLength = boxLength;
        }
    }

    /** Captures {@code occupants}' skyline once, for repeated {@link #bestPositionInBox(BoxProfile,
     *  Rectangle)} queries against them - see {@link BoxProfile}. */
    public BoxProfile profileFor(List<PlacedRectangle> occupants, int boxLength) {
        return new BoxProfile(Skyline.of(occupants, boxLength), boxLength);
    }

    /**
     * Best position for {@code rectangle} within a single box whose current occupants are exactly
     * {@code occupants} - the same search {@link #place} uses across all boxes, exposed per-box so
     * neighborhoods can search or re-search one specific box directly (e.g. when relocating a
     * rectangle within, or out of, its current box - excluding it from {@code occupants} first).
     * Evaluating more than one candidate rectangle against the same {@code occupants}? Build a
     * {@link BoxProfile} once via {@link #profileFor} instead of calling this overload repeatedly -
     * it pays for the skyline rebuild only once, not once per candidate.
     */
    public Optional<BoxPosition> bestPositionInBox(List<PlacedRectangle> occupants, int boxLength, Rectangle rectangle) {
        return bestPositionInBox(new BoxProfile(Skyline.of(occupants, boxLength), boxLength), rectangle);
    }

    /** Same search as {@link #bestPositionInBox(List, int, Rectangle)}, against an already-built
     *  {@link BoxProfile} instead of raw occupants - see that type's Javadoc for when this is worth
     *  reaching for over the one-shot overload. */
    public Optional<BoxPosition> bestPositionInBox(BoxProfile profile, Rectangle rectangle) {
        Skyline skyline = profile.skyline;
        int boxLength = profile.boxLength;
        BoxPosition best = null;
        for (Orientation orientation : Orientation.values()) {
            int w = orientation == Orientation.ROTATED ? rectangle.height() : rectangle.width();
            int h = orientation == Orientation.ROTATED ? rectangle.width() : rectangle.height();
            if (w > boxLength || h > boxLength) {
                continue;
            }
            for (int x : skyline.candidateXPositions()) {
                if (x + w > boxLength) {
                    continue;
                }
                int y = skyline.heightOver(x, w);
                if (y + h > boxLength) {
                    continue;
                }
                long wasted = skyline.wastedAreaAt(x, w);
                if (best == null || isBetterPosition(x, y, wasted, best)) {
                    best = new BoxPosition(x, y, orientation, wasted);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private Best findBest(PackingSolution partial, Rectangle rectangle) {
        Best best = null;
        int boxLength = partial.boxLength();
        for (int boxIndex = 0; boxIndex < partial.boxSlotCount(); boxIndex++) {
            Optional<BoxPosition> position = bestPositionInBox(partial.rectanglesIn(boxIndex), boxLength, rectangle);
            if (position.isPresent() && (best == null || isBetterBox(boxIndex, position.get(), best))) {
                best = new Best(boxIndex, position.get());
            }
        }
        return best;
    }

    private boolean isBetterPosition(int x, int y, long wastedArea, BoxPosition current) {
        if (wastedArea != current.wastedArea()) {
            return wastedArea < current.wastedArea();
        }
        if (y != current.y()) {
            return y < current.y();
        }
        return x < current.x();
    }

    private boolean isBetterBox(int boxIndex, BoxPosition candidate, Best current) {
        if (candidate.wastedArea() != current.position().wastedArea()) {
            return candidate.wastedArea() < current.position().wastedArea();
        }
        if (boxIndex != current.boxIndex()) {
            return boxIndex < current.boxIndex();
        }
        if (candidate.y() != current.position().y()) {
            return candidate.y() < current.position().y();
        }
        return candidate.x() < current.position().x();
    }
}
