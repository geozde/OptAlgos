package optalgos.gui;

import optalgos.rectpack.model.Rectangle;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.util.List;
import java.util.Locale;

/**
 * What the main area shows BEFORE any algorithm has been run on the current instance: the generated
 * rectangles themselves, one row each, with the side lengths they were generated with. Until now that
 * area sat empty with a "choose an algorithm" placeholder, which left the instance - the thing the
 * user just configured and generated - as something they could only infer from the packing an
 * algorithm later produced from it.
 *
 * <p>The summary line above the table exists so the table is a description of the instance rather
 * than a data dump. Its last figure is the area lower bound, {@code ceil(total rectangle area / L^2)}:
 * no packing can ever use fewer boxes than that, since a box holds at most {@code L^2} of area, so it
 * is the reference point that makes every box count in {@link ResultsPanel} mean something. It is a
 * bound, not a target - it ignores geometry entirely and is generally not achievable.
 */
final class InstanceTablePanel extends JPanel {

    private static final String[] COLUMNS = {"#", "Width", "Height", "Area"};

    private final JLabel summary = new JLabel();
    private final RectangleModel model = new RectangleModel();
    private final JTable table = new JTable(model);

    InstanceTablePanel() {
        super(new BorderLayout());
        setBackground(Palette.BACKGROUND);

        summary.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        summary.setForeground(Palette.TEXT);
        summary.setFont(summary.getFont().deriveFont(Font.BOLD));

        table.setRowHeight(table.getRowHeight() + 2);
        // Vertical rules as well as horizontal: stretched across the full main area the columns are
        // wide, and a value sitting alone in a wide column needs a visible edge to be read against,
        // or it drifts out of line with its own heading.
        table.setShowGrid(true);
        table.setGridColor(Palette.METER_TRACK);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer rightAligned = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                                                            boolean focused, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
                label.setHorizontalAlignment(CENTER);
                // The id column is what a viewer scans down to find one rectangle among a thousand, so
                // it is the one column set in bold - the row is located by its number first and read
                // across only afterwards.
                label.setFont(label.getFont().deriveFont(column == 0 ? Font.BOLD : Font.PLAIN));
                return label;
            }
        };
        // Registered for Number, not Object: the model reports Integer columns (so the row sorter
        // orders numerically rather than lexicographically), and JTable resolves a renderer by
        // walking the column class up its superclasses - Integer finds Number's built-in renderer
        // long before it would reach Object's, so an Object registration here would never be used.
        table.setDefaultRenderer(Number.class, rightAligned);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder(0, 16, 16, 16));
        scroll.getVerticalScrollBar().setUnitIncrement(24);

        add(summary, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    void show(List<Rectangle> rectangles, int boxLength, long seed) {
        model.setRectangles(rectangles);
        long totalArea = 0;
        for (Rectangle rectangle : rectangles) {
            totalArea += rectangle.area();
        }
        long boxArea = (long) boxLength * boxLength;
        long lowerBound = (totalArea + boxArea - 1) / boxArea;
        // Short lines, not one long one: run together this could reach past the right edge of the
        // window and simply vanish off it. One line per question, in the order they get asked - what
        // to do next, what was generated, what that instance implies about the answer, and how to get
        // this exact instance back. The seed is printed without grouping separators so it can be
        // typed back into the seed field exactly as shown.
        summary.setText(String.format(Locale.ROOT,
                "<html>Choose an algorithm on the left and press Run.<br>"
                        + "%,d rectangles generated, box length = %d.<br>"
                        + "Total rectangle area = %,d. At least %,d boxes are needed.<br>"
                        + "Seed = %d (enter it in Step 1 to generate this instance again).</html>",
                rectangles.size(), boxLength, totalArea, lowerBound, seed));
        table.getRowSorter().setSortKeys(null);
        scrollToTop();
    }

    private void scrollToTop() {
        table.scrollRectToVisible(table.getCellRect(0, 0, true));
    }

    private static final class RectangleModel extends AbstractTableModel {

        private List<Rectangle> rectangles = List.of();

        void setRectangles(List<Rectangle> rectangles) {
            this.rectangles = List.copyOf(rectangles);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rectangles.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return Integer.class;   // so the row sorter orders numerically, not lexicographically
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int column) {
            Rectangle rectangle = rectangles.get(row);
            return switch (column) {
                case 0 -> rectangle.id();
                case 1 -> rectangle.width();
                case 2 -> rectangle.height();
                default -> rectangle.area();
            };
        }
    }
}
