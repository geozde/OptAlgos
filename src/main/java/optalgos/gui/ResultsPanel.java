package optalgos.gui;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.util.Locale;

/**
 * Side-by-side comparison of every algorithm x neighborhood/strategy combination on the CURRENT
 * instance: what box count each one reached, and how long it took. The spec's whole point in
 * requiring that both algorithms be applicable to the same instance arbitrarily often is that their
 * results can be compared - but comparing them across separate runs previously meant remembering the
 * previous run's status line, which the next run overwrites. One row per {@link AlgorithmChoice},
 * filled in as each is run, makes the comparison something the viewer reads rather than recalls.
 *
 * <p>Runtime sits next to box count because the spec's own testing harness logs exactly that pair
 * ("die erreichten Zielfunktionswerte sowie die aufgewendeten Laufzeiten ... um die Guete der
 * Algorithmen miteinander zu vergleichen") - a result reached in 0.1s and the same result reached in
 * 10s are not the same result.
 *
 * <p><b>Cleared whenever a new instance is generated</b> ({@link #clear()}): box counts are only
 * comparable within one instance, so carrying them across a regeneration would invite exactly the
 * confusion between old and new results the table exists to remove.
 *
 * <h2>Two marks, two questions</h2>
 *
 * <p>Exactly one row is drawn bold and in the strong accent colour: the BEST result so far - fewest
 * boxes, an exact tie broken by the shorter runtime, for the same reason runtime is shown at all.
 * Separately, the row whose run is the one currently drawn on the canvas carries a pale wash behind
 * it.
 *
 * <p>Those are two different questions ("which algorithm won?" and "which run am I looking at?") and
 * they used to share one visual channel, which is what made the highlight read as broken: the bold
 * blue row is the best one, NOT the one just run, so the first algorithm run on an instance keeps the
 * highlight for as long as nothing beats its box count - reported as the highlight staying on the
 * first algorithm even when another one is run. Giving the second question its own mark means neither
 * answer has to be inferred from the other, and the legend under the table says which mark is which.
 *
 * <h2>Why every update repaints the whole table</h2>
 *
 * <p>Both marks are properties of the table as a WHOLE, not of the row being written: writing one
 * row's box count can move the best to a different row, and marking a row as running wipes the count
 * it had, which can move the best away from it. So every mutation here fires {@code
 * fireTableDataChanged}. Firing only the touched row - which {@link #markRunning} used to do - leaves
 * every other row painted with a highlight decision that is no longer true, and those stale pixels
 * survive until something unrelated repaints them. Inside the sidebar's scroll pane that is
 * arbitrary: scrolling blits the existing pixels and repaints only the newly exposed strip, so fresh
 * and stale rows end up on screen together - several rows bold and blue at once while others stay
 * plain, exactly as reported.
 */
final class ResultsPanel extends SidebarSection {

    private static final String[] COLUMNS = {"Algorithm", "Boxes", "Time"};

    /** Sentinel box counts: no run of this algorithm on this instance yet, and one in progress. */
    private static final int NOT_RUN = -1;
    private static final int RUNNING = -2;

    private final AlgorithmChoice[] choices = AlgorithmChoice.values();
    /** Box count per row, or one of the sentinels above. */
    private final int[] boxCounts = new int[choices.length];
    private final double[] seconds = new double[choices.length];
    /** Row of the run the canvas is currently showing, or -1 while it is showing no run at all. */
    private int currentRow = -1;
    private final ResultsModel model = new ResultsModel();
    private final JTable table = new JTable(model);
    private final JLabel legend = new JLabel();

    ResultsPanel() {
        super(new BorderLayout(), "Step 3: Compare Results for this instance.");
        clear();

        table.setFillsViewportHeight(false);
        table.setRowHeight(table.getRowHeight() + 4);
        table.setShowVerticalLines(false);
        table.setGridColor(Palette.METER_TRACK);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);
        table.setDefaultRenderer(Object.class, new ResultRenderer());
        // Measured from the actual algorithm names rather than guessed: a guessed width truncated the
        // longest one to "Local Search - Rule-Based (Permutatio", which is the same "information
        // clipped out of view" failure as the one this panel's own section fixes above the table.
        table.getColumnModel().getColumn(0).setPreferredWidth(widestAlgorithmName() + 12);
        table.getColumnModel().getColumn(1).setPreferredWidth(52);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);

        // A mark nothing on screen explains is a mark the viewer has to guess at, and guessing wrong
        // here ("bold must mean the one I just ran") is precisely what made the table look broken.
        // Constrained to a fixed CSS width for the same reason AlgorithmControlPanel's status label is
        // - see STATUS_LABEL_WIDTH_PX there: an unconstrained <html> label sizes itself to fit its
        // text on one line, and that width demand propagates up into the sidebar's own.
        legend.setText("<html><div style='width:" + LEGEND_WIDTH_PX + "px'>"
                + "<b>Bold blue</b> = fewest boxes so far.<br>"
                + "Shaded row = the run shown on the right.</div></html>");
        legend.setForeground(Palette.TEXT_MUTED);
        legend.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 2));

        // No scroll pane: the row count is fixed at the number of algorithms, so the table is simply
        // laid out at its full height inside the sidebar, which scrolls as a whole. A nested scroll
        // pane here would only add a second, competing scrollbar over five rows.
        JTableHeader header = table.getTableHeader();
        add(header, BorderLayout.NORTH);
        add(table, BorderLayout.CENTER);
        add(legend, BorderLayout.SOUTH);
    }

    private static final int LEGEND_WIDTH_PX = 250;

    /** Wipes every result - called when a new instance is generated, since a box count from a
     *  different instance is not comparable with anything in this table. */
    void clear() {
        for (int i = 0; i < choices.length; i++) {
            boxCounts[i] = NOT_RUN;
            seconds[i] = 0;
        }
        currentRow = -1;
        model.fireTableDataChanged();
    }

    /** Marks one row as in progress, so a run that takes ten seconds isn't indistinguishable from
     *  one that was never started, and marks it as the run the canvas is now showing.
     *
     * <p>Any row still marked running from an earlier run goes back to "not run": starting a new run
     * abandons the previous one's worker (see {@code MainFrame.onRun}), so no result will ever arrive
     * for that row, and leaving it at "..." claims a run is still in progress for the rest of the
     * instance's life. */
    void markRunning(AlgorithmChoice choice) {
        int row = rowOf(choice);
        if (row < 0) {
            return;
        }
        for (int i = 0; i < choices.length; i++) {
            if (boxCounts[i] == RUNNING) {
                boxCounts[i] = NOT_RUN;
            }
        }
        boxCounts[row] = RUNNING;
        currentRow = row;
        model.fireTableDataChanged();   // wiping this row's count can move the best highlight elsewhere
    }

    /** Records a finished run's result, replacing any earlier result for the same algorithm - the
     *  spec allows running the same combination on the same instance repeatedly, and what the viewer
     *  wants to compare is what they most recently saw happen. */
    void record(AlgorithmChoice choice, int boxCount, double elapsedSeconds) {
        int row = rowOf(choice);
        if (row < 0) {
            return;
        }
        boxCounts[row] = boxCount;
        seconds[row] = elapsedSeconds;
        currentRow = row;
        model.fireTableDataChanged();   // the best-result highlight can move to a different row
    }

    private int widestAlgorithmName() {
        FontMetrics metrics = table.getFontMetrics(table.getFont());
        int widest = metrics.stringWidth(COLUMNS[0]);
        for (AlgorithmChoice choice : choices) {
            widest = Math.max(widest, metrics.stringWidth(choice.toString()));
        }
        return widest;
    }

    private int rowOf(AlgorithmChoice choice) {
        for (int i = 0; i < choices.length; i++) {
            if (choices[i] == choice) {
                return i;
            }
        }
        return -1;
    }

    /** The one row that gets the best-result highlight, or -1 while no run has finished on this
     *  instance yet. Fewest boxes wins; an exact tie goes to the faster run, so two algorithms
     *  reaching the same box count highlight one row rather than both - the table's answer to "which
     *  one should I use" is not "either of these", it is the one that got there sooner. */
    private int bestRow() {
        int best = -1;
        for (int i = 0; i < choices.length; i++) {
            if (boxCounts[i] < 0) {
                continue;
            }
            if (best < 0 || boxCounts[i] < boxCounts[best]
                    || (boxCounts[i] == boxCounts[best] && seconds[i] < seconds[best])) {
                best = i;
            }
        }
        return best;
    }

    private final class ResultsModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return choices.length;
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
        public boolean isCellEditable(int row, int column) {
            return false;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return switch (column) {
                case 0 -> choices[row].toString();
                case 1 -> boxCounts[row] == RUNNING ? "..." : boxCounts[row] < 0 ? "-" : String.valueOf(boxCounts[row]);
                default -> boxCounts[row] < 0 ? "" : String.format(Locale.ROOT, "%.1fs", seconds[row]);
            };
        }
    }

    private final class ResultRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable owner, Object value, boolean selected,
                                                        boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(owner, value, false, false, row, column);
            label.setHorizontalAlignment(column == 0 ? LEFT : RIGHT);
            boolean isBest = row == bestRow();
            // Not-yet-run rows are the ones the viewer has no reason to look at, so they recede into
            // pale grey; the best result is the one they came to the table for, so it alone is bold
            // and coloured. Everything in between stays plain black text.
            label.setForeground(boxCounts[row] == NOT_RUN ? Palette.TEXT_MUTED
                    : isBest ? Palette.HIGHLIGHT_OUTLINE : Palette.TEXT);
            label.setFont(label.getFont().deriveFont(isBest ? Font.BOLD : Font.PLAIN));
            // Set on EVERY row, not only the washed one: DefaultTableCellRenderer keeps the last
            // background it was handed (its unselectedBackground) and reuses it for the cells rendered
            // after it, so a wash applied to one row alone leaks onto the rest of the table.
            label.setBackground(row == currentRow ? Palette.ROW_WASH : owner.getBackground());
            return label;
        }
    }
}
