package optalgos.gui;

import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Curates a bounded, representative history of an algorithm run for playback and scrubbing: never
 * more than {@link #MAX_FRAMES} frames, no matter how long the run turns out to be (spec requirement -
 * a run of thousands of internal steps must not become thousands of displayed frames the viewer has to
 * sit through), never a frame that looks identical to the one before it, and never a run of frames so
 * dominated by small cosmetic moves that the handful of genuinely important ones - a box actually
 * closing - get diluted away by decimation. {@link #recordFinal} is always the run's true last state,
 * bypassing all of this, so playback always ends on the actual result - unless that result happens to
 * be placement-identical to the last frame already kept, in which case it REPLACES rather than
 * duplicates it (see {@link #recordFinal}'s own Javadoc).
 *
 * <p>The "never identical to the one before it" guarantee was found only by actually watching a run: a
 * step is offered to this class on EVERY iteration {@link optalgos.algorithm.localsearch.LocalSearch}
 * takes, including ones from {@link optalgos.algorithm.localsearch.Neighborhood#onPlateau} that don't
 * move a single rectangle - {@code RelaxedOverlapNeighborhood} in particular can spend most of a run's
 * iterations retrying the SAME state while its internal overlap schedule tightens, with nothing
 * visibly different about the packing itself. {@link #shouldKeep} gates on that directly via {@link
 * #differsFromLastKept}: a step is never even a CANDIDATE for retention unless at least one rectangle's
 * box, position, or orientation differs from the last frame this class actually kept - these non-moving
 * retries are filtered out before the improvement/schedule logic ever sees them.
 *
 * <p>The "dilution" problem was found the same way, on a DIFFERENT kind of run: {@link
 * optalgos.neighborhood.GeometricNeighborhood} accepts many small, genuinely-different moves that never
 * change the box count at all (a sparsity-thinning relocation, an in-place rotation nudge) for every
 * one that actually closes a box. Since every one of those small moves passes {@link #differsFromLastKept}
 * (nothing about it is a duplicate), an earlier version of this class - which decimated the retained
 * list by position alone, keeping every other entry regardless of what kind of change it represented -
 * could end up with a final 100-frame set overwhelmingly made of these small moves, with the rare
 * box-closing transitions just as likely to be thinned away as anything else: exactly backwards from
 * what a viewer scrubbing a run wants to see ("the different meaningful changes... shown"). {@link
 * #compact} now protects box-count-IMPROVING frames from ever being discarded (see {@link
 * #KeptFrame#improvement}) and only thins the non-improving "filler" frames in between - every box
 * closing in the run is guaranteed to still have its own frame in the final set, regardless of how many
 * small cosmetic moves happened around it. This still degrades gracefully to the old uniform-stride
 * behavior when nothing in a run counts as an "improvement" at all (Greedy's box count never decreases
 * as it places rectangles, for instance) - see {@link #downsample}.
 */
final class FrameHistory {

    static final int MAX_FRAMES = 100;

    /** One retained frame plus whether the step it came from struck a new best (lowest-yet) box count
     *  - the signal {@link #compact} uses to decide what's safe to thin out. */
    private record KeptFrame(Frame frame, boolean improvement) {
    }

    private final List<KeptFrame> kept = new ArrayList<>();
    private Map<Integer, PlacedRectangle> lastKeptById = Map.of();
    private int keepEveryNth = 1;
    private long stepsSinceLastKeep = 0;
    private int bestObjectiveSoFar = Integer.MAX_VALUE;
    private boolean finalized = false;

    /** Set by {@link #shouldKeep} for the immediately following {@link #record} call to pick up - the
     *  two are always called as a single-threaded pair for the same step (see {@code AlgorithmRunner}),
     *  never interleaved with a pair for a different step, which is what makes carrying this one piece
     *  of "why" across the two calls safe without threading it through the public method signatures
     *  {@code FrameConsumer} already commits both callers to. */
    private boolean pendingImprovement;

    /** Whether the current step is worth turning into a {@link Frame} - cheap relative to a full
     *  {@code PackingSolution.snapshot()} (a hash-map-backed diff against the last kept frame, plus a
     *  couple of int comparisons), so a caller can check this before paying for the snapshot on a step
     *  that's about to be discarded anyway. {@code liveState} is read-only here - never retained past
     *  this call - so it's safe to pass the live, still-mutating solution directly. */
    boolean shouldKeep(PackingSolution liveState, long stepNumber) {
        if (!differsFromLastKept(liveState)) {
            return false;
        }
        int objective = liveState.boxCount();
        boolean improved = objective < bestObjectiveSoFar;
        if (improved) {
            bestObjectiveSoFar = objective;
        }
        stepsSinceLastKeep++;
        boolean keep = improved || stepsSinceLastKeep >= keepEveryNth;
        if (keep) {
            pendingImprovement = improved;
        }
        return keep;
    }

    private boolean differsFromLastKept(PackingSolution liveState) {
        List<PlacedRectangle> current = liveState.allPlacedRectangles();
        if (current.size() != lastKeptById.size()) {
            return true;   // shouldn't normally happen (rectangle count is fixed) but a safe fallback
        }
        for (PlacedRectangle placed : current) {
            PlacedRectangle before = lastKeptById.get(placed.rectangle().id());
            if (before == null || !samePlacement(before, placed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean samePlacement(PlacedRectangle a, PlacedRectangle b) {
        return a.boxIndex() == b.boxIndex() && a.x() == b.x() && a.y() == b.y() && a.orientation() == b.orientation();
    }

    /** Retains a frame {@link #shouldKeep} just approved - called from the algorithm's own worker
     *  thread, same as {@link #shouldKeep}, never the EDT. */
    synchronized void record(Frame frame) {
        if (finalized) {
            return;   // the run already produced its true final frame; nothing else gets appended after it
        }
        stepsSinceLastKeep = 0;
        kept.add(new KeptFrame(frame, pendingImprovement));
        lastKeptById = byId(frame.snapshot());
        if (kept.size() > MAX_FRAMES) {
            compact();
        }
    }

    /** Halves the retained list, same as before, but no longer by raw position: every improvement
     *  frame is protected and only thinned if improvements alone would still overflow the target (an
     *  edge case, not the common one this class was built for - see the class Javadoc); otherwise the
     *  budget left over after keeping every improvement is spent evenly across the non-improving filler
     *  frames via {@link #downsample}. Relative chronological order is preserved either way. */
    private void compact() {
        int targetSize = (kept.size() + 1) / 2;

        List<Integer> improvementIdx = new ArrayList<>();
        List<Integer> fillerIdx = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            (kept.get(i).improvement() ? improvementIdx : fillerIdx).add(i);
        }

        Set<Integer> keepIdx;
        if (improvementIdx.size() >= targetSize) {
            keepIdx = new HashSet<>(downsample(improvementIdx, targetSize));
        } else {
            keepIdx = new HashSet<>(improvementIdx);
            keepIdx.addAll(downsample(fillerIdx, targetSize - improvementIdx.size()));
        }

        List<KeptFrame> retained = new ArrayList<>(keepIdx.size());
        for (int i = 0; i < kept.size(); i++) {
            if (keepIdx.contains(i)) {
                retained.add(kept.get(i));
            }
        }
        kept.clear();
        kept.addAll(retained);
        keepEveryNth *= 2;
        // lastKeptById is deliberately left alone: it tracks "what to diff the NEXT live step
        // against," which stays the most recently recorded frame regardless of which older frames a
        // compaction pass happens to discard.
    }

    /** {@code targetCount} items evenly spaced across {@code items}, preserving relative order -
     *  generalizes the "keep every other one" idea to an arbitrary target instead of always exactly
     *  half, which {@link #compact} needs once the target has to account for space already spent on
     *  protected improvement frames. */
    private static <T> List<T> downsample(List<T> items, int targetCount) {
        if (targetCount >= items.size()) {
            return new ArrayList<>(items);
        }
        if (targetCount <= 0) {
            return List.of();
        }
        List<T> result = new ArrayList<>(targetCount);
        double stride = items.size() / (double) targetCount;
        for (int i = 0; i < targetCount; i++) {
            result.add(items.get((int) (i * stride)));
        }
        return result;
    }

    /**
     * The run's true final state, always kept and always last - see the class Javadoc. Call exactly
     * once, when the algorithm has genuinely finished.
     *
     * <p>Skips adding a second, redundant frame when {@code finalFrame} is placement-identical to the
     * last one already kept (still upgrading that entry to carry the final metadata) rather than
     * always appending unconditionally - a real gap the unconditional version had: whenever a search
     * plateaus with nothing left to try (or a neighborhood's {@link
     * optalgos.algorithm.localsearch.Neighborhood#finish} is a no-op on an already-clean result), the
     * last live-recorded state and the algorithm's actual returned result are the exact same placement,
     * and appending both produced two consecutive frames a viewer cannot tell apart - indistinguishable
     * from the identical-frame problem {@link #shouldKeep} exists to prevent, just arriving through the
     * one path that bypassed it.
     */
    synchronized void recordFinal(Frame finalFrame) {
        if (!kept.isEmpty() && !differsFromLastKept(finalFrame.snapshot())) {
            kept.set(kept.size() - 1, new KeptFrame(finalFrame, true));
            finalized = true;
            return;
        }
        if (kept.size() >= MAX_FRAMES) {
            // Every regular record() already keeps the list at <= MAX_FRAMES; make room for the one
            // guaranteed addition here rather than let this call be the one exception that pushes the
            // total past the cap. The dropped frame is whatever was kept most recently, so it's
            // necessarily close to this final state anyway - nothing meaningful is lost.
            kept.remove(kept.size() - 1);
        }
        kept.add(new KeptFrame(finalFrame, true));
        finalized = true;
    }

    private static Map<Integer, PlacedRectangle> byId(PackingSolution solution) {
        Map<Integer, PlacedRectangle> byId = new HashMap<>();
        for (PlacedRectangle placed : solution.allPlacedRectangles()) {
            byId.put(placed.rectangle().id(), placed);
        }
        return byId;
    }

    /** A snapshot of the retained history so far - safe to call from the EDT at any time, including
     *  mid-run: the list only ever grows (up to {@link #MAX_FRAMES}, then holds steady in count while
     *  its contents periodically decimate) until {@link #recordFinal} appends the last entry. */
    synchronized List<Frame> snapshot() {
        return kept.stream().map(KeptFrame::frame).toList();
    }
}
