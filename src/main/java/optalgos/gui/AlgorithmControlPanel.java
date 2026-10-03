package optalgos.gui;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JSlider;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Lets the user pick an algorithm x neighborhood/strategy combination and run it, then control
 * playback of the resulting animation (play/pause/step, and scrubbing to any point). Per the spec, the
 * same instance can have any algorithm run against it repeatedly, in any combination - this panel
 * places no restriction on running again after a previous run finished, or before it does.
 *
 * <p>No playback-speed control: with the number of displayed frames itself capped (see
 * {@link FrameHistory}), a fixed, automatically-computed pace keeps a full replay comfortably watchable
 * regardless of instance size, without asking the viewer to tune anything - see {@code MainFrame}.
 */
final class AlgorithmControlPanel extends SidebarSection {

    private final JComboBox<AlgorithmChoice> algorithmChoice = new JComboBox<>(AlgorithmChoice.values());
    private final JButton runButton = new JButton("Run");
    private final JButton playPauseButton = new JButton("Pause");
    private final JButton stepButton = new JButton("Step");
    private final JSlider scrubBar = new JSlider(0, 1, 0);
    private final JLabel scrubLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(
            "<html><div style='width:250px'>Generate an instance to get started.</div></html>");

    private boolean suppressScrubEvents = false;

    AlgorithmControlPanel(Consumer<AlgorithmChoice> onRun, Runnable onPlayPauseToggle, Runnable onStep,
                           IntConsumer onScrub) {
        super(new GridBagLayout(), "Step 2: Select and run algorithm.");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 6, 3, 6);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridwidth = 2;

        gbc.gridx = 0;
        gbc.gridy = 0;
        add(algorithmChoice, gbc);

        gbc.gridy = 1;
        add(runButton, gbc);

        gbc.gridy = 2;
        gbc.gridwidth = 1;
        add(playPauseButton, gbc);
        gbc.gridx = 1;
        add(stepButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 2;
        add(scrubLabel, gbc);
        gbc.gridy = 4;
        add(scrubBar, gbc);

        gbc.gridy = 5;
        statusLabel.setVerticalAlignment(JLabel.TOP);
        add(statusLabel, gbc);

        scrubBar.setEnabled(false);

        runButton.addActionListener(e -> onRun.accept((AlgorithmChoice) algorithmChoice.getSelectedItem()));
        playPauseButton.addActionListener(e -> onPlayPauseToggle.run());
        stepButton.addActionListener(e -> onStep.run());
        scrubBar.addChangeListener(e -> {
            if (!suppressScrubEvents) {
                onScrub.accept(scrubBar.getValue());
            }
        });
    }

    AlgorithmChoice selectedAlgorithm() {
        return (AlgorithmChoice) algorithmChoice.getSelectedItem();
    }

    // Status text is dynamic and can get long ("Local Search - Geometric - running - 47 boxes so far
    // (step 8234) - 3.2s elapsed"). Left unconstrained, a plain <html> JLabel sizes itself to fit that
    // text on ONE line, which can demand far more width than the sidebar has - and since that demand
    // flows up through GridBagLayout into this panel's own preferred width, it can silently drag the
    // WHOLE sidebar wider than its containing scroll pane, clipping other controls out of view with no
    // way to scroll to them (a real bug this exact mechanism caused - see MainFrame's sidebar setup).
    // Constraining it to a fixed CSS width makes it wrap onto multiple lines instead of ever demanding
    // more horizontal space than the sidebar is meant to have.
    private static final int STATUS_LABEL_WIDTH_PX = 250;

    void setStatus(String text) {
        statusLabel.setText("<html><div style='width:" + STATUS_LABEL_WIDTH_PX + "px'>" + text + "</div></html>");
    }

    /** Blanks the status line, keeping its reserved height so the panel doesn't resize under the
     *  results table below it. Used when something else on screen already says what this line would. */
    void clearStatus() {
        setStatus("&nbsp;");
    }

    void setPlayPauseLabel(String text) {
        playPauseButton.setText(text);
    }

    /** Widens the scrubbable range to {@code [0, maxIndexInclusive]} - called as more frames arrive
     *  during a run, since {@link FrameHistory} only ever grows until the run finishes. Never narrows
     *  the range (the maximum a run reaches only ever goes up), so an in-progress drag isn't yanked
     *  out from under the user by a growing bound.
     *
     * <p>Guarded by the same {@code suppressScrubEvents} flag as {@link #setScrubPosition} - a real
     * bug this exact gap caused: {@code JSlider.setMaximum} can itself fire a {@code ChangeEvent} (the
     * current value gets re-clamped against the new bound internally), and this method is called on
     * every tick MainFrame's timer runs, live-follow included. Left unguarded, every single tick was
     * re-invoking {@code onScrub(0)}, which forces playback back to paused at frame 0 - live-follow
     * and replay auto-advance were both being silently overridden immediately after ever taking
     * effect, leaving the display stuck on frame 1 for an entire run regardless of what was actually
     * happening.
     */
    void setScrubRange(int maxIndexInclusive) {
        int bounded = Math.max(1, maxIndexInclusive);
        if (bounded > scrubBar.getMaximum()) {
            suppressScrubEvents = true;
            scrubBar.setMaximum(bounded);
            suppressScrubEvents = false;
        }
        scrubBar.setEnabled(bounded > 0);
    }

    /** Moves the bar to reflect the currently displayed frame without re-triggering {@code onScrub} -
     *  for programmatic updates (auto-follow during a run, auto-advance during replay), as opposed to
     *  the user dragging the bar themselves. */
    void setScrubPosition(int index) {
        suppressScrubEvents = true;
        scrubBar.setValue(index);
        suppressScrubEvents = false;
    }

    void setScrubText(String text) {
        scrubLabel.setText(text);
    }

    void resetScrub() {
        suppressScrubEvents = true;
        scrubBar.setMaximum(1);
        scrubBar.setValue(0);
        scrubBar.setEnabled(false);
        suppressScrubEvents = false;
        setScrubText(" ");
    }
}
