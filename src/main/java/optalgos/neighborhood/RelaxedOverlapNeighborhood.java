package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.algorithm.localsearch.Neighborhood;
import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The geometry-based neighborhood, adapted to the partial overlapping
 * 
 * Rects may overlap by a percentage that starts at 100 and eventually falls to 0
 * with a guaranteed overlap-free result at the end. 
 * 
 * Local Search is unaware that this is happening. It is hidden enitrely in candidateMoves in Neighborhood.
 *
 * How the search works:
 * Start from the compact, fully-overlapping arrangement: every rects stacked into as few boxes as 
 * the total area could reasonable occupy. 
 * As OverlapSchedule tightens, the penalty on tolerated overlap violations grows past what an extra 
 * box costs. 
 * Once tolerance reaches zero the remaining budget is used for the usual refinement
 * (relocate, re-nest, drain boxes). This also adds reheats, which briefly re-permit overlap so the 
 * search can dissolve a stuck arrangement and re-compact again.
 */
public final class RelaxedOverlapNeighborhood implements Neighborhood<PackingSolution> {

    /**
     * Cost per box. The true objective.
     */
    private static final double BOX_COUNT_WEIGHT = 10.0;

    /** Weight on how tall a box's skyline stands. */
    private static final double COMPACTION_WEIGHT = 1.0;

    private static final int MAX_SOURCE_RECTANGLES_PER_STEP = 12;
    private static final int MAX_DESTINATION_BOXES_TRIED = 10;
    private static final int DIVERSITY_DESTINATION_BOXES = 3;
    private static final int EXHAUSTIVE_DESTINATION_INSTANCE_LIMIT = 200;

    private static final int RESOLVE_LOADED_BOXES_PER_STEP = 2;

    private static final int MAX_STACK_DESTINATIONS = 3;
    private static final int MAX_STACK_ANCHORS = 4;

    private static final double MIN_REHEAT_STRENGTH = 0.15;

    private static final int MAX_SWAP_DESTINATION_BOXES = 5;
    private static final int MAX_SWAP_CANDIDATES_PER_BOX = 3;
    private static final int MAX_SWAP_ELIGIBLE_OCCUPANCY = 15;

    private static final int MAX_DRAIN_ATTEMPTS_PER_PLATEAU = 6;
    private static final int REHEAT_AFTER_FRUITLESS_PLATEAUS = 40;
    private static final int MAX_CONSECUTIVE_FRUITLESS_PLATEAUS = 20_000;
    /** Each fruitless plateau nudges the anneal along.
     *  A run given far less time than OverlapSchedule's horizon still tightens */
    private static final double STAGNATION_PROGRESS_PER_PLATEAU = 1.0 / 4_000.0;

    private static final int FEASIBILITY_CHECK_INTERVAL = 8;

    private final List<Rectangle> rectangles;
    private final int boxLength;
    private final long boxArea;
    private final SkylinePlacement placement;
    private final Random random;
    private final OverlapSchedule schedule;

    private int drainCursor = 0;
    private int fruitlessPlateaus = 0;
    private int boxCountAtLastReheat = Integer.MAX_VALUE;
    private int fruitlessReheats = 0;
    /** Elapsed time at the moment the search first reached a feasible arrangement. */
    private long firstFeasibleNanos = -1;
    private long stepsSinceFeasibilityCheck = 0;
    private PackingSolution bestFeasible;
    private int bestFeasibleBoxCount = Integer.MAX_VALUE;
    private double bestFeasibleWorstFill = -1.0;
    /**
     * Boxes whose current contents have already been put through addResolveBoxMove without
     * it paying off. 
     * That move re-nests a whole box and is by far the most expensive thing per step.
     */
    private final Map<Integer, Integer> resolveRejected = new HashMap<>();
    private int resolveRejectedBucket = Integer.MIN_VALUE;

    public RelaxedOverlapNeighborhood(List<Rectangle> rectangles, int boxLength, SkylinePlacement placement) {
        this(rectangles, boxLength, placement, new Random());
    }

    public RelaxedOverlapNeighborhood(List<Rectangle> rectangles, int boxLength, SkylinePlacement placement,
                                       Random random) {
        this(rectangles, boxLength, placement, random, OverlapSchedule.DEFAULT_RUN_HORIZON);
    }

    /**
     * runHorizon is how long this neighborhood should expect the search to be given
     */
    public RelaxedOverlapNeighborhood(List<Rectangle> rectangles, int boxLength, SkylinePlacement placement,
                                       Random random, Duration runHorizon) {
        this.rectangles = List.copyOf(rectangles);
        this.boxLength = boxLength;
        this.boxArea = (long) boxLength * boxLength;
        this.placement = placement;
        this.random = random;
        this.schedule = new OverlapSchedule(runHorizon);
    }

    /**
     * Initial solution
     * 
     * Every rectangle stacked, fully overlapping, into fewest number of boxes 
     * that could theoretically hold everything, ignoring geometry. 
     */
    @Override
    public PackingSolution initialSolution() {
        long totalArea = 0;
        for (Rectangle rectangle : rectangles) {
            totalArea += rectangle.area();
        }
        int targetBoxCount = Math.max(1, (int) ((totalArea + boxArea - 1) / boxArea));

        PackingSolution solution = PackingSolution.empty(boxLength);
        for (int i = 0; i < rectangles.size(); i++) {
            solution.place(new PlacedRectangle(rectangles.get(i), i % targetBoxCount, 0, 0, Orientation.NORMAL));
        }
        resetSearchState();
        schedule.start();
        return solution;
    }

    // runs are stateful. We must reset them occassionally (each new run)
    private void resetSearchState() {
        drainCursor = 0;
        fruitlessPlateaus = 0;
        boxCountAtLastReheat = Integer.MAX_VALUE;
        fruitlessReheats = 0;
        firstFeasibleNanos = -1;
        stepsSinceFeasibilityCheck = 0;
        bestFeasible = null;
        bestFeasibleBoxCount = Integer.MAX_VALUE;
        bestFeasibleWorstFill = -1.0;
        resolveRejected.clear();
        resolveRejectedBucket = Integer.MIN_VALUE;
    }

    /** cache invalidation key
     *  decides when to reset the resolveRejected cache 
     */
    private static int scheduleBucket(double allowed, double penalty) {
        return (int) (allowed * 20) * 1000 + (int) (penalty / 100);
    }

    /** for a sampled subset of rectangles, generates candidate moves 
     * starts from a compact, overlapping (infeasible) packing and 
     * gradually tightens a tolerance for overlap down to zero
     * 
    */
    @Override
    public List<? extends Move<PackingSolution>> candidateMoves(PackingSolution current) {
        // remember current if we need to reheat 
        rememberIfBestFeasible(current);

        double allowed = schedule.allowedFraction();
        double penalty = schedule.penaltyWeight();
        int bucket = scheduleBucket(allowed, penalty);
        // might need to wipe resolveRejectedBucket
        if (bucket != resolveRejectedBucket) {
            resolveRejectedBucket = bucket;
            resolveRejected.clear();
        }

        List<Move<PackingSolution>> moves = new ArrayList<>();
        List<BoxSpace> destinations = mostSpaciousBoxes(current);
        // One skyline profile per destination box
        // It is built the first time any source tries getting into it and reused afterward
        // Only one move gets applied at the very end.
        Map<Integer, SkylinePlacement.BoxProfile> destinationProfiles = new HashMap<>();

        // for each box worth checking, try renesting whole box (needed to remove overlap!)
        for (int boxIndex : boxesWorthResolving(current)) {
            addResolveBoxMove(current, boxIndex, destinations, allowed, penalty, moves);
        }
        // Looking for a reusable empty slot
        // answer is the same for every source this step, 
        // so it is resolved once rather than per sampled rect
        int freshSlot = firstEmptySlot(current);

        // loop over source rects
        for (PlacedRectangle source : sampleSources(current)) {
            addRelocationMoves(current, source, destinations, destinationProfiles, freshSlot, allowed, penalty, moves);
            addRepositionWithinBoxMove(current, source, allowed, penalty, moves);
            addRotateInPlaceMove(current, source, allowed, penalty, moves);
            addSwapMoves(current, source, destinations, allowed, penalty, moves);
            if (allowed > 0.0) {
                addOverlapCreatingMoves(current, source, allowed, penalty, moves);
            }
        }
        // Resets  stagnation counter when this step actually produced any candidates
        if (!moves.isEmpty()) {
            fruitlessPlateaus = 0;
        }
        return moves;
    }

    /**
     * No single move of the samples moves improved on  current solution.
     * 
     * First try to close a whole box outright with drainOneBox.
     * Failing that, nudge the anneal along and periodically reheat.
     *
     * This never returns empty while the schedule can still do something.
     * Run ends when TerminationCriterion says it does.
     */
    @Override
    public Optional<PackingSolution> onPlateau(PackingSolution current, Consumer<PackingSolution> onIntermediateStep) {
        // save current state if it's good
        rememberIfBestFeasible(current);

        // first try draining
        if (drainOneBox(current)) {
            fruitlessPlateaus = 0;
            return Optional.of(current);
        }
        fruitlessPlateaus++; // unsuccessful :(

        // then try reheating
        schedule.onStagnation(STAGNATION_PROGRESS_PER_PLATEAU);
        // Reheat only out of a state that is feasible
        if (fruitlessPlateaus % REHEAT_AFTER_FRUITLESS_PLATEAUS == 0
                && !current.hasAnyOverlap() && schedule.reheat(nextReheatStrength())) {
            return Optional.of(current);
        }

        // this is a truly fruitless streak
        if (fruitlessPlateaus > MAX_CONSECUTIVE_FRUITLESS_PLATEAUS && schedule.isPastReheatDeadline()) {
            // Genuinely exhausted, and only then accept plateau
            return Optional.empty();
        }
        return Optional.of(current);
    }

    /**
     * Enforcement of guaranteed overlap-free final result
     * LocalSearch calls this exactly once, on whatever state it is about to return
     */
    @Override
    public PackingSolution finish(PackingSolution current, Consumer<PackingSolution> onIntermediateStep) {
        rememberIfBestFeasible(current);
        PackingSolution feasible = current.hasAnyOverlap() ? repair(current, onIntermediateStep) : current;
        if (bestFeasible != null && isBetterThan(bestFeasible, feasible)) {
            onIntermediateStep.accept(bestFeasible);
            return bestFeasible;
        }
        return feasible;
    }

    /** Fewer boxes wins
     *  Between equal box counts, the one whose emptiest box is fullest wins
     *  Both arguments must already be overlap-free. */
    private boolean isBetterThan(PackingSolution candidate, PackingSolution incumbent) {
        if (candidate.boxCount() != incumbent.boxCount()) {
            return candidate.boxCount() < incumbent.boxCount();
        }
        return worstBoxFill(candidate) > worstBoxFill(incumbent);
    }

    /**
     * Remembers the fewest-box overlap-free state seen so far. 
     * 
     * Without this the run returns whatever it happens to end on.
     * Ordered.
     */
    private void rememberIfBestFeasible(PackingSolution current) {
        int boxCount = current.boxCount();
        if (boxCount > bestFeasibleBoxCount) {
            return;
        }
        if (++stepsSinceFeasibilityCheck % FEASIBILITY_CHECK_INTERVAL != 0) {
            return;
        }
        double worstFill = worstBoxFill(current);
        if (boxCount == bestFeasibleBoxCount && worstFill <= bestFeasibleWorstFill) {
            return;
        }
        if (current.hasAnyOverlap()) {
            return;
        }
        if (firstFeasibleNanos < 0) {
            firstFeasibleNanos = schedule.elapsedNanos();
        }
        bestFeasibleBoxCount = boxCount;
        bestFeasibleWorstFill = worstFill;
        bestFeasible = current.snapshot();
    }

    /**
     * Fill of the emptiest box. The tie-break between feasible solutions using the same number of
     * boxes, higher being better. 
     */
    private double worstBoxFill(PackingSolution current) {
        double worst = Double.MAX_VALUE;
        for (int boxIndex : current.occupiedBoxIndices()) {
            worst = Math.min(worst, Math.min(1.0, usedAreaIn(current, boxIndex) / (double) boxArea));
        }
        return worst == Double.MAX_VALUE ? 0.0 : worst;
    }

    // ---------------------------------------------------------------------------------------
    // Objective: a state potential, so every move's score is an exact difference of one function
    // ---------------------------------------------------------------------------------------

    /**
     * One occupied box's contribution to the search objective
     * same shape as GeometricNeighborhood
     */
    private double boxPotential(long usedArea, int maxTop) {
        if (usedArea <= 0) {
            return 0.0;
        }
        double fill = Math.min(1.0, usedArea / (double) boxArea);
        return BOX_COUNT_WEIGHT - fill * fill + COMPACTION_WEIGHT * (maxTop / (double) boxLength);
    }

    /** One box's full contribution
     *  geometry plus the penalised overlap among its own occupants. */
    private double potentialOf(List<PlacedRectangle> occupants, double allowed, double penalty) {
        if (occupants.isEmpty()) {
            return 0.0;
        }
        long used = 0;
        int top = 0;
        for (PlacedRectangle placed : occupants) {
            used += placed.rectangle().area();
            top = Math.max(top, placed.top());
        }
        return boxPotential(used, top) + penalty * pairExcess(occupants, allowed);
    }

    /** Overlap in excess of what is currently tolerated, summed over every pair sharing this box. */
    private static double pairExcess(List<PlacedRectangle> occupants, double allowed) {
        double total = 0.0;
        for (int i = 0; i < occupants.size(); i++) {
            for (int j = i + 1; j < occupants.size(); j++) {
                double excess = occupants.get(i).overlapRatioWith(occupants.get(j)) - allowed;
                if (excess > 0) {
                    total += excess;
                }
            }
        }
        return total;
    }

    /** Excess overlap between one placement and a box's occupants */
    private static double excessAgainst(PlacedRectangle candidate, List<PlacedRectangle> occupants,
                                         PlacedRectangle excluded, double allowed) {
        double total = 0.0;
        for (PlacedRectangle other : occupants) {
            if (other.equals(excluded)) {
                continue;
            }
            double excess = candidate.overlapRatioWith(other) - allowed;
            if (excess > 0) {
                total += excess;
            }
        }
        return total;
    }

    /**
     * Exact change in the objective caused by moving one rectangle from to.
     * Only the one or two boxes involved can change, and within them only the pairs involving this
     * rectangle, so everything else cancels.
     */
    private double relocationDelta(PackingSolution current, PlacedRectangle from, PlacedRectangle to,
                                    double allowed, double penalty) {
        int sourceBox = from.boxIndex();
        int destBox = to.boxIndex();
        List<PlacedRectangle> sourceOccupants = current.rectanglesIn(sourceBox);
        long sourceUsed = usedAreaIn(current, sourceBox);
        double overlapBefore = excessAgainst(from, sourceOccupants, from, allowed);

        if (sourceBox == destBox) {
            int before = maxTopOf(sourceOccupants);
            int after = Math.max(maxTopExcluding(sourceOccupants, from), to.top());
            double geometry = boxPotential(sourceUsed, after) - boxPotential(sourceUsed, before);
            double overlapAfter = excessAgainst(to, sourceOccupants, from, allowed);
            return geometry + penalty * (overlapAfter - overlapBefore);
        }

        List<PlacedRectangle> destOccupants = current.rectanglesIn(destBox);
        long destUsed = usedAreaIn(current, destBox);
        long area = from.rectangle().area();
        int destTop = maxTopOf(destOccupants);
        double before = boxPotential(sourceUsed, maxTopOf(sourceOccupants)) + boxPotential(destUsed, destTop);
        double after = boxPotential(sourceUsed - area, maxTopExcluding(sourceOccupants, from))
                + boxPotential(destUsed + area, Math.max(destTop, to.top()));
        double overlapAfter = excessAgainst(to, destOccupants, null, allowed);
        return (after - before) + penalty * (overlapAfter - overlapBefore);
    }

    // ---------------------------------------------------------------------------------------
    // Candidate selection
    // ---------------------------------------------------------------------------------------

    private record BoxSpace(int boxIndex, long freeArea) {
    }

    /**
     * Destination boxes for relocation, emptiest first. 
     * The tail DIVERSITY_DESTINATION_BOXES entries are drawn at random from the emptiest boxes.
     * We are not just using the emptiest ones, to get more randomness and identify potential moves.
     */
    private List<BoxSpace> mostSpaciousBoxes(PackingSolution current) {
        List<BoxSpace> spaces = new ArrayList<>(current.boxCount());
        for (int boxIndex : current.occupiedBoxIndices()) {
            spaces.add(new BoxSpace(boxIndex, current.freeAreaIn(boxIndex)));
        }
        if (spaces.size() <= MAX_DESTINATION_BOXES_TRIED || rectangles.size() <= EXHAUSTIVE_DESTINATION_INSTANCE_LIMIT) {
            return spaces;
        }
        spaces.sort(Comparator.comparingLong((BoxSpace b) -> -b.freeArea()));
        int topCount = MAX_DESTINATION_BOXES_TRIED - DIVERSITY_DESTINATION_BOXES;
        List<BoxSpace> chosen = new ArrayList<>(spaces.subList(0, topCount));
        List<BoxSpace> rest = new ArrayList<>(spaces.subList(topCount, spaces.size()));
        Collections.shuffle(rest, random);
        chosen.addAll(rest.subList(0, Math.min(DIVERSITY_DESTINATION_BOXES, rest.size())));
        return chosen;
    }

    /**
     * Rectangles worth trying to move this step, drawn from both ends of the occupancy ranking:
     * half from the most-loaded boxes, which is where we have most overlaps, and half from the 
     * sparsest, which is where a box can still realistically be emptied. 
     */
    private List<PlacedRectangle> sampleSources(PackingSolution current) {
        if (rectangles.size() <= MAX_SOURCE_RECTANGLES_PER_STEP) {
            return current.allPlacedRectangles();
        }
        List<Integer> byLoadAscending = new ArrayList<>(current.occupiedBoxIndices());
        Collections.shuffle(byLoadAscending, random);   // random order among ties
        byLoadAscending.sort(Comparator.comparingLong(b -> usedAreaIn(current, b)));

        List<PlacedRectangle> chosen = new ArrayList<>(MAX_SOURCE_RECTANGLES_PER_STEP);
        int half = MAX_SOURCE_RECTANGLES_PER_STEP / 2;
        for (int i = byLoadAscending.size() - 1; i >= 0 && chosen.size() < half; i--) {
            takeFrom(current, byLoadAscending.get(i), half, chosen);
        }
        for (int boxIndex : byLoadAscending) {
            if (chosen.size() >= MAX_SOURCE_RECTANGLES_PER_STEP) {
                break;
            }
            takeFrom(current, boxIndex, MAX_SOURCE_RECTANGLES_PER_STEP, chosen);
        }
        return chosen;
    }

    private static void takeFrom(PackingSolution current, int boxIndex, int limit, List<PlacedRectangle> chosen) {
        for (PlacedRectangle placed : current.rectanglesIn(boxIndex)) {
            if (chosen.size() >= limit) {
                return;
            }
            if (!chosen.contains(placed)) {
                chosen.add(placed);
            }
        }
    }

    /** try the boxes most likely to need resolving, i.e. most overlap
     * approximate this with total rect area in box.
     * Then also add some random picks.
    */
    private List<Integer> boxesWorthResolving(PackingSolution current) {
        List<Integer> occupied = new ArrayList<>(current.occupiedBoxIndices());
        if (occupied.size() <= RESOLVE_LOADED_BOXES_PER_STEP + 1) {
            return occupied;
        }
        occupied.sort(Comparator.comparingLong((Integer b) -> -usedAreaIn(current, b)));
        List<Integer> chosen = new ArrayList<>(occupied.subList(0, RESOLVE_LOADED_BOXES_PER_STEP));
        int extra = occupied.get(RESOLVE_LOADED_BOXES_PER_STEP
                + random.nextInt(occupied.size() - RESOLVE_LOADED_BOXES_PER_STEP));
        chosen.add(extra);
        return chosen;
    }

    // ---------------------------------------------------------------------------------------
    // Move families
    // ---------------------------------------------------------------------------------------

    /**
     * Relocates one rectangle into another box at a position that overlaps nothing, or into a box
     * of its own.
     *
     */
    private void addRelocationMoves(PackingSolution current, PlacedRectangle source, List<BoxSpace> destinations,
                                     Map<Integer, SkylinePlacement.BoxProfile> destinationProfiles, int freshSlot,
                                     double allowed, double penalty, List<Move<PackingSolution>> moves) {
        int sourceBoxIndex = source.boxIndex();
        long sourceArea = source.rectangle().area();

        for (BoxSpace space : destinations) {
            if (space.boxIndex() == sourceBoxIndex || space.freeArea() < sourceArea) {
                continue;
            }
            SkylinePlacement.BoxProfile profile = destinationProfiles.computeIfAbsent(space.boxIndex(),
                    i -> placement.profileFor(current.rectanglesIn(i), boxLength));
            Optional<SkylinePlacement.BoxPosition> position = placement.bestPositionInBox(profile, source.rectangle());
            if (position.isEmpty()) {
                continue;
            }
            SkylinePlacement.BoxPosition p = position.get();
            addIfImproving(current, source,
                    new PlacedRectangle(source.rectangle(), space.boxIndex(), p.x(), p.y(), p.orientation()),
                    allowed, penalty, moves);
        }

        if (freshSlot != sourceBoxIndex) {
            addIfImproving(current, source,
                    new PlacedRectangle(source.rectangle(), freshSlot, 0, 0, Orientation.NORMAL),
                    allowed, penalty, moves);
        }
    }

    /** Repositions a rect at the best position in its own box. 
     *  This is how a pile of rects sharing one corner at the start turns into a packed box */
    private void addRepositionWithinBoxMove(PackingSolution current, PlacedRectangle source,
                                             double allowed, double penalty, List<Move<PackingSolution>> moves) {
        List<PlacedRectangle> others = withoutSelf(current.rectanglesIn(source.boxIndex()), source);
        if (others.isEmpty()) {
            return;
        }
        Optional<SkylinePlacement.BoxPosition> position =
                placement.bestPositionInBox(others, boxLength, source.rectangle());
        if (position.isEmpty()) {
            return;
        }
        SkylinePlacement.BoxPosition p = position.get();
        if (p.x() == source.x() && p.y() == source.y() && p.orientation() == source.orientation()) {
            return;   // not actually a move
        }
        addIfImproving(current, source,
                new PlacedRectangle(source.rectangle(), source.boxIndex(), p.x(), p.y(), p.orientation()),
                allowed, penalty, moves);
    }

    private void addRotateInPlaceMove(PackingSolution current, PlacedRectangle source,
                                       double allowed, double penalty, List<Move<PackingSolution>> moves) {
        Orientation flipped = source.orientation() == Orientation.NORMAL ? Orientation.ROTATED : Orientation.NORMAL;
        PlacedRectangle rotated = new PlacedRectangle(source.rectangle(), source.boxIndex(), source.x(), source.y(), flipped);
        if (!rotated.fitsWithinBox(boxLength)) {
            return;
        }
        addIfImproving(current, source, rotated, allowed, penalty, moves);
    }

    /**
     * Exchanges source with a rectangle in another box. 
     * 
     * Fixes problem that we cannot empty a box because there is no gap with the right shape.
     * Trading with another rect can open such a gap => enable to later empty box.
     * 
     */
    private void addSwapMoves(PackingSolution current, PlacedRectangle source, List<BoxSpace> destinations,
                               double allowed, double penalty, List<Move<PackingSolution>> moves) {
        int sourceBoxIndex = source.boxIndex();
        List<PlacedRectangle> sourceBoxOthers = withoutSelf(current.rectanglesIn(sourceBoxIndex), source);
        if (sourceBoxOthers.size() > MAX_SWAP_ELIGIBLE_OCCUPANCY) {
            return;   // too many occupants for the repeated per-candidate skyline searches to be worth it
        }
        int destinationsTried = 0;
        for (BoxSpace space : destinations) {
            int destBoxIndex = space.boxIndex();
            if (destBoxIndex == sourceBoxIndex) {
                continue;
            }
            List<PlacedRectangle> destOccupants = current.rectanglesIn(destBoxIndex);
            if (destOccupants.isEmpty() || destOccupants.size() > MAX_SWAP_ELIGIBLE_OCCUPANCY) {
                continue;
            }
            if (destinationsTried++ >= MAX_SWAP_DESTINATION_BOXES) {
                break;
            }
            int candidatesTried = 0;
            for (PlacedRectangle candidate : destOccupants) {
                if (candidatesTried++ >= MAX_SWAP_CANDIDATES_PER_BOX) {
                    break;
                }
                List<PlacedRectangle> destBoxOthers = withoutSelf(destOccupants, candidate);
                Optional<SkylinePlacement.BoxPosition> sourceIntoDest =
                        placement.bestPositionInBox(destBoxOthers, boxLength, source.rectangle());
                if (sourceIntoDest.isEmpty()) {
                    continue;
                }
                Optional<SkylinePlacement.BoxPosition> candidateIntoSource =
                        placement.bestPositionInBox(sourceBoxOthers, boxLength, candidate.rectangle());
                if (candidateIntoSource.isEmpty()) {
                    continue;
                }
                SkylinePlacement.BoxPosition sp = sourceIntoDest.get();
                SkylinePlacement.BoxPosition cp = candidateIntoSource.get();
                PlacedRectangle sourceTo = new PlacedRectangle(source.rectangle(), destBoxIndex,
                        sp.x(), sp.y(), sp.orientation());
                PlacedRectangle candidateTo = new PlacedRectangle(candidate.rectangle(), sourceBoxIndex,
                        cp.x(), cp.y(), cp.orientation());
                double delta = swapDelta(current, source, sourceTo, candidate, candidateTo, allowed, penalty);
                if (delta < 0) {
                    moves.add(new SwapMove(source, sourceTo, candidate, candidateTo, delta));
                }
            }
        }
    }

    /** Exact objective change for a swap. Both boxes' geometry terms move, and so do the overlap
     *  pairs involving either rectangle; every other pair is untouched and cancels. Both new
     *  placements come from a skyline search against the other occupants, so neither introduces
     *  overlap - the "after" overlap terms are the ones the two departing rectangles leave behind. */
    private double swapDelta(PackingSolution current, PlacedRectangle sourceFrom, PlacedRectangle sourceTo,
                              PlacedRectangle candidateFrom, PlacedRectangle candidateTo,
                              double allowed, double penalty) {
        int boxA = sourceFrom.boxIndex();
        int boxB = candidateFrom.boxIndex();
        List<PlacedRectangle> occupantsA = current.rectanglesIn(boxA);
        List<PlacedRectangle> occupantsB = current.rectanglesIn(boxB);
        long usedA = usedAreaIn(current, boxA);
        long usedB = usedAreaIn(current, boxB);
        long areaA = sourceFrom.rectangle().area();
        long areaB = candidateFrom.rectangle().area();

        double geometryBefore = boxPotential(usedA, maxTopOf(occupantsA)) + boxPotential(usedB, maxTopOf(occupantsB));
        double geometryAfter =
                boxPotential(usedA - areaA + areaB, Math.max(maxTopExcluding(occupantsA, sourceFrom), candidateTo.top()))
                        + boxPotential(usedB - areaB + areaA,
                        Math.max(maxTopExcluding(occupantsB, candidateFrom), sourceTo.top()));

        double overlapBefore = excessAgainst(sourceFrom, occupantsA, sourceFrom, allowed)
                + excessAgainst(candidateFrom, occupantsB, candidateFrom, allowed);
        double overlapAfter = excessAgainst(candidateTo, occupantsA, sourceFrom, allowed)
                + excessAgainst(sourceTo, occupantsB, candidateFrom, allowed);
        return (geometryAfter - geometryBefore) + penalty * (overlapAfter - overlapBefore);
    }

    /**
     * Deliberately overlapping relocations.
     * 
     * Offered only while the schedule actually tolerates overlap
     */
    private void addOverlapCreatingMoves(PackingSolution current, PlacedRectangle source,
                                          double allowed, double penalty, List<Move<PackingSolution>> moves) {
        List<Integer> occupied = new ArrayList<>(current.occupiedBoxIndices());
        if (occupied.size() < 2) {
            return;
        }
        for (int attempt = 0; attempt < MAX_STACK_DESTINATIONS; attempt++) {
            int destBoxIndex = occupied.get(random.nextInt(occupied.size()));
            if (destBoxIndex == source.boxIndex()) {
                continue;
            }
            List<PlacedRectangle> destOccupants = current.rectanglesIn(destBoxIndex);
            PlacedRectangle best = null;
            double bestDelta = 0.0;
            int anchors = Math.min(MAX_STACK_ANCHORS, destOccupants.size());
            for (int i = 0; i < anchors; i++) {
                PlacedRectangle anchor = destOccupants.get(i);
                PlacedRectangle candidate = fittingPlacement(source.rectangle(), destBoxIndex,
                        anchor.x(), anchor.y(), source.orientation());
                if (candidate == null) {
                    continue;
                }
                double delta = relocationDelta(current, source, candidate, allowed, penalty);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = candidate;
                }
            }
            if (best != null) {
                moves.add(new RelocationMove(source, best, bestDelta));
            }
        }
    }

    /**
     * Convert a compact, overlapping box into a packing: 
     * re-lay the whole box out largest-first at disjoint positions, and re-position whatever no 
     * longer fits, either into other boxes with genuine room, or, failing that, into boxes of its own.
     *
     */
    private void addResolveBoxMove(PackingSolution current, int boxIndex, List<BoxSpace> destinations,
                                    double allowed, double penalty, List<Move<PackingSolution>> moves) {
        List<PlacedRectangle> occupants = current.rectanglesIn(boxIndex);
        if (occupants.size() < 2) {
            return;
        }
        int signature = occupants.hashCode();
        if (Integer.valueOf(signature).equals(resolveRejected.get(boxIndex))) {
            return;   // same contents, same schedule bucket, already known not to pay off
        }
        List<Rectangle> items = new ArrayList<>(occupants.size());
        for (PlacedRectangle placed : occupants) {
            items.add(placed.rectangle());
        }
        items.sort(Comparator.comparingInt(Rectangle::area).reversed());

        List<Rectangle> leftovers = new ArrayList<>();
        Map<Integer, List<PlacedRectangle>> staged = new LinkedHashMap<>();
        staged.put(boxIndex, layOutLargestFirst(boxIndex, items, leftovers));
        if (leftovers.isEmpty() && staged.get(boxIndex).size() == occupants.size()
                && samePlacements(occupants, staged.get(boxIndex))) {
            resolveRejected.put(boxIndex, signature);
            return;   // re-nesting changed nothing
        }
        rehouseLeftovers(current, boxIndex, leftovers, destinations, staged);

        double delta = 0.0;
        for (Map.Entry<Integer, List<PlacedRectangle>> entry : staged.entrySet()) {
            delta += potentialOf(entry.getValue(), allowed, penalty)
                    - potentialOf(current.rectanglesIn(entry.getKey()), allowed, penalty);
        }
        if (delta >= 0) {
            resolveRejected.put(boxIndex, signature);
            return;
        }
        resolveRejected.remove(boxIndex);

        List<PlacedRectangle> removed = new ArrayList<>();
        List<PlacedRectangle> added = new ArrayList<>();
        for (Map.Entry<Integer, List<PlacedRectangle>> entry : staged.entrySet()) {
            removed.addAll(current.rectanglesIn(entry.getKey()));
            added.addAll(entry.getValue());
        }
        // Ascending box index, so that any genuinely new slot is opened in the order
        // PackingSolution#place requires (a new slot's index must equal the current slot count).
        added.sort(Comparator.comparingInt(PlacedRectangle::boxIndex));
        moves.add(new RepackMove(removed, added, delta));
    }

    /** Seats leftovers in boxes with real room, opening fresh slots only for what nothing
     *  existing can take. */
    private void rehouseLeftovers(PackingSolution current, int sourceBoxIndex, List<Rectangle> leftovers,
                                   List<BoxSpace> destinations, Map<Integer, List<PlacedRectangle>> staged) {
        Deque<Integer> freeSlots = emptySlots(current);
        int nextNewSlot = current.boxSlotCount();
        int openedSlot = -1;

        for (Rectangle item : leftovers) {
            if (seatIn(current, item, sourceBoxIndex, destinations, staged, false)) {
                continue;
            }
            if (openedSlot < 0 || !seatInSlot(current, item, openedSlot, staged)) {
                openedSlot = freeSlots.isEmpty() ? nextNewSlot++ : freeSlots.poll();
                // A rectangle whose sides both fit within L always fits in an empty box
                seatInSlot(current, item, openedSlot, staged);
            }
        }
    }

    /**
     * Finds a box other than sourceBoxIndex that can take item
     * staging the result, not applying it directly
     *
     * used only when emptying a box on a plateau, where the alternative is the box staying open 
     */
    private boolean seatIn(PackingSolution current, Rectangle item, int sourceBoxIndex,
                            List<BoxSpace> destinations, Map<Integer, List<PlacedRectangle>> staged,
                            boolean allowRepack) {
        for (boolean repack : allowRepack ? REPACK_PASSES : DIRECT_PASS_ONLY) {
            for (BoxSpace space : destinations) {
                if (space.boxIndex() == sourceBoxIndex) {
                    continue;
                }
                List<PlacedRectangle> alreadyStaged = staged.get(space.boxIndex());
                long used = alreadyStaged != null ? areaOf(alreadyStaged) : usedAreaIn(current, space.boxIndex());
                if (boxArea - used < item.area()) {
                    continue;
                }
                if (repack ? repackToFit(current, item, space.boxIndex(), staged)
                        : seatInSlot(current, item, space.boxIndex(), staged)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final boolean[] DIRECT_PASS_ONLY = {false};
    private static final boolean[] REPACK_PASSES = {false, true};

    /** Re-orders a destination box from scratch with item added. Rects are sorted largest-first. 
     *  Succeeds only if EVERY rectangle then fits */
    private boolean repackToFit(PackingSolution current, Rectangle item, int boxIndex,
                                 Map<Integer, List<PlacedRectangle>> staged) {
        List<PlacedRectangle> occupants = stagedContents(current, staged, boxIndex);
        List<Rectangle> items = new ArrayList<>(occupants.size() + 1);
        for (PlacedRectangle placed : occupants) {
            items.add(placed.rectangle());
        }
        items.add(item);
        items.sort(Comparator.comparingInt(Rectangle::area).reversed());

        List<Rectangle> leftovers = new ArrayList<>();
        List<PlacedRectangle> repacked = layOutLargestFirst(boxIndex, items, leftovers);
        if (!leftovers.isEmpty()) {
            return false;
        }
        staged.put(boxIndex, repacked);
        return true;
    }

    private boolean seatInSlot(PackingSolution current, Rectangle item, int boxIndex,
                                Map<Integer, List<PlacedRectangle>> staged) {
        List<PlacedRectangle> occupants = stagedContents(current, staged, boxIndex);
        Optional<SkylinePlacement.BoxPosition> position = placement.bestPositionInBox(occupants, boxLength, item);
        if (position.isEmpty()) {
            return false;
        }
        SkylinePlacement.BoxPosition p = position.get();
        occupants.add(new PlacedRectangle(item, boxIndex, p.x(), p.y(), p.orientation()));
        return true;
    }

    private static List<PlacedRectangle> stagedContents(PackingSolution current,
                                                         Map<Integer, List<PlacedRectangle>> staged, int boxIndex) {
        return staged.computeIfAbsent(boxIndex, i -> new ArrayList<>(current.rectanglesIn(i)));
    }

    /** Lays items out disjointly into one box, in the order given.
     *  Collects whatever does not fit into leftovers rather than failing outright
     * 
     *  This is the partial-progress version of a re-nest! */
    private List<PlacedRectangle> layOutLargestFirst(int boxIndex, List<Rectangle> items, List<Rectangle> leftovers) {
        List<PlacedRectangle> placed = new ArrayList<>(items.size());
        for (Rectangle item : items) {
            Optional<SkylinePlacement.BoxPosition> position = placement.bestPositionInBox(placed, boxLength, item);
            if (position.isEmpty()) {
                leftovers.add(item);
                continue;
            }
            SkylinePlacement.BoxPosition p = position.get();
            placed.add(new PlacedRectangle(item, boxIndex, p.x(), p.y(), p.orientation()));
        }
        return placed;
    }

    // ---------------------------------------------------------------------------------------
    // Plateau response
    // ---------------------------------------------------------------------------------------

    /**
     * Tries to empty one box outright by re-locating every occupant elsewhere. 
     * 
     * Closing a box is the true objective, and it is invisible to single-move search.
     * 
     * Working the sparsest boxes first, and keeping a
     * cursor across plateaus so a box that failed is not retried immediately.
     * Mirrors solution in GeometricNeighborhood drainABox.
     */
    private boolean drainOneBox(PackingSolution current) {
        List<Integer> emptiestFirst = new ArrayList<>(current.occupiedBoxIndices());
        if (emptiestFirst.size() < 2) {
            return false;
        }
        emptiestFirst.sort(Comparator.comparingLong(box -> usedAreaIn(current, box)));
        List<BoxSpace> fullestFirst = new ArrayList<>(emptiestFirst.size());
        for (int i = emptiestFirst.size() - 1; i >= 0; i--) {
            int boxIndex = emptiestFirst.get(i);
            fullestFirst.add(new BoxSpace(boxIndex, current.freeAreaIn(boxIndex)));
        }

        for (int attempt = 0; attempt < MAX_DRAIN_ATTEMPTS_PER_PLATEAU; attempt++) {
            if (drainCursor >= emptiestFirst.size()) {
                drainCursor = 0;
            }
            int sourceBox = emptiestFirst.get(drainCursor++);
            if (tryDrainBox(current, sourceBox, fullestFirst)) {
                drainCursor = 0;
                return true;
            }
        }
        return false;
    }

    private boolean tryDrainBox(PackingSolution current, int sourceBox, List<BoxSpace> destinations) {
        List<PlacedRectangle> occupants = new ArrayList<>(current.rectanglesIn(sourceBox));
        if (occupants.isEmpty()) {
            return false;
        }
        occupants.sort(Comparator.comparingInt((PlacedRectangle p) -> p.rectangle().area()).reversed());

        Map<Integer, List<PlacedRectangle>> staged = new LinkedHashMap<>();
        for (PlacedRectangle occupant : occupants) {
            if (!seatIn(current, occupant.rectangle(), sourceBox, destinations, staged, true)) {
                return false;   // this box cannot be emptied into the others as they stand
            }
        }
        for (PlacedRectangle occupant : occupants) {
            current.remove(occupant);
        }
        for (Map.Entry<Integer, List<PlacedRectangle>> entry : staged.entrySet()) {
            for (PlacedRectangle previous : new ArrayList<>(current.rectanglesIn(entry.getKey()))) {
                current.remove(previous);
            }
            for (PlacedRectangle placed : entry.getValue()) {
                current.place(placed);
            }
        }
        return true;
    }

    /**
     * How much should we reheat?
     * 
     * Gently at first, harder each time a reheat comes and goes without the solution improving. 
     * 
     * A strong reheat dissolves everything back to close to the fully-overlapping start and 
     * allows it re-form completely differently.
     */
    private double nextReheatStrength() {
        if (bestFeasibleBoxCount < boxCountAtLastReheat) {
            fruitlessReheats = 0;
        } else {
            fruitlessReheats = Math.min(fruitlessReheats + 1, 4);
        }
        boxCountAtLastReheat = bestFeasibleBoxCount;
        double escalated = 0.25 + 0.1875 * fruitlessReheats;

        // Never melt more than this run can afford to re-freeze! 
        double affordable = firstFeasibleNanos > 0
                ? schedule.nanosUntilReheatDeadline() / (2.0 * firstFeasibleNanos)
                : escalated;
        return Math.max(MIN_REHEAT_STRENGTH, Math.min(escalated, affordable));
    }

    /**
     * Guarantees a genuinely overlap-free result without discarding the search's own arrangement.
     * 
     * Only the rectangles still part of a violation are touched.
     * They are evicted and re-placed via SkylinePlcacement, largest first.
     *
     * Every re-placement is reported onIntermediateStep for tracking in GUI.
     */
    private PackingSolution repair(PackingSolution current, Consumer<PackingSolution> onIntermediateStep) {
        Set<Integer> violatingIds = overlappingRectangleIds(current);

        List<Rectangle> evicted = new ArrayList<>(violatingIds.size());
        for (int rectangleId : violatingIds) {
            PlacedRectangle removed = current.removeById(rectangleId);
            if (removed != null) {
                evicted.add(removed.rectangle());
            }
        }
        evicted.sort(Comparator.comparingInt(Rectangle::area).reversed());

        PackingSolution rebuilding = current;
        for (Rectangle rectangle : evicted) {
            rebuilding = placement.place(rebuilding, rectangle);
            onIntermediateStep.accept(rebuilding);
        }
        return rebuilding;
    }

    /** Every rectangle id party to at least one overlap violation - the exact set {@link #repair}
     *  needs to evict, and nothing more. */
    private static Set<Integer> overlappingRectangleIds(PackingSolution current) {
        Set<Integer> ids = new HashSet<>();
        for (var box : current.boxViews()) {
            List<PlacedRectangle> occupants = box.rectangles();
            for (int i = 0; i < occupants.size(); i++) {
                for (int j = i + 1; j < occupants.size(); j++) {
                    if (!occupants.get(i).isOpenlyDisjointFrom(occupants.get(j))) {
                        ids.add(occupants.get(i).rectangle().id());
                        ids.add(occupants.get(j).rectangle().id());
                    }
                }
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------------------------

    private void addIfImproving(PackingSolution current, PlacedRectangle from, PlacedRectangle to,
                                 double allowed, double penalty, List<Move<PackingSolution>> moves) {
        double delta = relocationDelta(current, from, to, allowed, penalty);
        if (delta < 0) {
            moves.add(new RelocationMove(from, to, delta));
        }
    }

    private PlacedRectangle fittingPlacement(Rectangle rectangle, int boxIndex, int x, int y,
                                              Orientation preferredOrientation) {
        Orientation opposite = preferredOrientation == Orientation.NORMAL ? Orientation.ROTATED : Orientation.NORMAL;
        for (Orientation orientation : List.of(preferredOrientation, opposite)) {
            PlacedRectangle candidate = new PlacedRectangle(rectangle, boxIndex, x, y, orientation);
            if (candidate.fitsWithinBox(boxLength)) {
                return candidate;
            }
        }
        return null;
    }

    /** Lowest box slot that currently holds nothing, or the next new slot if every one is occupied. */
    private static int firstEmptySlot(PackingSolution current) {
        for (int slot = 0; slot < current.boxSlotCount(); slot++) {
            if (current.isEmptyBox(slot)) {
                return slot;
            }
        }
        return current.boxSlotCount();
    }

    private static Deque<Integer> emptySlots(PackingSolution current) {
        Deque<Integer> slots = new ArrayDeque<>();
        for (int slot = 0; slot < current.boxSlotCount(); slot++) {
            if (current.isEmptyBox(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private long usedAreaIn(PackingSolution current, int boxIndex) {
        return boxArea - current.freeAreaIn(boxIndex);
    }

    private static long areaOf(List<PlacedRectangle> occupants) {
        long total = 0;
        for (PlacedRectangle placed : occupants) {
            total += placed.rectangle().area();
        }
        return total;
    }

    private static int maxTopOf(List<PlacedRectangle> occupants) {
        int top = 0;
        for (PlacedRectangle placed : occupants) {
            top = Math.max(top, placed.top());
        }
        return top;
    }

    private static int maxTopExcluding(List<PlacedRectangle> occupants, PlacedRectangle excluded) {
        int top = 0;
        for (PlacedRectangle placed : occupants) {
            if (!placed.equals(excluded)) {
                top = Math.max(top, placed.top());
            }
        }
        return top;
    }

    private static boolean samePlacements(List<PlacedRectangle> before, List<PlacedRectangle> after) {
        return new HashSet<>(before).equals(new HashSet<>(after));
    }

    private static List<PlacedRectangle> withoutSelf(List<PlacedRectangle> occupants, PlacedRectangle self) {
        List<PlacedRectangle> filtered = new ArrayList<>(occupants);
        filtered.remove(self);
        return filtered;
    }
}
