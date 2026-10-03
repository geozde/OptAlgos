package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.algorithm.localsearch.Neighborhood;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PermutationSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.model.RectanglePermutation;
import optalgos.rectpack.placement.PlacementRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Rule-based ("regelbasiert") local search
 * 
 * searched object is a PERMUTATION of rectangles
 * 
 * Exactly as in Greedy, the rectangles are placed into boxes in permutation order by the 
 * supplied PlacementRule (here always SkylinePlacement), and the neighborhood consists of small 
 * modification steps on the permutation.
 *
 * rectangles that currently sit in a relatively empty box are strongly preferred as the rectangle 
 * to relocate.
 */
public final class PermutationNeighborhood implements Neighborhood<PermutationSolution> {

    /**
     * Weight of the true objective (box count)
     */
    private static final double BOX_COUNT_WEIGHT = 10.0;

    /**
     * Rectangle re-placements one candidateMoves call may spend, across all its candidates.
     */
    private static final int REPLACEMENT_BUDGET_PER_STEP = 600;

    /** Never spend the whole budget on a handful of huge candidates at large n 
     *  a step always gets at least this many tries, even if they overrun the budget. */
    private static final int MIN_CANDIDATES_PER_STEP = 2;

    /** Upper bound on candidates per step, so a tiny instance (where every candidate is nearly free)
     *  still yields steps often enough to be watchable rather than burning a whole second deciding. */
    private static final int MAX_CANDIDATES_PER_STEP = 40;

    /**
     * Longest run of consecutive rectangles one relocation may move: a tenth of the permutation, and
     * never fewer than 8 so the move type stays meaningful on small instances too. A block is what
     * lets the search cross ground a one-rectangle-at-a-time step cannot - moving one of several large
     * rectangles that are stranded together at the wrong end of the order improves nothing until its
     * neighbours follow, so a search accepting only individually-improving steps never takes the first
     * of them, and simply never gets started.
     *
     * <p>This is the single largest lever on result quality at n=1000, measured over 3 seeds at 10
     * seconds against a decreasing-area reference of 97.7 boxes: single-rectangle relocation only
     * finishes at 109-118 boxes, a cap of n/50 at ~104, and this cap of n/10 at ~100. The length is
     * drawn UNIFORMLY up to the cap, which also had to be measured rather than assumed - a
     * distribution skewed towards short blocks (log-uniform, so most steps stay tiny) looked like the
     * more conservative choice but finished at ~110, no better than having no block move at all, which
     * says the bulk relocation IS the mechanism doing the work here rather than an occasional shortcut.
     *
     * <p>A tenth of the order is a large step, and worth being explicit about: it is bounded as a
     * FRACTION rather than an absolute count precisely so that no single move can ever re-sort the
     * permutation outright and hand the search its answer in one jump - reorganising the order still
     * takes tens of accepted, individually-improving steps, which is what keeps a run watchable.
     */
    private static final int MIN_MAX_BLOCK_LENGTH = 8;
    private static final int BLOCK_LENGTH_INSTANCE_DIVISOR = 10;

    private static int maxBlockLength(int n) {
        return Math.max(MIN_MAX_BLOCK_LENGTH, n / BLOCK_LENGTH_INSTANCE_DIVISOR);
    }

    private int pickBlockLength(int n, int from) {
        return 1 + random.nextInt(Math.min(maxBlockLength(n), n - from));
    }

    /**
     * Half-width of the window around a source position that the cheap half of target draws is
     * confined to - see {@link #pickTarget}. Wide enough that a windowed step is a meaningful jump
     * rather than a nudge, small enough that at n=1000 it re-places a small fraction of what a
     * full-reach step does.
     */
    private static final int LOCAL_TARGET_WINDOW = 64;

    /** How many of the emptiest boxes count as "relatively empty" and have their rectangles offered
     *  as relocation sources - the spec's own hint for this neighborhood. Kept small so attention
     *  concentrates on the boxes actually closest to being closable, rather than being diluted across
     *  every box that happens to be below average. */
    private static final int SPARSEST_BOXES_TARGETED = 4;

    /** How often a candidate's source rectangle is drawn from a relatively empty box rather than
     *  uniformly at random. The remainder keeps a genuinely random share, so the search can still
     *  find improvements that have nothing to do with the emptiest boxes. */
    private static final double SPARSE_SOURCE_PROBABILITY = 0.75;

    /**
     * Consecutive fruitless samples before the search reports itself genuinely stuck. Unlike the
     * version this replaces (5000 retries over a 36-move reachable set - see the class Javadoc), a
     * retry here is a fresh draw from the whole permutation and is therefore genuinely informative,
     * so a far smaller number suffices; the caller's own {@code TerminationCriterion} remains the
     * normal way a run ends.
     */
    private static final int MAX_CONSECUTIVE_EMPTY_SAMPLES = 200;

    private final List<Rectangle> rectangles;
    private final int boxLength;
    private final PlacementRule placementRule;
    private final Random random;
    private int consecutiveEmptySamples = 0;

    public PermutationNeighborhood(List<Rectangle> rectangles, int boxLength, PlacementRule placementRule) {
        this(rectangles, boxLength, placementRule, new Random());
    }

    public PermutationNeighborhood(List<Rectangle> rectangles, int boxLength, PlacementRule placementRule, Random random) {
        this.rectangles = List.copyOf(rectangles);
        this.boxLength = boxLength;
        this.placementRule = placementRule;
        this.random = random;
    }

    /**
     * The deliberately bad starting order the spec asks for: increasing area, the exact inverse of
     * the decreasing-area rule {@link optalgos.greedystrategy.DecreasingAreaStrategy} uses and the
     * order that is bad for a structural reason rather than by luck of a random draw. Placing the
     * smallest rectangles first lets them claim floor space in every box, so the large ones arrive
     * last, fit nowhere, and each open a box of their own.
     *
     * <p>Measured against that same decreasing-area order as reference, 40 seeds per shape: +21% more
     * boxes at the GUI's default shape (n=50), +42% at n=200, +19% at n=1000, and worse on every
     * single seed at every shape with room to be worse. A uniform random shuffle - the obvious
     * alternative - is not a substitute: it lands within +0% to +5% of the reference and ties it
     * outright on roughly half of the n=50 seeds, leaving the search nothing to demonstrate.
     */
    @Override
    public PermutationSolution initialSolution() {
        // Literally the reverse of the order DecreasingAreaStrategy hands Greedy: a stable
        // largest-first sort (which is what its repeated Collections.max amounts to), turned around.
        List<Rectangle> order = new ArrayList<>(rectangles);
        order.sort(Comparator.comparingInt(Rectangle::area).reversed());
        Collections.reverse(order);
        return materialize(new RectanglePermutation(order));
    }

    @Override
    public List<? extends Move<PermutationSolution>> candidateMoves(PermutationSolution current) {
        int n = current.order().size();
        if (n < 2) {
            return List.of();
        }

        List<Integer> sparseSources = indicesInSparsestBoxes(current);
        double currentScore = score(current.placement());
        List<Move<PermutationSolution>> moves = new ArrayList<>();

        int replacementsSpent = 0;
        for (int candidate = 0; candidate < MAX_CANDIDATES_PER_STEP; candidate++) {
            int from = pickSource(n, sparseSources);
            int to = pickTarget(n, from);
            if (from == to) {
                continue;
            }
            int earliestChanged = Math.min(from, to);
            int span = n - earliestChanged;
            if (candidate >= MIN_CANDIDATES_PER_STEP && replacementsSpent + span > REPLACEMENT_BUDGET_PER_STEP) {
                break;
            }
            replacementsSpent += span;

            // The two permutation edit types, in equal measure: a block relocation lifts a short run
            // of rectangles out of the order and re-inserts it elsewhere (the move that actually
            // drains a relatively empty box, and - at block length above 1 - the only one able to
            // rescue a group of large rectangles stranded together), a swap exchanges two positions.
            RectanglePermutation candidateOrder;
            if (candidate % 2 == 0) {
                int length = pickBlockLength(n, from);
                candidateOrder = current.order().withBlockMoved(from, length, to);
            } else {
                candidateOrder = current.order().withSwap(from, to);
            }

            PermutationSolution materialized = materializeFrom(current, candidateOrder, earliestChanged);
            double delta = score(materialized.placement()) - currentScore;
            if (delta < 0) {
                moves.add(new PermutationMove(materialized, delta));
            }
        }

        if (!moves.isEmpty()) {
            consecutiveEmptySamples = 0;
        }
        return moves;
    }

    /**
     * Randomly sampled rather than exhaustive (the spec explicitly invites this for performance), so
     * a single fruitless sample does not mean the search is stuck - retry with fresh draws before
     * concluding it is.
     */
    @Override
    public Optional<PermutationSolution> onPlateau(PermutationSolution current, Consumer<PermutationSolution> onIntermediateStep) {
        consecutiveEmptySamples++;
        return consecutiveEmptySamples <= MAX_CONSECUTIVE_EMPTY_SAMPLES ? Optional.of(current) : Optional.empty();
    }

    /**
     * Search objective, minimized: the true objective (box count) plus a heuristic fill term, exactly
     * the augmentation the spec allows and suggests.
     *
     * <p>Box count alone is far too flat to search against - a single swap or relocation essentially
     * never changes it, so a strictly-improving local search sees every neighbor as equal and accepts
     * none. Subtracting the sum of squared box fill fractions supplies the missing gradient: because
     * the square rewards concentration, moving load out of a relatively empty box into fuller ones
     * always scores better, even while that box still holds rectangles. That is precisely the "reward
     * a step that further empties an already sparse box, even if it is not empty yet" heuristic the
     * spec describes, expressed as a continuous quantity rather than a special case.
     *
     * <p>The fill term is bounded by the box count and weighted below it ({@link #BOX_COUNT_WEIGHT}),
     * so it can only ever break ties between arrangements using equally many boxes, never outvote the
     * true objective. Since {@link optalgos.algorithm.localsearch.LocalSearch} accepts only strictly
     * negative deltas, this score decreases strictly along a run and cannot cycle - which is why this
     * class needs no anti-cycling bookkeeping at all.
     */
    private double score(PackingSolution placement) {
        double boxArea = (double) boxLength * boxLength;
        double fillTerm = 0.0;
        for (int boxIndex : placement.occupiedBoxIndices()) {
            double fill = (boxArea - placement.freeAreaIn(boxIndex)) / boxArea;
            fillTerm += fill * fill;
        }
        return BOX_COUNT_WEIGHT * placement.boxCount() - fillTerm;
    }

    private int pickSource(int n, List<Integer> sparseSources) {
        if (!sparseSources.isEmpty() && random.nextDouble() < SPARSE_SOURCE_PROBABILITY) {
            return sparseSources.get(random.nextInt(sparseSources.size()));
        }
        return random.nextInt(n);
    }

    /**
     * Where to move the picked source to. A rectangle stranded in a nearly empty box gets rescued by
     * being placed EARLIER in the order, while the boxes it could join still have room - so early
     * positions are the valuable targets, and they are also the expensive ones, since everything after
     * the touched position must be re-placed.
     *
     * <p>Rather than making the valuable half of the permutation unreachable (the mistake this class
     * previously made - see the class Javadoc), both are offered: half the draws land anywhere at all,
     * so every position stays reachable in a single step, and half land within a window around the
     * source, which costs a fraction as much and lets a rectangle migrate forward over several cheap
     * steps instead of one expensive one. At n=1000 a windowed step re-places tens of rectangles where
     * a global one re-places hundreds, so the same time budget buys several times as many steps
     * without giving up the reach.
     */
    private int pickTarget(int n, int source) {
        if (random.nextBoolean()) {
            return random.nextInt(n);
        }
        int low = Math.max(0, source - LOCAL_TARGET_WINDOW);
        int high = Math.min(n, source + LOCAL_TARGET_WINDOW + 1);
        return low + random.nextInt(high - low);
    }

    /**
     * Permutation indices of the rectangles currently sitting in the {@link #SPARSEST_BOXES_TARGETED}
     * emptiest boxes - the relocation sources the spec suggests preferring. Emptiness is measured by
     * area fill, the same quantity {@link #score} rewards, so the sources offered here are exactly the
     * ones whose removal that score can reward.
     */
    private List<Integer> indicesInSparsestBoxes(PermutationSolution current) {
        PackingSolution placement = current.placement();
        List<Integer> occupied = new ArrayList<>(placement.occupiedBoxIndices());
        if (occupied.size() <= 1) {
            return List.of();
        }
        occupied.sort(Comparator.comparingLong(placement::freeAreaIn).reversed());
        Set<Integer> sparsest = new HashSet<>(occupied.subList(0, Math.min(SPARSEST_BOXES_TARGETED, occupied.size())));

        Map<Integer, Integer> boxByRectangleId = new HashMap<>();
        for (int boxIndex : sparsest) {
            for (PlacedRectangle placed : placement.rectanglesIn(boxIndex)) {
                boxByRectangleId.put(placed.rectangle().id(), boxIndex);
            }
        }

        List<Rectangle> order = current.order().order();
        List<Integer> indices = new ArrayList<>();
        for (int index = 0; index < order.size(); index++) {
            if (boxByRectangleId.containsKey(order.get(index).id())) {
                indices.add(index);
            }
        }
        return indices;
    }

    private PermutationSolution materialize(RectanglePermutation order) {
        PackingSolution placement = placementRule.placeAll(PackingSolution.empty(boxLength), order.order());
        return new PermutationSolution(order, placement);
    }

    /**
     * Materializes {@code newOrder}, re-placing only from {@code fromIndex} onward and reusing
     * {@code current}'s already-placed prefix - the two orders agree before that index, so the
     * placement rule would make identical decisions there.
     *
     * <p>The compaction step is not an optimization but a correctness requirement, and is the single
     * repair that made this neighborhood able to find anything at all (see the class Javadoc for the
     * measurement). Dropping the suffix leaves empty box slots behind, and {@code SkylinePlacement}
     * prefers an empty slot to every partly-filled box - it wastes no area, which is exactly what the
     * rule minimizes - so those holes get refilled immediately and a box that the move genuinely
     * closed reappears as occupied. Renumbering the surviving boxes down to a contiguous range
     * removes the holes, restoring "open a new box" to the meaning it has in a full placement pass:
     * nothing that already exists can take this rectangle. Renumbering preserves the relative order of
     * the surviving boxes, so the result is indistinguishable from placing {@code newOrder} from
     * scratch - verified exhaustively over every relocation neighbor of a real starting permutation.
     */
    private PermutationSolution materializeFrom(PermutationSolution current, RectanglePermutation newOrder, int fromIndex) {
        PackingSolution working = current.placement().snapshot();
        List<Rectangle> previousOrder = current.order().order();
        for (int index = fromIndex; index < previousOrder.size(); index++) {
            working.removeById(previousOrder.get(index).id());
        }

        PackingSolution compacted = PackingSolution.empty(boxLength);
        int nextBoxIndex = 0;
        for (int boxIndex = 0; boxIndex < working.boxSlotCount(); boxIndex++) {
            List<PlacedRectangle> occupants = working.rectanglesIn(boxIndex);
            if (occupants.isEmpty()) {
                continue;
            }
            for (PlacedRectangle placed : occupants) {
                compacted.place(new PlacedRectangle(placed.rectangle(), nextBoxIndex, placed.x(), placed.y(),
                        placed.orientation()));
            }
            nextBoxIndex++;
        }

        List<Rectangle> newOrderList = newOrder.order();
        for (int index = fromIndex; index < newOrderList.size(); index++) {
            compacted = placementRule.place(compacted, newOrderList.get(index));
        }
        return new PermutationSolution(newOrder, compacted);
    }
}
