package optalgos.gui;

import optalgos.rectpack.model.BoxOverlap;
import optalgos.rectpack.model.BoxView;
import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.PlacedRectangle;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Draws the current {@link PackingSolution} as a grid of boxes, each an L x L square with its
 * rectangles inside, scaled to fill the available width and wrapping into as many rows as needed
 * (the enclosing {@code JScrollPane} handles anything that doesn't fit vertically) - satisfying the
 * spec's "clearly and sufficiently large" requirement without shrinking boxes down to illegibility on
 * large instances. Only occupied boxes are drawn (see {@link #occupiedBoxes}) - an emptied box left
 * behind by a consolidating move is not part of the solution, and displaying it just adds clutter.
 *
 * <p>Follows the spec's general visualization guidance directly: unimportant (unchanged since the
 * last displayed frame) rectangles are drawn first, pale and thin-stroked; whatever changed is drawn
 * LAST, bold and in a strong accent color, so the viewer's eye goes straight to what happened without
 * the unimportant majority competing for attention. "Changed" is computed by diffing this frame
 * against the previously shown one by rectangle identity, not by trusting the algorithm to say what
 * moved - true regardless of how many internal steps a single displayed frame skipped over.
 *
 * <h2>Overlap annotation</h2>
 *
 * A box carrying overlap - only ever the relaxed-overlap neighborhood mid-run, per the spec's
 * "Ueberlappungen teilweise zulassen" variant - says so in three reinforcing ways, all keyed off
 * {@link BoxView#overlap()} (see {@link BoxOverlap} for why that measure and not the search
 * objective's own overlap sum):
 *
 * <ol>
 *   <li><b>Its caption states the figure</b> - the worst pair's overlap as a percentage, plus how many
 *       pairs overlap at all - in the strong overlap accent, while an overlap-free box keeps the plain
 *       muted caption it always had. Nothing is added to boxes that have no overlap, not even a "0%":
 *       a run that never overlaps anything (Greedy, the geometry-based neighborhood, and the tail of
 *       every relaxed-overlap run) looks exactly as it did before.</li>
 *   <li><b>A meter under the caption shows the same figure as a length</b>, so the severity of a box
 *       is comparable against its neighbours at a glance without reading five captions. A single
 *       monochromatic bar on a pale track, per the spec's instruction that gradations be
 *       monochromatic rather than multi-hued.</li>
 *   <li><b>The overlapping regions themselves are filled</b>, drawn last so nothing paints over them.
 *       The number answers "how much", the geometry answers "where" - and a shared area a few pixels
 *       across is otherwise very easy to miss, which is exactly the "kleine Details sofort erkennbar"
 *       case the spec calls out.</li>
 * </ol>
 *
 * <p>The overlap accent is deliberately NOT the highlight blue used for "this changed": the two
 * regularly coincide (a rectangle that just moved into an overlap is both), so they have to remain
 * separable - see {@link Palette#OVERLAP_ACCENT}.
 */
final class PackingCanvas extends JPanel {

    private static final int MIN_BOX_PIXELS = 90;
    // Caption line plus the overlap meter's own band beneath it. Reserved unconditionally, even
    // for a solution with no overlap anywhere: a band that appears and disappears would reflow the
    // whole grid between frames as overlap comes and goes, which is precisely the "Wechsel zwischen
    // zwei Standbildern einsichtsreich und nicht verwirrend" the spec asks us to avoid.
    private static final int LABEL_HEIGHT = 24;
    private static final int METER_HEIGHT = 4;
    private static final int BOX_GAP = 22;
    private static final int MARGIN = 16;

    private PackingSolution solution;
    private Map<Integer, PlacedRectangle> previousById = new HashMap<>();
    private Set<Integer> changedRectangleIds = new HashSet<>();
    private boolean computing = false;

    PackingCanvas() {
        setBackground(Palette.BACKGROUND);
    }

    void showFrame(PackingSolution next) {
        Map<Integer, PlacedRectangle> nextById = new HashMap<>();
        for (PlacedRectangle placed : next.allPlacedRectangles()) {
            nextById.put(placed.rectangle().id(), placed);
        }
        Set<Integer> changed = new HashSet<>();
        for (Map.Entry<Integer, PlacedRectangle> entry : nextById.entrySet()) {
            PlacedRectangle before = previousById.get(entry.getKey());
            if (before == null || !samePlacement(before, entry.getValue())) {
                changed.add(entry.getKey());
            }
        }

        this.solution = next;
        this.previousById = nextById;
        this.changedRectangleIds = changed;
        this.computing = false;
        updatePreferredSize();
        revalidate();
        repaint();
    }

    /** Reverts to a blank canvas the instant a new run starts - called from {@code MainFrame.onRun}.
     *  Without this, a run started right after a previous one finished left that PREVIOUS run's final
     *  boxes on screen for the entire "computing..." phase of the new one (nothing calls
     *  {@link #showFrame} again until the new run's first retained frame exists, which per {@code
     *  MainFrame}'s design doesn't happen until the run is done) - a real, reported bug: it read as the
     *  new run somehow not having started, or worse, as its result, when it was neither. */
    void showComputing() {
        this.solution = null;
        this.previousById = new HashMap<>();
        this.changedRectangleIds = new HashSet<>();
        this.computing = true;
        updatePreferredSize();
        revalidate();
        repaint();
    }

    int currentBoxCount() {
        return solution == null ? 0 : solution.boxCount();
    }

    private static boolean samePlacement(PlacedRectangle a, PlacedRectangle b) {
        return a.boxIndex() == b.boxIndex() && a.x() == b.x() && a.y() == b.y() && a.orientation() == b.orientation();
    }

    /** Only the boxes actually holding something - an emptied box left behind by a move (see
     *  {@link PackingSolution}'s sparse-index note) is a slot the algorithm has moved past, not part
     *  of the solution worth showing; displaying it would clutter the grid with squares the viewer has
     *  to actively ignore, working against the spec's "clearly and sufficiently large" intent. */
    private List<BoxView> occupiedBoxes() {
        List<BoxView> occupied = new ArrayList<>();
        for (BoxView box : solution.boxViews()) {
            if (!box.rectangles().isEmpty()) {
                occupied.add(box);
            }
        }
        return occupied;
    }

    private void updatePreferredSize() {
        if (solution == null || solution.boxCount() == 0) {
            setPreferredSize(null);
            return;
        }
        int boxesPerRow = boxesPerRow(solution.boxCount());
        int boxPixels = boxPixelsFor(boxesPerRow);
        int rows = (int) Math.ceil(solution.boxCount() / (double) boxesPerRow);
        int width = MARGIN * 2 + boxesPerRow * boxPixels + (boxesPerRow - 1) * BOX_GAP;
        int height = MARGIN * 2 + rows * (boxPixels + LABEL_HEIGHT) + Math.max(0, rows - 1) * BOX_GAP;
        setPreferredSize(new Dimension(width, height));
    }

    // A conservative, deliberately generous stand-in for a vertical scrollbar's width. Reserving this
    // UNCONDITIONALLY - whether or not one actually happens to be showing at the moment either caller
    // queries this - is what makes the two callers agree: updatePreferredSize() (called synchronously
    // from showFrame(), before Swing has processed the resulting layout change) and paintComponent()
    // (called later, once it has) would otherwise each see the viewport at a DIFFERENT width whenever
    // that layout pass toggles the scrollbar's presence in between the two calls - exactly what a
    // rapidly-updating live run does as box count changes from frame to frame. That mismatch showed up
    // as a real, reproducible bug: paintComponent() computing a boxesPerRow the actual (narrower, post-
    // scrollbar) viewport couldn't fit, silently pushing entire columns past the visible edge instead
    // of wrapping them - e.g. "Box 6" followed by "Box 9" with 7 and 8 rendered off-screen, not a
    // labeling error at all (the sequential counter itself was always correct) but a positioning one.
    private static final int SCROLLBAR_WIDTH_RESERVE = 24;

    private int viewportWidth() {
        int raw = getParent() != null ? getParent().getWidth() : 800;
        return Math.max(MIN_BOX_PIXELS, raw - SCROLLBAR_WIDTH_RESERVE);
    }

    private int boxesPerRow(int occupiedCount) {
        int available = Math.max(viewportWidth() - MARGIN * 2, MIN_BOX_PIXELS);
        int perRow = Math.max(1, (available + BOX_GAP) / (MIN_BOX_PIXELS + BOX_GAP));
        int slots = Math.max(1, occupiedCount);
        return Math.max(1, Math.min(perRow, slots));
    }

    private int boxPixelsFor(int boxesPerRow) {
        int available = viewportWidth() - MARGIN * 2 - (boxesPerRow - 1) * BOX_GAP;
        return Math.max(MIN_BOX_PIXELS, available / boxesPerRow);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (solution == null || solution.boxCount() == 0) {
            paintPlaceholder(g);
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        List<BoxView> occupied = occupiedBoxes();
        int boxesPerRow = boxesPerRow(occupied.size());
        int boxPixels = boxPixelsFor(boxesPerRow);
        double scale = boxPixels / (double) solution.boxLength();

        int index = 0;
        for (BoxView box : occupied) {
            int col = index % boxesPerRow;
            int row = index / boxesPerRow;
            int originX = MARGIN + col * (boxPixels + BOX_GAP);
            int originY = MARGIN + LABEL_HEIGHT + row * (boxPixels + LABEL_HEIGHT + BOX_GAP);
            // Displayed as a plain sequential position (1, 2, 3, ...), not the box's internal index -
            // once empty boxes are skipped, internal indices have gaps (3, 47, 812, ...) that would
            // read as confusing rather than informative to a viewer who has no reason to know or care
            // about the algorithm's internal box numbering.
            paintBox(g2, box, index + 1, originX, originY, boxPixels, scale);
            index++;
        }
        g2.dispose();
    }

    private void paintBox(Graphics2D g2, BoxView box, int displayNumber, int originX, int originY, int boxPixels, double scale) {
        BoxOverlap overlap = box.overlap();
        paintCaption(g2, box, overlap, displayNumber, originX, originY, boxPixels);

        g2.setColor(Palette.BOX_BORDER);
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawRect(originX, originY, boxPixels, boxPixels);

        for (PlacedRectangle placed : box.rectangles()) {
            if (!changedRectangleIds.contains(placed.rectangle().id())) {
                paintRectangle(g2, placed, originX, originY, boxPixels, scale, false);
            }
        }
        for (PlacedRectangle placed : box.rectangles()) {
            if (changedRectangleIds.contains(placed.rectangle().id())) {
                paintRectangle(g2, placed, originX, originY, boxPixels, scale, true);
            }
        }
        // Last, so neither rectangle in an overlapping pair can paint over the region they share -
        // the same "draw the important thing last" rule the changed/unchanged split above follows.
        if (overlap.isPresent()) {
            paintOverlapRegions(g2, box, originX, originY, boxPixels, scale);
        }
    }

    /** "Box 7 (9)" as before, plus - only when this box actually carries overlap - the figure in the
     *  overlap accent and a meter beneath. */
    private void paintCaption(Graphics2D g2, BoxView box, BoxOverlap overlap, int displayNumber,
                               int originX, int originY, int boxPixels) {
        Font baseFont = g2.getFont();
        String identity = "Box " + displayNumber + " (" + box.rectangles().size() + ")";
        int textY = originY - METER_HEIGHT - 8;

        g2.setColor(Palette.TEXT_MUTED);
        g2.drawString(identity, originX, textY);

        if (!overlap.isPresent()) {
            return;
        }
        int identityWidth = g2.getFontMetrics().stringWidth(identity);
        Font accentFont = baseFont.deriveFont(Font.BOLD);
        g2.setFont(accentFont);
        g2.setColor(Palette.OVERLAP_ACCENT);
        String clause = overlapClause(overlap, g2.getFontMetrics(), boxPixels - identityWidth);
        if (clause != null) {
            g2.drawString(clause, originX + identityWidth, textY);
        }
        g2.setFont(baseFont);

        // The meter carries the figure even when the box is too narrow for any of the text forms
        // above to fit, which is the whole reason it is a separate channel rather than decoration.
        int meterY = originY - METER_HEIGHT - 3;
        g2.setColor(Palette.METER_TRACK);
        g2.fillRect(originX, meterY, boxPixels, METER_HEIGHT);
        g2.setColor(Palette.OVERLAP_ACCENT);
        // At least 2px so a 1%-overlap box is still visibly non-zero rather than reading as clean.
        int filled = Math.max(2, (int) Math.round(boxPixels * Math.min(1.0, overlap.worstRatio())));
        g2.fillRect(originX, meterY, filled, METER_HEIGHT);
    }

    /** The longest form of the overlap figure that fits the width left over next to the box's own
     *  caption, or null if even the shortest doesn't - boxes get narrow on large instances, and a
     *  caption running past its box into the next one would be the "Ueberlappungen von Informationen"
     *  the spec asks us to avoid, on a label whose entire job is to report an overlap. */
    private static String overlapClause(BoxOverlap overlap, FontMetrics metrics, int availableWidth) {
        String percent = overlap.worstPercent() + "%";
        // Two forms, not a ladder of ever-shorter ones: an abbreviation like "83% / 10" fits more
        // boxes but leaves the second number unlabelled, and a number nobody can name is worse than
        // no number at all. Either both figures are stated in full, or only the percentage is - and
        // the filled overlap regions in the box below still show how widespread it is either way.
        String[] candidates = {
                "  " + percent + " overlap, " + overlap.pairCount() + (overlap.pairCount() == 1 ? " pair" : " pairs"),
                "  " + percent,
        };
        for (String candidate : candidates) {
            if (metrics.stringWidth(candidate) <= availableWidth) {
                return candidate;
            }
        }
        return null;
    }

    /** Fills the intersection of every overlapping pair in this box. Pairwise rather than as a merged
     *  region, so an area three rectangles all share is painted three times and comes out darker than
     *  one only two share - the denser the violation, the stronger it reads. */
    private void paintOverlapRegions(Graphics2D g2, BoxView box, int originX, int originY, int boxPixels,
                                      double scale) {
        List<PlacedRectangle> rectangles = box.rectangles();
        g2.setColor(Palette.OVERLAP_REGION_FILL);
        for (int i = 0; i < rectangles.size(); i++) {
            for (int j = i + 1; j < rectangles.size(); j++) {
                PlacedRectangle a = rectangles.get(i);
                PlacedRectangle b = rectangles.get(j);
                int left = Math.max(a.x(), b.x());
                int right = Math.min(a.right(), b.right());
                int bottom = Math.max(a.y(), b.y());
                int top = Math.min(a.top(), b.top());
                if (left >= right || bottom >= top) {
                    continue;
                }
                // Same edge-rounding discipline as paintRectangle below, and for the same reason: the
                // patch has to land exactly on the shared area, not a pixel past either rectangle.
                int leftPx = (int) Math.round(left * scale);
                int rightPx = (int) Math.round(right * scale);
                int bottomPx = (int) Math.round(bottom * scale);
                int topPx = (int) Math.round(top * scale);
                int w = Math.max(1, rightPx - leftPx);
                int h = Math.max(1, topPx - bottomPx);
                g2.fillRect(originX + leftPx, originY + boxPixels - topPx, w, h);
            }
        }
    }

    private void paintRectangle(Graphics2D g2, PlacedRectangle placed, int originX, int originY, int boxPixels,
                                 double scale, boolean highlighted) {
        // Each edge is rounded from the rectangle's own ABSOLUTE model coordinate, and its pixel
        // extent taken as the difference - never round(position) + round(size), which rounds two
        // values independently and so overshoots the far edge by a pixel whenever both land on
        // exactly .5 and Math.round carries both of them upward. That was a real, reported bug: at
        // L=30 in a 129px box (scale 4.3) a rectangle at x=15 of width 15 drew as 65..130 while the
        // box border sat at 129, so its outline appeared to lie OUTSIDE a box it was provably inside
        // (PackingSolution.place() rejects any placement that isn't, so an out-of-bounds rectangle
        // cannot reach this method at all - the overshoot was purely ours). It struck only odd box
        // sizes, and only rectangles flush against the right or top wall, which is why it surfaced
        // in a puzzling scattering of boxes rather than uniformly, reading as an algorithm fault.
        // Rounding the far edge directly makes containment exact instead: boxLength * scale IS
        // boxPixels by construction, so a rectangle touching a wall lands ON the border, never past.
        int leftPx = (int) Math.round(placed.x() * scale);
        int rightPx = (int) Math.round(placed.right() * scale);
        int bottomPx = (int) Math.round(placed.y() * scale);
        int topPx = (int) Math.round(placed.top() * scale);

        int w = Math.max(1, rightPx - leftPx);
        int h = Math.max(1, topPx - bottomPx);
        // Flip Y: PlacedRectangle's y grows upward (geometric convention), Swing's grows downward.
        // The 1px floors above keep a rectangle too thin to round up to a whole pixel visible at
        // all (a real case once L is large relative to the box's on-screen size); clamping to the
        // far edge lets such a sliver shift inward rather than become a new source of overshoot.
        int x = Math.min(originX + leftPx, originX + boxPixels - w);
        int y = Math.min(originY + boxPixels - topPx, originY + boxPixels - h);

        // Translucent fill (never the outline - see Palette.RECTANGLE_FILL_TRANSLUCENT) so two
        // rectangles genuinely overlapping visibly compose into a darker region instead of one
        // simply, invisibly, painting over the other.
        g2.setColor(highlighted ? Palette.HIGHLIGHT_FILL_TRANSLUCENT : Palette.RECTANGLE_FILL_TRANSLUCENT);
        g2.fillRect(x, y, w, h);
        g2.setColor(highlighted ? Palette.HIGHLIGHT_OUTLINE : Palette.RECTANGLE_OUTLINE);
        g2.setStroke(new BasicStroke(highlighted ? 2.5f : 1f));
        g2.drawRect(x, y, w, h);
    }

    private void paintPlaceholder(Graphics g) {
        g.setColor(Palette.TEXT_MUTED);
        String message = computing
                ? "Computing... the visualization will appear once the run finishes."
                : solution == null
                        ? "Generate an instance to get started."
                        : "Instance ready. Choose an algorithm on the left and press Run.";
        g.drawString(message, MARGIN, MARGIN + 16);
    }
}
