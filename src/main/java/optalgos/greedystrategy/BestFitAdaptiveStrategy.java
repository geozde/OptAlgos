package optalgos.greedystrategy;

import optalgos.algorithm.greedy.SelectionStrategy;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;
import optalgos.rectpack.placement.SkylinePlacement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * implementation of SelectionStrategy to select which rect to place next
 *
 * idea "Of the remaining rects, which one fits best into what I've built so far?"
 * 
 * At each step asking 
 * 1. gets a sample of the remaining rectangles
 * 2. determines how much space it would waste if placed in current partial solution (adaptive greedy!)
 * 3. keeps candidate with lowest cost
 * 
 * note: It does not look at ALL rects if remaining > 40. Instead it samples some 40 rects
 */
public final class BestFitAdaptiveStrategy implements SelectionStrategy<Rectangle, PackingSolution> {

    private static final int MAX_CANDIDATES_CONSIDERED = 40;

    private final SkylinePlacement placement;
    private final Random random;

    // constructors
    public BestFitAdaptiveStrategy(SkylinePlacement placement) {
        this(placement, new Random());
    }

    // make it deterministic for tests
    public BestFitAdaptiveStrategy(SkylinePlacement placement, Random random) {
        this.placement = placement;
        this.random = random;
    }

    /**
     * 1. get a sample
     * 2. find one that wastes least space if placed into current partial solution
     * 3. return it
     */
    @Override
    public Rectangle selectNext(PackingSolution partial, List<Rectangle> remaining) {
        List<Rectangle> pool = sample(remaining);
        Rectangle best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (Rectangle candidate : pool) {
            double cost = placement.placementCost(partial, candidate);
            if (cost < bestCost) {
                bestCost = cost;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * samples some 40 candidates
     */
    private List<Rectangle> sample(List<Rectangle> remaining) {
        if (remaining.size() <= MAX_CANDIDATES_CONSIDERED) {
            return remaining;
        }
        List<Rectangle> shuffled = new ArrayList<>(remaining);
        Collections.shuffle(shuffled, random);
        return shuffled.subList(0, MAX_CANDIDATES_CONSIDERED);
    }
}
