package optalgos.gui;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.event.TableModelEvent;
import javax.swing.event.TableModelListener;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the two marks the results table draws - which row is bold/blue (the best result) and which
 * row carries the wash (the run currently on the canvas) - and the repaint contract they depend on.
 *
 * <p>The regression these exist for: the highlight is a property of the whole table (whether a row is
 * best depends on every other row's count), but {@code markRunning} used to fire an update for its
 * own row only. Every other row then kept pixels painted from a highlight decision that was no longer
 * true, and inside the sidebar's scroll pane those stale pixels can survive arbitrarily long - which
 * is why the tests below assert on the EVENT as well as on what the renderer returns: rendering the
 * right thing is worth nothing if the rows that changed are never asked to render again.
 */
class ResultsPanelTest {

    private static final AlgorithmChoice A = AlgorithmChoice.GREEDY_DECREASING_AREA;
    private static final AlgorithmChoice B = AlgorithmChoice.GREEDY_BEST_FIT_ADAPTIVE;
    private static final AlgorithmChoice C = AlgorithmChoice.LOCAL_SEARCH_GEOMETRIC;

    @Test
    void highlightsTheBestResult_notTheOneRunMostRecently() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);
        panel.record(B, 109, 6.6);

        assertThat(boldRows(panel)).containsExactly(rowOf(A));
        assertThat(colorOf(panel, rowOf(A))).isEqualTo(Palette.HIGHLIGHT_OUTLINE);
        assertThat(colorOf(panel, rowOf(B))).isEqualTo(Palette.TEXT);
    }

    @Test
    void highlightMovesWhenAWorseResultIsBeaten() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 109, 6.6);
        assertThat(boldRows(panel)).containsExactly(rowOf(A));

        panel.record(B, 96, 10.0);
        assertThat(boldRows(panel)).containsExactly(rowOf(B));
    }

    @Test
    void tiedBoxCountsHighlightOneRow_theFasterOne() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);
        panel.record(B, 96, 6.6);

        assertThat(boldRows(panel)).containsExactly(rowOf(B));
    }

    @Test
    void notYetRunRowsRecedeIntoMutedGrey() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);

        assertThat(colorOf(panel, rowOf(B))).isEqualTo(Palette.TEXT_MUTED);
        assertThat(valueOf(panel, rowOf(B), 1)).isEqualTo("-");
    }

    @Test
    void washMarksTheRunCurrentlyOnScreen_andOnlyThatRow() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);
        panel.markRunning(B);

        assertThat(washedRows(panel)).containsExactly(rowOf(B));
        // The best result is still A's, so the two marks sit on different rows - the whole point of
        // giving "best" and "on screen" separate visual channels.
        assertThat(boldRows(panel)).containsExactly(rowOf(A));
    }

    @Test
    void markingARowRunningRepaintsEveryRow_becauseTheBestCanMoveOffIt() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);
        panel.record(B, 102, 6.6);

        List<TableModelEvent> events = listenForEvents(panel);
        panel.markRunning(A);   // wipes the current best, so B's row becomes the best one

        assertThat(events).anyMatch(ResultsPanelTest::coversEveryRow);
        assertThat(boldRows(panel)).containsExactly(rowOf(B));
    }

    @Test
    void aSupersededRunStopsClaimingToBeInProgress() {
        ResultsPanel panel = new ResultsPanel();
        panel.markRunning(A);
        assertThat(valueOf(panel, rowOf(A), 1)).isEqualTo("...");

        panel.markRunning(C);   // starting a new run abandons A's worker: no result will ever arrive

        assertThat(valueOf(panel, rowOf(A), 1)).isEqualTo("-");
        assertThat(valueOf(panel, rowOf(C), 1)).isEqualTo("...");
    }

    @Test
    void clearingWipesBothMarks() {
        ResultsPanel panel = new ResultsPanel();
        panel.record(A, 96, 10.0);
        panel.clear();

        assertThat(boldRows(panel)).isEmpty();
        assertThat(washedRows(panel)).isEmpty();
    }

    // --- helpers: read back exactly what the table would paint ------------------------------------

    private static int rowOf(AlgorithmChoice choice) {
        return List.of(AlgorithmChoice.values()).indexOf(choice);
    }

    private static JTable tableIn(ResultsPanel panel) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JTable found) {
                return found;
            }
        }
        throw new AssertionError("results panel has no table");
    }

    /** The component the renderer actually hands the table for one cell - the only place the two
     *  marks exist, since both are decided at render time rather than stored per row. */
    private static JLabel rendered(ResultsPanel panel, int row, int column) {
        JTable table = tableIn(panel);
        Component component = table.getCellRenderer(row, column)
                .getTableCellRendererComponent(table, table.getValueAt(row, column), false, false, row, column);
        return (JLabel) component;
    }

    private static List<Integer> boldRows(ResultsPanel panel) {
        List<Integer> bold = new ArrayList<>();
        for (int row = 0; row < tableIn(panel).getRowCount(); row++) {
            if (rendered(panel, row, 0).getFont().isBold()) {
                bold.add(row);
            }
        }
        return bold;
    }

    private static List<Integer> washedRows(ResultsPanel panel) {
        List<Integer> washed = new ArrayList<>();
        for (int row = 0; row < tableIn(panel).getRowCount(); row++) {
            if (Palette.ROW_WASH.equals(rendered(panel, row, 0).getBackground())) {
                washed.add(row);
            }
        }
        return washed;
    }

    private static java.awt.Color colorOf(ResultsPanel panel, int row) {
        return rendered(panel, row, 0).getForeground();
    }

    private static String valueOf(ResultsPanel panel, int row, int column) {
        return String.valueOf(tableIn(panel).getValueAt(row, column));
    }

    private static List<TableModelEvent> listenForEvents(ResultsPanel panel) {
        List<TableModelEvent> events = new ArrayList<>();
        TableModelListener listener = events::add;
        tableIn(panel).getModel().addTableModelListener(listener);
        return events;
    }

    /** True for an event that asks the table to repaint every row, however many rows it has - what
     *  {@code fireTableDataChanged} produces, and what a highlight that depends on all rows needs. */
    private static boolean coversEveryRow(TableModelEvent event) {
        return event.getFirstRow() == 0 && event.getLastRow() == Integer.MAX_VALUE;
    }
}
