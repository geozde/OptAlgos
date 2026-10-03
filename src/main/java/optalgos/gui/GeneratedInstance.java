package optalgos.gui;

import optalgos.rectpack.model.Rectangle;

import java.util.List;

/** {@code seed} is the generator seed this instance came from - shown to the viewer so the exact
 *  instance can be regenerated later by entering it again. */
record GeneratedInstance(List<Rectangle> rectangles, int boxLength, long seed) {
}
