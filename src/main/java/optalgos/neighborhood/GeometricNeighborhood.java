package optalgos.neighborhood;

import optalgos.algorithm.localsearch.Move;
import optalgos.algorithm.localsearch.Neighborhood;
import optalgos.rectpack.model.Orientation;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Geometry-based neighborhood
 * 
 * A neighbor is produced by moving rectangles directly. 
 * Note that candidate generation is deliberately non-exhaustive!
 *
 * OBJECTIVE FUNCTION:
 * We not only value closing a box, but also slowly emptying it or better ordering it
 * For this, the objective adds two terms to the box count (as a value each STATE has)
 * boxPotential is the whole of it, and its Javadoc explains everything
 *
 * THREE MECHANISMS
 * 1. Sampled single moves (candidateMoves) 
 *      relocate, rotate, swap, re-nest one box 
 *      => cheap!
 * 2. Draining a box (drainOneBox).
 *      Used for plateaus
 *      Closing a box requires relocating ALL of its occupants but only the last relocation 
 *      reduces the box count! The earlier ones are not strictly improving.
 * 3. Ruin and recreate (perturb)
 *      If draining is exhausted too, try this
 *      Only step that is allowed to make things worse and can leave a true plateau.
 *      Made this safe because finish only return the best solution that we ever had.
 *
 */
public final class GeometricNeighborhood implements Neighborhood<PackingSolution> {

    /**
     * True objective
     * Cost of every box
     * 
     * Must be the dominant term!
     */
    private static final double BOX_COUNT_WEIGHT = 10.0;

    /**
     * Weight of the compaction term in boxPotential, normalized to [0,1].
     * Summed over all boxes this equals the total dead area trapped under the skylines
     * Minimizing it = "waste less space beneath what is already placed".
     */
    private static final double COMPACTION_WEIGHT = 1.0;

    private static final int MAX_SOURCE_RECTANGLES_PER_STEP = 15;

    // Widening this (tried 30) helps at n=500 (43 -> 37 boxes) but hurts at n=1000 (80 -> 91) 
    // Tested around and kept at best proven value.
    private static final int MAX_DESTINATION_BOXES_TRIED = 10;
    private static final int DIVERSITY_DESTINATION_BOXES = 3;
    private static final int EXHAUSTIVE_DESTINATION_INSTANCE_LIMIT = 200;
    private static final int MAX_CONSECUTIVE_EMPTY_SAMPLES = 30_000;
    /**
     * How many boxes one plateau step can try to drain before giving up.
     * Need this because draining is MUCH more expensive than a candidate move  
     */
    private static final int MAX_DRAIN_ATTEMPTS_PER_PLATEAU = 6;
    /**
     * How many plateau steps we try before trying perturbation and nuking it all.
     */
    private static final int PERTURB_AFTER_FRUITLESS_PLATEAUS = 25;
    /**
     * How much of the solution one perturb tears down, measured in rects. 
     * Small enough because we want to get out of a local optimum, not dissolve everything.
     */
    private static final int RUIN_RECTANGLES = 32;
    private static final int RUIN_POOL_FACTOR = 4;
    private static final int MAX_SWAP_DESTINATION_BOXES = 5;
    private static final int MAX_SWAP_CANDIDATES_PER_BOX = 3;
    private static final int MAX_SWAP_ELIGIBLE_OCCUPANCY = 15;

    private final List<Rectangle> rectangles;
    private final int boxLength;
    private final SkylinePlacement placement = new SkylinePlacement();
    private final Random random;
    private int consecutiveEmptySamples = 0;
    /** Where the next {@link #drainOneBox} scan resumes in the emptiest-first box order. */
    private int drainCursor = 0;
    /** Best solution seen this run, and its box count - see {@link #finish}. */
    private PackingSolution bestSeen;
    private int bestSeenBoxCount = Integer.MAX_VALUE;

    public GeometricNeighborhood(List<Rectangle> rectangles, int boxLength) {
        this(rectangles, boxLength, new Random());
    }

    public GeometricNeighborhood(List<Rectangle> rectangles, int boxLength, Random random) {
        this.rectangles = List.copyOf(rectangles);
        this.boxLength = boxLength;
        this.random = random;
    }

    // BAD INITIALIZATION
    // No further rectangle is considered for it, ever, even if one would still fit
    // once it holds at least this many rectangles AND has used at least MIN_FILL_FRACTION_PER_BOX area
    private static final int MIN_RECTANGLES_PER_BOX = 3;
    private static final double MIN_FILL_FRACTION_PER_BOX = 0.5;

    /**
     * Deliberately bad initial solution, but not maximally bad (one rect per box)
     *
     * Builds one box at a time. 
     * Rectangles are fed in the instance's own generation order, not sorted at all.
     * It places each rect into the current box via the same SkylinePlacement search the 
     * neighborhood's own moves use until that box holds at least #MIN_RECTANGLES_PER_BOX rects
     * AND has used at least MIN_FILL_FRACTION_PER_BOX of its area .
     * Then it is commited and the next box is started empty. 
     * It never reopens an earlier box once it's committed.
     */
    @Override
    public PackingSolution initialSolution() {
        PackingSolution solution = PackingSolution.empty(boxLength);
        long boxArea = (long) boxLength * boxLength;
        int currentBoxIndex = -1;
        int currentBoxOccupancy = 0;
        long currentBoxUsedArea = 0;

        for (Rectangle rectangle : rectangles) {
            boolean boxCommitted = currentBoxIndex == -1 || (currentBoxOccupancy >= MIN_RECTANGLES_PER_BOX
                    && currentBoxUsedArea >= boxArea * MIN_FILL_FRACTION_PER_BOX);
            Optional<SkylinePlacement.BoxPosition> position = boxCommitted
                    ? Optional.empty()
                    : placement.bestPositionInBox(solution.rectanglesIn(currentBoxIndex), boxLength, rectangle);
            if (position.isEmpty()) {
                currentBoxIndex = solution.boxSlotCount();
                currentBoxOccupancy = 0;
                currentBoxUsedArea = 0;
                // New, empty box: each rect is guaranteed to fit
                position = placement.bestPositionInBox(solution.rectanglesIn(currentBoxIndex), boxLength, rectangle);
            }
            SkylinePlacement.BoxPosition p = position.orElseThrow();
            solution.place(new PlacedRectangle(rectangle, currentBoxIndex, p.x(), p.y(), p.orientation()));
            currentBoxOccupancy++;
            currentBoxUsedArea += (long) rectangle.width() * rectangle.height();
        }
        return solution;
    }

    /** for a sampled subset of rectangles, generates candidate moves */
    @Override
    public List<? extends Move<PackingSolution>> candidateMoves(PackingSolution current) {
        rememberIfBest(current);
        List<Move<PackingSolution>> moves = new ArrayList<>();
        List<BoxSpace> spaciousBoxes = mostSpaciousBoxes(current);
        // One skyline profile per destination box
        // It is built the first time any source tries getting into it and reused afterward
        // Only one move gets applied at the very end.
        Map<Integer, SkylinePlacement.BoxProfile> destinationProfiles = new HashMap<>();

        // if multiple candidate moves share the same box, we don't need to renest them all
        // repackOffered tracks renested boxs
        Map<Integer, Boolean> repackOffered = new HashMap<>();
        for (PlacedRectangle source : sampleSourceRectangles(current)) {
            // try moving source rect somewhere else
            addRelocationMoves(current, source, spaciousBoxes, destinationProfiles, moves);
            // try moving within box
            addRotateInPlaceMove(current, source, moves);
            // try swapping with rect from another box
            addSwapMoves(current, source, spaciousBoxes, moves);
            // renest whole box => change skyline of box
            if (repackOffered.putIfAbsent(source.boxIndex(), Boolean.TRUE) == null) {
                // takes every occupant of a box, sorts them largest-first, and replaces them one at a time 
                addWithinBoxRepackMove(current, source.boxIndex(), moves);
            }
        }
        if (moves.stream().anyMatch(m -> m.scoreDelta() < 0)) {
            consecutiveEmptySamples = 0;
        }
        return moves;
    }

    /**
     * Called when no sampled candidate move improves on current. 
     * 
     * 1. First, try to close a box.
     * drainOneBox lifts every rectangle out of one nearly-empty box and re-places them among the others. 
     *
     * 2. Second, only if no box could be drained, try a different sample. 
     * Candidate generation is randomly sampled, not exhaustive.
     * Could just be an unlucky sample. Retry with a fresh random sample up to 
     * MAX_CONSECUTIVE_EMPTY_SAMPLES times before concluding that the search is really stuck.
     */
    @Override
    public Optional<PackingSolution> onPlateau(PackingSolution current, Consumer<PackingSolution> onIntermediateStep) {
        // 1. try draining a box
        if (drainOneBox(current)) {
            consecutiveEmptySamples = 0;
            return Optional.of(current);
        }

        // 2. try a different sample
        consecutiveEmptySamples++;
        if (consecutiveEmptySamples > MAX_CONSECUTIVE_EMPTY_SAMPLES) {
            return Optional.empty();
        }
        if (consecutiveEmptySamples % PERTURB_AFTER_FRUITLESS_PLATEAUS == 0) {
            perturb(current);
        }
        return Optional.of(current);
    }

    /**
     * To get out of a local optimum we must try steps that first make things worse.
     * This dissolves a few of the emptiest boxes and relocated their rectangles from scratch
     * 
     * 1. Pool the emptiest boxes. Sample some at random.
     * 2. Dissolve these boxes
     * 3. Try to relocate all to rects that were taken out best-fit into the fullest boxes that will
     * take them. 
     * 4. A rect that fits nowhere opens a new box rather than blocking the step. 
     * 
     * The result can be worse than what it replaced. 
     * Safe because finish returns the best solution the run ever held.
     */
    private void perturb(PackingSolution current) {
        List<Integer> emptiestFirst = new ArrayList<>(current.occupiedBoxIndices());
        if (emptiestFirst.size() < 2) {
            return;
        }
        emptiestFirst.sort(Comparator.comparingLong(box -> usedAreaIn(current, box)));

        // How many boxes RUIN_RECTANGLES works out to at this instance's occupancy, and hence how wide
        // a pool to draw them from. Both are derived per call, since occupancy changes as a run proceeds.
        double meanOccupancy = Math.max(1.0, rectangles.size() / (double) emptiestFirst.size());
        int expectedBoxes = (int) Math.ceil(RUIN_RECTANGLES / meanOccupancy);
        int poolSize = Math.min(emptiestFirst.size() - 1, Math.max(2, RUIN_POOL_FACTOR * expectedBoxes));
        List<Integer> pool = new ArrayList<>(emptiestFirst.subList(0, poolSize));
        Collections.shuffle(pool, random);

        List<Integer> dissolved = new ArrayList<>();
        List<PlacedRectangle> homeless = new ArrayList<>();
        for (int box : pool) {
            if (homeless.size() >= RUIN_RECTANGLES) {
                break;
            }
            dissolved.add(box);
            homeless.addAll(current.rectanglesIn(box));
        }
        if (dissolved.isEmpty()) {
            return;
        }
        for (PlacedRectangle placed : homeless) {
            current.remove(placed);
        }
        homeless.sort(Comparator.comparingInt((PlacedRectangle p) -> p.rectangle().area()).reversed());

        List<Integer> fullestFirst = new ArrayList<>(emptiestFirst);
        fullestFirst.removeAll(dissolved);
        Collections.reverse(fullestFirst);

        Map<Integer, List<PlacedRectangle>> staged = new HashMap<>();
        long boxArea = (long) boxLength * boxLength;
        List<PlacedRectangle> intoFreshBoxes = new ArrayList<>();
        for (PlacedRectangle placed : homeless) {
            if (!rehouse(current, placed.rectangle(), -1, fullestFirst, staged, boxArea)) {
                intoFreshBoxes.add(placed);
            }
        }
        for (Map.Entry<Integer, List<PlacedRectangle>> entry : staged.entrySet()) {
            commitBoxContents(current, entry.getKey(), entry.getValue());
        }
        reopenBoxesFor(current, intoFreshBoxes);
    }

    /**
     * Reopens a box for rects that {@link #perturb} could not fit into an existing box
     * It fills one reopened box at a time.
     */
    private void reopenBoxesFor(PackingSolution current, List<PlacedRectangle> homeless) {
        int slot = -1;
        List<PlacedRectangle> seated = List.of();
        for (PlacedRectangle placed : homeless) {
            if (slot < 0) {
                slot = nextEmptySlot(current, 0);
            }
            List<PlacedRectangle> extended = repackInto(slot, seated, placed.rectangle());
            if (extended == null) {
                commitBoxContents(current, slot, seated);
                slot = nextEmptySlot(current, slot + 1);
                extended = repackInto(slot, List.of(), placed.rectangle());
            }
            seated = extended;
        }
        if (slot >= 0) {
            commitBoxContents(current, slot, seated);
        }
    }

    /** First box open slot at or after from, or the index of a new slot if
     *  every existing one is occupied. */
    private static int nextEmptySlot(PackingSolution current, int from) {
        for (int slot = Math.max(0, from); slot < current.boxSlotCount(); slot++) {
            if (current.isEmptyBox(slot)) {
                return slot;
            }
        }
        return current.boxSlotCount();
    }

    /** Replaces everything in one box with parameter contents. 
     *  Removing first means a box being re-nested
     *  is momentarily empty, which is exactly what lets its rectangles land back in the same slot. */
    private static void commitBoxContents(PackingSolution current, int boxIndex, List<PlacedRectangle> contents) {
        for (PlacedRectangle previous : new ArrayList<>(current.rectanglesIn(boxIndex))) {
            current.remove(previous);
        }
        for (PlacedRectangle placed : contents) {
            current.place(placed);
        }
    }

    /**
     * Returns the best solution this run ever held
     */
    @Override
    public PackingSolution finish(PackingSolution current, Consumer<PackingSolution> onIntermediateStep) {
        rememberIfBest(current);
        if (bestSeen != null && bestSeen.boxCount() < current.boxCount()) {
            onIntermediateStep.accept(bestSeen);
            return bestSeen;
        }
        return current;
    }

    /** Snapshots current solution if it is best seen so far */
    private void rememberIfBest(PackingSolution current) {
        if (current.boxCount() < bestSeenBoxCount) {
            bestSeenBoxCount = current.boxCount();
            bestSeen = current.snapshot();
        }
    }

    /**
     * Tries to empty one box completely. 
     * 
     * It tries with emptiest boxes first and stops at the first success.
     *
     * Persistent scan position across calls with drainCursor.
     * Motivation: box that could not be drained a moment ago usually still cannot
     */
    private boolean drainOneBox(PackingSolution current) {
        List<Integer> emptiestFirst = new ArrayList<>(current.occupiedBoxIndices());
        if (emptiestFirst.size() < 2) {
            return false;
        }
        emptiestFirst.sort(Comparator.comparingLong(box -> usedAreaIn(current, box)));
        // Destinations in the opposite order: fullest first. 
        List<Integer> fullestFirst = new ArrayList<>(emptiestFirst);
        Collections.reverse(fullestFirst);

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

    /**
     * Helper for drainOneBox
     * 
     * Re-places every occupant of sourceBox into other boxes, largest first, and commits only if 
     * every single one is successfully replaced.
     *
     * Each rectangle gets two passes over the destinations: the ordinary skyline position first,
     * and only if no box anywhere will take it that way, a re-nesting of the destination around it
     *
     * Nothing is written to current until the whole plan is known to work. 
     */
    private boolean tryDrainBox(PackingSolution current, int sourceBox, List<Integer> destinations) {
        List<PlacedRectangle> occupants = new ArrayList<>(current.rectanglesIn(sourceBox));
        if (occupants.isEmpty()) {
            return false;
        }
        occupants.sort(Comparator.comparingInt((PlacedRectangle p) -> p.rectangle().area()).reversed());

        Map<Integer, List<PlacedRectangle>> staged = new HashMap<>();
        long boxArea = (long) boxLength * boxLength;
        for (PlacedRectangle occupant : occupants) {
            if (!rehouse(current, occupant.rectangle(), sourceBox, destinations, staged, boxArea)) {
                return false;
            }
        }

        for (PlacedRectangle occupant : occupants) {
            current.remove(occupant);
        }
        for (Map.Entry<Integer, List<PlacedRectangle>> entry : staged.entrySet()) {
            commitBoxContents(current, entry.getKey(), entry.getValue());
        }
        return true;
    }

    /** Helper for tryDrainBox
     * 
     *  find a destination for the rect and stage the result there,
     *  trying every destination without re-nesting before trying any destination with it. */
    private boolean rehouse(PackingSolution current, Rectangle item, int sourceBox, List<Integer> destinations,
                             Map<Integer, List<PlacedRectangle>> staged, long boxArea) {
        for (boolean allowRepack : new boolean[]{false, true}) {
            for (int destination : destinations) {
                if (destination == sourceBox) {
                    continue;
                }
                List<PlacedRectangle> destOccupants = staged.get(destination);
                if (destOccupants == null) {
                    destOccupants = current.rectanglesIn(destination);
                }
                if (boxArea - totalAreaOf(destOccupants) < item.area()) {
                    continue;   // cannot fit by area alone - skip the skyline search entirely
                }
                if (allowRepack) {
                    List<PlacedRectangle> repacked = repackInto(destination, destOccupants, item);
                    if (repacked != null) {
                        staged.put(destination, repacked);
                        return true;
                    }
                } else {
                    Optional<SkylinePlacement.BoxPosition> position =
                            placement.bestPositionInBox(destOccupants, boxLength, item);
                    if (position.isPresent()) {
                        SkylinePlacement.BoxPosition p = position.get();
                        List<PlacedRectangle> extended = new ArrayList<>(destOccupants);
                        extended.add(new PlacedRectangle(item, destination, p.x(), p.y(), p.orientation()));
                        staged.put(destination, extended);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static long totalAreaOf(List<PlacedRectangle> occupants) {
        long total = 0;
        for (PlacedRectangle placed : occupants) {
            total += placed.rectangle().area();
        }
        return total;
    }

    private record BoxSpace(int boxIndex, long freeArea) {
    }

    /**
     * Computes most spacious boxes. They most likely have room for another rect.
     * 
     * This is computed once per step and shared across every sampled source.
     * Free area ignores fragmentation.
     *
     * EXCLUDES already-empty box slots, focusing on partially-filled boxes.
     */
    private List<BoxSpace> mostSpaciousBoxes(PackingSolution current) {
        List<BoxSpace> spaces = new ArrayList<>(current.boxCount());
        for (int i : current.occupiedBoxIndices()) {
            spaces.add(new BoxSpace(i, current.freeAreaIn(i)));
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
     * Identifies rectangles worth trying to move this step.
     * Walk boxes in ascending-occupancy order - the RELATIVELY sparsest boxes first, whatever their 
     * absolute occupancy.
     *
     */
    private List<PlacedRectangle> sampleSourceRectangles(PackingSolution current) {
        // Ranking directly off occupiedBoxIndices() (occupied slots only, tracked incrementally - see
        // PackingSolution) rather than flattening every rectangle into one list and regrouping to 
        // reduce costs
        if (rectangles.size() <= MAX_SOURCE_RECTANGLES_PER_STEP) {
            return current.allPlacedRectangles();
        }
        List<Integer> boxesByOccupancyAscending = new ArrayList<>(current.occupiedBoxIndices());
        Collections.shuffle(boxesByOccupancyAscending, random);   // random order among ties
        boxesByOccupancyAscending.sort(Comparator.comparingInt(b -> current.rectanglesIn(b).size()));

        List<PlacedRectangle> chosen = new ArrayList<>(MAX_SOURCE_RECTANGLES_PER_STEP);
        for (int boxIndex : boxesByOccupancyAscending) {
            if (chosen.size() >= MAX_SOURCE_RECTANGLES_PER_STEP) {
                break;
            }
            for (PlacedRectangle r : current.rectanglesIn(boxIndex)) {
                if (chosen.size() >= MAX_SOURCE_RECTANGLES_PER_STEP) {
                    break;
                }
                chosen.add(r);
            }
        }
        return chosen;
    }

    /**
     * tries moving source into another box.
     * Loops over spaciousBoxes and for each one
     * 1. if it's source's own box, delegates to tryRelocateWithingOwnBox
     * 2. Otherwise, checks for fit into other box
     * 3. Or offer new box
     */
    private void addRelocationMoves(PackingSolution current, PlacedRectangle source,
                                     List<BoxSpace> spaciousBoxes, Map<Integer, SkylinePlacement.BoxProfile> destinationProfiles,
                                     List<Move<PackingSolution>> moves) {
        int oldBoxIndex = source.boxIndex();
        long sourceArea = source.rectangle().area();

        boolean ownBoxTried = false;
        for (BoxSpace space : spaciousBoxes) {
            // this is source's own original box!
            if (space.boxIndex() == oldBoxIndex) {
                ownBoxTried = true;
                tryRelocateWithinOwnBox(current, source, moves);
                continue;
            } // this is another box but source won't fit here
            else if (space.freeArea() < sourceArea) {
                continue;
            } // try fitting into new box if neither
            tryRelocateInto(current, source, space.boxIndex(), destinationProfiles, moves);
        }
        if (!ownBoxTried) {
            tryRelocateWithinOwnBox(current, source, moves);
        }

        // Also offer opening a brand new box, in case every existing box is a worse fit
        int newBoxIndex = current.boxSlotCount();
        if (newBoxIndex != oldBoxIndex) {
            PlacedRectangle intoNewBox = new PlacedRectangle(source.rectangle(), newBoxIndex, 0, 0, Orientation.NORMAL);
            moves.add(new RelocationMove(source, intoNewBox, relocationDelta(current, source, intoNewBox)));
        }
    }

    /** The common, cacheable case: destBoxIndex is some box OTHER than where source
     *  already is. So its skyline is the same regardless of which source is being tried this step. */
    private void tryRelocateInto(PackingSolution current, PlacedRectangle source, int destBoxIndex,
                                  Map<Integer, SkylinePlacement.BoxProfile> destinationProfiles,
                                  List<Move<PackingSolution>> moves) {
        SkylinePlacement.BoxProfile profile = destinationProfiles.computeIfAbsent(destBoxIndex,
                i -> placement.profileFor(current.rectanglesIn(i), boxLength));
        Optional<SkylinePlacement.BoxPosition> position = placement.bestPositionInBox(profile, source.rectangle());
        if (position.isEmpty()) {
            return;
        }
        SkylinePlacement.BoxPosition p = position.get();
        PlacedRectangle relocated = new PlacedRectangle(source.rectangle(), destBoxIndex, p.x(), p.y(), p.orientation());
        moves.add(new RelocationMove(source, relocated, relocationDelta(current, source, relocated)));
    }

    /**
     * re-seat source at the least-wasteful position its own box.
     */
    private void tryRelocateWithinOwnBox(PackingSolution current, PlacedRectangle source,
                                          List<Move<PackingSolution>> moves) {
        int boxIndex = source.boxIndex();
        List<PlacedRectangle> withoutSource = withoutSelf(current.rectanglesIn(boxIndex), source);
        Optional<SkylinePlacement.BoxPosition> position =
                placement.bestPositionInBox(withoutSource, boxLength, source.rectangle());
        if (position.isEmpty()) {
            return;
        }
        SkylinePlacement.BoxPosition p = position.get();
        if (p.x() == source.x() && p.y() == source.y() && p.orientation() == source.orientation()) {
            return;   // not actually a move
        }
        PlacedRectangle relocated = new PlacedRectangle(source.rectangle(), boxIndex, p.x(), p.y(), p.orientation());
        moves.add(new RelocationMove(source, relocated, relocationDelta(current, source, relocated)));
    }

    /**
     * Repositions all rects within a box by decreasing area.
     * This can change how a box's rectangles interlock with each other to change entire skyline.
     */
    private void addWithinBoxRepackMove(PackingSolution current, int boxIndex, List<Move<PackingSolution>> moves) {
        List<PlacedRectangle> occupants = current.rectanglesIn(boxIndex);
        if (occupants.size() < 2) {
            return;   // nothing to re-nest
        }
        List<PlacedRectangle> repacked = repackInto(boxIndex, occupants, null);
        if (repacked == null) {
            return;
        }
        long used = usedAreaIn(current, boxIndex);
        double delta = boxPotential(used, maxTopOf(repacked)) - boxPotential(used, maxTopOf(occupants));
        if (delta < 0) {
            moves.add(new RepackMove(occupants, repacked, delta));
        }
    }

    /**
     * Helper for changing skyline of a box without changing boxes. 
     * 
     * Re-places occupants plus extra, if given, into box boxIndex
     * Uses largest area first. Lets SkylinePlacement choose position and orientation.
     * 
     * Returns null if they do not all fit.
     */
    private List<PlacedRectangle> repackInto(int boxIndex, List<PlacedRectangle> occupants, Rectangle extra) {
        List<Rectangle> items = new ArrayList<>(occupants.size() + 1);
        for (PlacedRectangle placed : occupants) {
            items.add(placed.rectangle());
        }
        if (extra != null) {
            items.add(extra);
        }
        items.sort(Comparator.comparingInt(Rectangle::area).reversed());

        List<PlacedRectangle> repacked = new ArrayList<>(items.size());
        for (Rectangle item : items) {
            Optional<SkylinePlacement.BoxPosition> position = placement.bestPositionInBox(repacked, boxLength, item);
            if (position.isEmpty()) {
                return null;
            }
            SkylinePlacement.BoxPosition p = position.get();
            repacked.add(new PlacedRectangle(item, boxIndex, p.x(), p.y(), p.orientation()));
        }
        return repacked;
    }

    private static List<PlacedRectangle> withoutSelf(List<PlacedRectangle> occupants, PlacedRectangle self) {
        List<PlacedRectangle> filtered = new ArrayList<>(occupants);
        filtered.remove(self);
        return filtered;
    }

    /**
     * Our box objective function!
     *
     * The 3 terms per occupied box:
     * 1. BOX_COUNT_WEIGHT. Our true objective. 
     * 2. minus Square of the box's fill fraction
     *       The classic bin-packing fill term (Falkenauer). 
     *       For a fixed total rectangle area, this is largest when fills are as UNEQUAL as possible
     *       => this rewards draining a relatively empty box into fuller ones.
     * 3. plus COMPACTION_WEIGHT * box's normalised skyline height
     *       total dead area trapped beneath the skylines
     *       => rewards nesting rectangles tightly rather than stacking them
     *
     * Every move's scoreDelta is then just potential(after)-potential(before).
     */
    private double boxPotential(long usedArea, int maxTop) {
        if (usedArea <= 0) {
            return 0.0;
        }
        double fill = usedArea / (double) ((long) boxLength * boxLength);
        return BOX_COUNT_WEIGHT - fill * fill + COMPACTION_WEIGHT * (maxTop / (double) boxLength);
    }

    private long usedAreaIn(PackingSolution current, int boxIndex) {
        return (long) boxLength * boxLength - current.freeAreaIn(boxIndex);
    }

    private static int maxTopOf(List<PlacedRectangle> occupants) {
        int top = 0;
        for (PlacedRectangle p : occupants) {
            top = Math.max(top, p.top());
        }
        return top;
    }

    private static int maxTopExcluding(List<PlacedRectangle> occupants, PlacedRectangle excluded) {
        int top = 0;
        for (PlacedRectangle p : occupants) {
            if (!p.equals(excluded)) {
                top = Math.max(top, p.top());
            }
        }
        return top;
    }

    /** boxPotential difference for moving one rectangle from where it is to parameter to. */
    private double relocationDelta(PackingSolution current, PlacedRectangle from, PlacedRectangle to) {
        int sourceBox = from.boxIndex();
        int destBox = to.boxIndex();
        List<PlacedRectangle> sourceOccupants = current.rectanglesIn(sourceBox);
        long sourceUsed = usedAreaIn(current, sourceBox);
        long area = from.rectangle().area();

        if (sourceBox == destBox) {
            int before = maxTopOf(sourceOccupants);
            int after = Math.max(maxTopExcluding(sourceOccupants, from), to.top());
            return boxPotential(sourceUsed, after) - boxPotential(sourceUsed, before);
        }

        List<PlacedRectangle> destOccupants = current.rectanglesIn(destBox);
        long destUsed = usedAreaIn(current, destBox);
        int destTop = maxTopOf(destOccupants);
        double before = boxPotential(sourceUsed, maxTopOf(sourceOccupants)) + boxPotential(destUsed, destTop);
        double after = boxPotential(sourceUsed - area, maxTopExcluding(sourceOccupants, from))
                + boxPotential(destUsed + area, Math.max(destTop, to.top()));
        return after - before;
    }

    /** boxPotential difference for exchanging two rectangles between two different boxes.
     *  Each box loses one occupant and gains the other, so both boxes' used area AND 
     *  skyline height change. */
    private double swapDelta(PackingSolution current, PlacedRectangle aFrom, PlacedRectangle aTo,
                              PlacedRectangle bFrom, PlacedRectangle bTo) {
        int boxA = aFrom.boxIndex();
        int boxB = bFrom.boxIndex();
        List<PlacedRectangle> occupantsA = current.rectanglesIn(boxA);
        List<PlacedRectangle> occupantsB = current.rectanglesIn(boxB);
        long usedA = usedAreaIn(current, boxA);
        long usedB = usedAreaIn(current, boxB);
        long areaA = aFrom.rectangle().area();
        long areaB = bFrom.rectangle().area();

        double before = boxPotential(usedA, maxTopOf(occupantsA)) + boxPotential(usedB, maxTopOf(occupantsB));
        double after = boxPotential(usedA - areaA + areaB, Math.max(maxTopExcluding(occupantsA, aFrom), bTo.top()))
                + boxPotential(usedB - areaB + areaA, Math.max(maxTopExcluding(occupantsB, bFrom), aTo.top()));
        return after - before;
    }

    /**
     * Exchanges source with a rectangle from another box
     * 
     * This enables progress when neither rect can simply relocate into another box.
     */
    private void addSwapMoves(PackingSolution current, PlacedRectangle source, List<BoxSpace> spaciousBoxes,
                               List<Move<PackingSolution>> moves) {
        int sourceBoxIndex = source.boxIndex();
        List<PlacedRectangle> sourceBoxOthers = withoutSelf(current.rectanglesIn(sourceBoxIndex), source);
        if (sourceBoxOthers.size() > MAX_SWAP_ELIGIBLE_OCCUPANCY) {
            return;
        }
        int destinationsTried = 0;
        for (BoxSpace space : spaciousBoxes) {
            int destBoxIndex = space.boxIndex();
            if (destBoxIndex == sourceBoxIndex) {
                continue;
            }
            List<PlacedRectangle> destOccupants = current.rectanglesIn(destBoxIndex);
            if (destOccupants.size() > MAX_SWAP_ELIGIBLE_OCCUPANCY) {
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
                Optional<SkylinePlacement.BoxPosition> sourceIntoDest = placement.bestPositionInBox(destBoxOthers, boxLength, source.rectangle());
                if (sourceIntoDest.isEmpty()) {
                    continue;
                }
                Optional<SkylinePlacement.BoxPosition> candidateIntoSource = placement.bestPositionInBox(sourceBoxOthers, boxLength, candidate.rectangle());
                if (candidateIntoSource.isEmpty()) {
                    continue;
                }

                SkylinePlacement.BoxPosition sp = sourceIntoDest.get();
                SkylinePlacement.BoxPosition cp = candidateIntoSource.get();
                PlacedRectangle aTo = new PlacedRectangle(source.rectangle(), destBoxIndex, sp.x(), sp.y(), sp.orientation());
                PlacedRectangle bTo = new PlacedRectangle(candidate.rectangle(), sourceBoxIndex, cp.x(), cp.y(), cp.orientation());
                double delta = swapDelta(current, source, aTo, candidate, bTo);
                if (delta < 0) {
                    moves.add(new SwapMove(source, aTo, candidate, bTo, delta));
                }
            }
        }
    }

    private void addRotateInPlaceMove(PackingSolution current, PlacedRectangle source, List<Move<PackingSolution>> moves) {
        Orientation flipped = source.orientation() == Orientation.NORMAL ? Orientation.ROTATED : Orientation.NORMAL;
        PlacedRectangle rotated = new PlacedRectangle(source.rectangle(), source.boxIndex(), source.x(), source.y(), flipped);
        if (!rotated.fitsWithinBox(boxLength)) {
            return;
        }
        for (PlacedRectangle other : current.rectanglesIn(source.boxIndex())) {
            if (other.equals(source)) {
                continue;
            }
            if (!rotated.isOpenlyDisjointFrom(other)) {
                return;
            }
        }
        // Rotation in place changes neither box membership nor used area, so the potential reduces
        // to its compaction term alone - improving exactly when the box gets shorter. Skipped when it
        // would not register as an improving move at all (e.g. a square rectangle, where rotation is
        // a no-op).
        double delta = relocationDelta(current, source, rotated);
        if (delta < 0) {
            moves.add(new RelocationMove(source, rotated, delta));
        }
    }
}
