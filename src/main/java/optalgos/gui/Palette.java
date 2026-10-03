package optalgos.gui;

import java.awt.Color;

/**
 * A small, deliberately colorblind-safe palette (Okabe-Ito based, red/green-free, centered on the
 * spec's own yellow/blue suggestion) plus the neutral tones the spec's general visualization
 * guidance calls for: pale/thin for what's currently unimportant, strong/bold for what the viewer's
 * eye should land on.
 */
final class Palette {

    static final Color BACKGROUND = new Color(0xF5, 0xF5, 0xF5);
    static final Color RECTANGLE_FILL = new Color(0xD9, 0xD9, 0xD9);
    // Every rectangle's own boundary, and the only thing separating one rectangle from the next where
    // they sit flush against each other - which, in a well-packed box, is nearly everywhere. Was
    // 0x9E9E9E, which is only a 1.9:1 contrast ratio against RECTANGLE_FILL above; with a 1px stroke,
    // antialiasing on, and roughly 3 screen pixels per unit of L at a typical window size, a shared
    // edge at that contrast is close to invisible and a row of abutting rectangles reads as one grey
    // blob. That was survivable while the local-search neighborhoods left boxes around 74% full, since
    // real gaps did the separating instead; once GeometricNeighborhood started returning boxes over 90%
    // full the boxes became genuinely hard to count by eye - reported directly against the GUI as
    // rectangles appearing to have gone missing, on a solution that provably had every one of them.
    // 0x616161 is 4.4:1 against the fill: unambiguous at a 1px stroke, still clearly lighter than
    // BOX_BORDER below (so a box still reads as the enclosing thing rather than as one more line among
    // its contents), and still neutral and recessive next to HIGHLIGHT_OUTLINE, per the spec's guidance
    // that what changed should be what draws the eye.
    static final Color RECTANGLE_OUTLINE = new Color(0x61, 0x61, 0x61);
    static final Color BOX_BORDER = new Color(0x42, 0x42, 0x42);
    static final Color HIGHLIGHT_FILL = new Color(0x5A, 0xB4, 0xE0);
    static final Color HIGHLIGHT_OUTLINE = new Color(0x00, 0x72, 0xB2);
    // The one accent reserved for OVERLAP - never for "this changed", which is HIGHLIGHT's job. The
    // two have to be told apart at a glance and often appear in the same box (a rectangle that just
    // moved INTO an overlap is both), so they are the Okabe-Ito blue/vermillion pair: the standard
    // colourblind-safe opposition, and the spec's own advice that two hues chosen for strict
    // distinction should differ in brightness as well as hue rather than being a red/green split.
    static final Color OVERLAP_ACCENT = new Color(0xD5, 0x5E, 0x00);
    // Light enough to sit UNDER a rectangle's outline without swallowing it - the overlapping region
    // is drawn as a filled patch on top of both rectangles, so it has to read as "these two share
    // this area", not as a third opaque rectangle.
    static final Color OVERLAP_REGION_FILL = new Color(0xD5, 0x5E, 0x00, 90);
    // Unfilled part of the per-box overlap meter: a neutral pale track, per the spec's guidance that
    // what is not currently important stays light grey rather than competing for attention.
    static final Color METER_TRACK = new Color(0xDC, 0xDC, 0xDC);

    // Pale wash behind the one results-table row whose run is the one currently drawn on the canvas.
    // A background rather than a text colour deliberately: "which run am I looking at" and "which run
    // is best" are two different questions, and answering both with the same bold blue text is what
    // made that highlight unreadable - see ResultsPanel. Light enough that both TEXT and
    // HIGHLIGHT_OUTLINE still clear 4.5:1 against it, so a washed row is never the harder one to read.
    static final Color ROW_WASH = new Color(0xE8, 0xF2, 0xF9);

    static final Color TEXT = new Color(0x21, 0x21, 0x21);
    static final Color TEXT_MUTED = new Color(0x75, 0x75, 0x75);
    static final Color ERROR = new Color(0xB0, 0x00, 0x20);

    // Semi-transparent counterparts of the two fills above, used ONLY for the fill (never the
    // outline - each rectangle's own boundary stays crisp and fully opaque regardless) so that two
    // rectangles genuinely sharing space - the relaxed-overlap neighborhood's whole reason for
    // existing, mid-search - visibly compose into a darker, more saturated region wherever they
    // overlap, instead of one rectangle's opaque fill simply painting over the other with a clean
    // edge that looks like ordinary adjacent packing. A real gap this exact rendering caused before
    // the alpha existed: instrumenting a real run confirmed retained frames DID contain overlapping
    // rectangles, but every screenshot of them looked like a perfectly ordinary, non-overlapping
    // layout - the data was there, nothing on screen suggested it.
    private static final int OVERLAP_VISIBLE_ALPHA = 195;
    static final Color RECTANGLE_FILL_TRANSLUCENT = withAlpha(RECTANGLE_FILL, OVERLAP_VISIBLE_ALPHA);
    static final Color HIGHLIGHT_FILL_TRANSLUCENT = withAlpha(HIGHLIGHT_FILL, OVERLAP_VISIBLE_ALPHA);

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    private Palette() {
    }
}
