package optalgos.rectpack.model;

/**
 * An unplaced rectangle with integer side lengths, as produced by the instance generator.
 * {@code width}/{@code height} are its dimensions before any rotation is applied - see
 * {@link PlacedRectangle} for the rotated, positioned form.
 */
public record Rectangle(int id, int width, int height) {

    public Rectangle {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Rectangle side lengths must be positive: width=" + width + ", height=" + height);
        }
    }

    public int area() {
        return width * height;
    }
}
