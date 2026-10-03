package optalgos.gui;

import optalgos.rectpack.model.PackingSolution;
import optalgos.rectpack.model.Rectangle;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Top-level window: instance configuration and algorithm controls in a sidebar, the packing
 * visualization filling the rest. Ties {@link InstanceConfigPanel}, {@link AlgorithmControlPanel},
 * {@link PackingCanvas}, {@link AlgorithmRunner}, and {@link FrameHistory} together, and owns the
 * {@link Timer} that drives the display - the only thing running on the EDT that touches an algorithm
 * run at all; everything about actually running one happens on {@link AlgorithmRunner}'s own
 * background thread.
 *
 * <p>The canvas and scrub bar are untouched while a run is in progress - only the status text updates
 * (elapsed time, live box count), on a short poll. Visualization starts once the run has genuinely
 * finished: {@link FrameHistory}'s retained frames (a fixed, already-known set of at most {@link
 * FrameHistory#MAX_FRAMES} by then) are fetched exactly once, the scrub bar is calibrated to that final
 * count, and replay begins from frame one at a fixed pace computed to make a full replay take a
 * comfortable, roughly constant amount of time regardless of how many frames there are (see {@link
 * #computeReplayDelayMs}) - no speed control for the viewer to tune. Scrubbing works over that same
 * fixed set once it exists.
 *
 * <p>The main area shows one of two things, swapped by a {@link CardLayout}: the generated instance
 * as a table of rectangles ({@link InstanceTablePanel}) from the moment it is generated until an
 * algorithm is run on it, and the packing visualization ({@link PackingCanvas}) from then on. The
 * sidebar's {@link ResultsPanel} accumulates one row per algorithm run against the current instance
 * and is wiped whenever a new instance is generated - box counts from different instances are not
 * comparable, so keeping them would be worse than showing nothing.
 *
 * <p>This is a deliberate simplification from an earlier version that instead followed the frame count
 * live while a run was still in progress - dropped for two reasons found only by actually watching a
 * run, not by reading the code: the displayed "frame N / M" total kept changing as more frames arrived
 * (M isn't meaningful until the run is done), and calibrating the scrub bar's range and position on
 * every single tick during that growth was fragile enough to actually desync the bar's visual thumb
 * position from its own reported value. Not visualizing anything until the final, fixed frame count is
 * known sidesteps both problems at once.
 */
final class MainFrame extends JFrame {

    private static final int COMPUTING_POLL_DELAY_MS = 150;

    /** CardLayout keys for the main area - see the class Javadoc. */
    private static final String INSTANCE_CARD = "instance";
    private static final String PACKING_CARD = "packing";

    private final PackingCanvas canvas = new PackingCanvas();
    private final InstanceTablePanel instanceTable = new InstanceTablePanel();
    private final ResultsPanel resultsPanel = new ResultsPanel();
    private final AlgorithmControlPanel algorithmPanel;
    private final CardLayout mainCards = new CardLayout();
    private final JPanel mainArea = new JPanel(mainCards);
    private final Timer playbackTimer;

    private List<Rectangle> currentRectangles;
    private int currentBoxLength;
    private long currentInstanceSeed;
    // Seed handed to the algorithm for the active run. Shown, never taken as input: re-running an
    // algorithm is meant to give a fresh run, but the viewer can still see which one they are looking at.
    private long runSeed;
    private AlgorithmRunner activeRunner;
    // Which algorithm the active run is actually running. Read instead of the combo box's CURRENT
    // selection, which the user is free to change mid-run (and must be, since the spec requires the
    // same instance be runnable with any combination arbitrarily often): the status line used to name
    // whatever was selected at the moment it refreshed rather than what was running, and the results
    // table would have attributed a finished run's box count to the wrong algorithm outright.
    private AlgorithmChoice runningChoice;
    private List<Frame> frames = List.of();
    private int displayIndex = 0;
    private boolean playing = true;
    private boolean replayReady = false;
    private long runStartNanos;
    private long finishedAtNanos = -1;

    MainFrame() {
        super("OptAlgos - Rectangle Packing");

        InstanceConfigPanel instancePanel = new InstanceConfigPanel(this::onInstanceGenerated);
        algorithmPanel = new AlgorithmControlPanel(this::onRun, this::onPlayPauseToggle, this::onStep, this::onScrub);
        // Each section caps its own height to its LIVE preferred height (see SidebarSection), which
        // is what leaves the leftover space to the glue below instead of stretching the sections.
        JPanel sidebar = new JPanel();
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.add(instancePanel);
        sidebar.add(algorithmPanel);
        sidebar.add(resultsPanel);
        sidebar.add(Box.createVerticalGlue());

        // Scrollable, not just a fixed-size panel: on a shorter window (or a taller sidebar, if
        // panels grow later) the alternative is silently clipping controls off the bottom - the
        // status label included, which is exactly the feedback a first-time viewer needs most.
        //
        // Width is a real bug's fix, not just a guess: this used to be a flat, hard-coded 300px with
        // horizontal scrolling disabled entirely - fragile on both sides of that number. Too narrow
        // for the content (which it turned out to be: the status label's text can run long enough to
        // demand more width than 300px, dragging the whole panel's preferred width past it - see
        // AlgorithmControlPanel's STATUS_LABEL_WIDTH_PX for the fix to THAT), and with no horizontal
        // scrollbar to fall back on, the excess was silently clipped out of view rather than
        // reachable any other way - controls a viewer would have no way to discover were even there.
        // Sizing from the sidebar's own measured preferred width (plus room for the vertical
        // scrollbar, which itself eats into the available width whenever it's showing) fixes the
        // immediate cause; keeping a horizontal scrollbar available as AS_NEEDED, rather than NEVER,
        // means any future content that needs more room than expected is still reachable instead of
        // silently gone.
        int scrollbarWidth = new JScrollBar(JScrollBar.VERTICAL).getPreferredSize().width;
        int sidebarWidth = Math.max(300, sidebar.getPreferredSize().width + scrollbarWidth);
        JScrollPane sidebarScroll = new JScrollPane(sidebar,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sidebarScroll.setBorder(null);
        sidebarScroll.getVerticalScrollBar().setUnitIncrement(24);
        sidebarScroll.setPreferredSize(new Dimension(sidebarWidth, 0));

        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.getVerticalScrollBar().setUnitIncrement(24);

        mainArea.add(instanceTable, INSTANCE_CARD);
        mainArea.add(canvasScroll, PACKING_CARD);
        mainCards.show(mainArea, PACKING_CARD);   // nothing generated yet: the canvas shows its own placeholder

        setLayout(new BorderLayout());
        add(sidebarScroll, BorderLayout.WEST);
        add(mainArea, BorderLayout.CENTER);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1280, 820);
        setLocationRelativeTo(null);

        playbackTimer = new Timer(COMPUTING_POLL_DELAY_MS, e -> onTick());
        playbackTimer.start();
    }

    private void onInstanceGenerated(GeneratedInstance instance) {
        activeRunner = null;   // any previous worker thread is left to finish on its own; nothing reads its history anymore
        runningChoice = null;
        frames = List.of();
        displayIndex = 0;
        playing = true;
        replayReady = false;
        algorithmPanel.setPlayPauseLabel("Pause");
        algorithmPanel.resetScrub();
        currentRectangles = instance.rectangles();
        currentBoxLength = instance.boxLength();
        currentInstanceSeed = instance.seed();
        // Every recorded box count belongs to the PREVIOUS instance and is meaningless against this
        // one - a smaller count on a smaller instance is not a better result. Wiping them is the
        // whole reason the table can be read as a comparison at all.
        resultsPanel.clear();
        canvas.showFrame(PackingSolution.empty(currentBoxLength));
        instanceTable.show(currentRectangles, currentBoxLength, currentInstanceSeed);
        mainCards.show(mainArea, INSTANCE_CARD);
        // Deliberately no status text here: the instance table now showing in the main area states
        // what was generated and what to do next, in more detail and in the place the viewer is
        // already looking. Repeating it in the sidebar was the same sentence twice on one screen.
        algorithmPanel.clearStatus();
    }

    private void onRun(AlgorithmChoice choice) {
        if (currentRectangles == null) {
            algorithmPanel.setStatus("Generate an instance first.");
            return;
        }
        activeRunner = new AlgorithmRunner();
        runningChoice = choice;
        runStartNanos = System.nanoTime();
        finishedAtNanos = -1;
        frames = List.of();
        displayIndex = 0;
        replayReady = false;
        algorithmPanel.resetScrub();
        resultsPanel.markRunning(choice);
        mainCards.show(mainArea, PACKING_CARD);
        // Without this, the PREVIOUS run's final boxes stayed on screen for this entire run's
        // "computing..." phase - nothing else calls canvas.showFrame(...) again until this run's own
        // first retained frame exists, which doesn't happen until it's done (see the class Javadoc) -
        // a real, reported bug: it read as though the new run hadn't started, or as its result,
        // depending on how long "computing..." took, when it was neither.
        canvas.showComputing();
        // Kept short (below one billion) for the same reason as the instance seed: it is read off the screen.
        runSeed = ThreadLocalRandom.current().nextLong(1_000_000_000L);
        activeRunner.start(choice, currentRectangles, currentBoxLength, runSeed);
        playing = true;
        algorithmPanel.setPlayPauseLabel("Pause");
        algorithmPanel.setStatus("Running " + choice + "...");
        playbackTimer.setDelay(COMPUTING_POLL_DELAY_MS);
    }

    private void onPlayPauseToggle() {
        if (!replayReady) {
            return;   // nothing to play/pause until the run has finished and replay has been set up
        }
        if (!playing && displayIndex >= frames.size() - 1) {
            displayIndex = 0;   // a finished replay that already ran to the end starts over on Play
            showCurrentFrame();
        }
        playing = !playing;
        algorithmPanel.setPlayPauseLabel(playing ? "Pause" : "Play");
    }

    private void onStep() {
        if (!replayReady) {
            return;   // nothing to step through until the run has finished
        }
        playing = false;
        algorithmPanel.setPlayPauseLabel("Play");
        if (displayIndex < frames.size() - 1) {
            displayIndex++;
            showCurrentFrame();
        }
        updateStatus();
    }

    private void onScrub(int index) {
        if (!replayReady) {
            return;
        }
        playing = false;
        algorithmPanel.setPlayPauseLabel("Play");
        displayIndex = Math.min(Math.max(index, 0), frames.size() - 1);
        showCurrentFrame();
        updateStatus();
    }

    private void onTick() {
        if (activeRunner == null) {
            return;
        }
        if (!activeRunner.isFinished()) {
            updateStatus();   // elapsed-time feedback only - the canvas and scrub bar stay untouched
            return;
        }
        if (!replayReady) {
            // The run just finished - this is the ONE point where frames are fetched and the scrub
            // bar calibrated, both exactly once, against the now-final, fixed frame count. See the
            // class Javadoc for why this replaced doing both continuously during the run.
            frames = activeRunner.frames();
            algorithmPanel.setScrubRange(Math.max(frames.size() - 1, 0));
            displayIndex = 0;
            replayReady = true;
            finishedAtNanos = System.nanoTime();
            playbackTimer.setDelay(computeReplayDelayMs(frames.size()));
            recordResult();
            showCurrentFrame();
        } else if (playing) {
            int lastIndex = frames.size() - 1;
            if (displayIndex < lastIndex) {
                displayIndex++;
                showCurrentFrame();
            } else {
                playing = false;
                algorithmPanel.setPlayPauseLabel("Play");
            }
        }
        updateStatus();
    }

    /** A full replay always takes roughly this long regardless of frame count (clamped per-frame, so
     *  a short run isn't stretched out and a nearly-100-frame one isn't rushed) - the "automatic"
     *  half of removing the speed control entirely: with the frame count itself capped, a fixed target
     *  duration is all a per-frame delay needs to be computed from. */
    private static int computeReplayDelayMs(int frameCount) {
        int targetTotalMs = 8000;
        int delay = frameCount <= 1 ? targetTotalMs : targetTotalMs / frameCount;
        return Math.max(80, Math.min(400, delay));
    }

    private void showCurrentFrame() {
        if (frames.isEmpty() || displayIndex >= frames.size()) {
            return;
        }
        canvas.showFrame(frames.get(displayIndex).snapshot());
        algorithmPanel.setScrubPosition(displayIndex);
    }

    private void updateStatus() {
        if (activeRunner == null) {
            return;   // whatever was last explicitly set (instance-generated message, etc.) stands
        }
        // replayReady is only ever set once activeRunner.isFinished() - see onTick() - so reaching
        // this method with it still false always means the run is still computing; the two branches
        // below correspond exactly to "still running" and "finished", never both or neither.
        if (!replayReady) {
            double elapsedSeconds = (System.nanoTime() - runStartNanos) / 1e9;
            algorithmPanel.setStatus(String.format(Locale.ROOT, "%s - computing... - %d boxes so far (step %d) - %.1fs elapsed%s",
                    runningChoice, activeRunner.liveBoxCount(), activeRunner.liveStepNumber(), elapsedSeconds, seedLine()));
            return;
        }
        double elapsedSeconds = (finishedAtNanos - runStartNanos) / 1e9;
        Frame shown = frames.get(displayIndex);
        int boxCount = shown.snapshot().boxCount();
        String suffix = statusSuffixFor(shown);
        algorithmPanel.setStatus(String.format(Locale.ROOT, "%s - finished in %.1fs - %d boxes - viewing frame %d / %d%s%s",
                runningChoice, elapsedSeconds, boxCount, displayIndex + 1, frames.size(), suffix, seedLine()));
        algorithmPanel.setScrubText("Frame " + (displayIndex + 1) + " / " + frames.size());
    }

    /** Both seeds on a line of their own, so they stay readable once the instance table is no longer
     *  showing - a screenshot of a finished run then records which instance it was. */
    private String seedLine() {
        return String.format(Locale.ROOT, "<br>Instance seed %d, run seed %d", currentInstanceSeed, runSeed);
    }

    /** Two independent things worth calling out about a frame, per {@link Frame}'s flags:
     *
     * <p>{@code isFinal} - flagging the run's true final frame specifically (rather than just "last in
     * the list", which every OTHER frame also reaches once scrubbed to the end) matters because it can
     * legitimately look like a bigger, more abrupt change than any step before it: a neighborhood's
     * finish() hook can act on the state search itself never reached.
     *
     * <p>{@code isRepairStep} - a state that came from exactly that kind of hook (relaxed-overlap's
     * from-scratch repair rebuild, currently the only neighborhood that uses it) rather than an
     * accepted search move. Without this label, a viewer scrubbing through a repair rebuild - box
     * count climbing back up from a fresh, empty start - has no way to tell that apart from an actual
     * regression in the search's own progress; with it, the frames read as what they are: a
     * reconstruction happening in the open, not a mysterious jump or a step backwards. */
    private static String statusSuffixFor(Frame shown) {
        if (shown.isFinal() && shown.isRepairStep()) {
            return " (final result, after rebuilding to remove overlap)";
        }
        if (shown.isFinal()) {
            return " (final result)";
        }
        if (shown.isRepairStep()) {
            return " (rebuilding from scratch to remove overlap...)";
        }
        return "";
    }

    /** Files the finished run's objective value in the comparison table. Takes the box count from the
     *  LAST frame rather than whichever one is currently displayed: {@link FrameHistory#recordFinal}
     *  guarantees that entry is the algorithm's actual returned result, whereas the displayed frame is
     *  wherever the viewer happens to have scrubbed to. */
    private void recordResult() {
        if (frames.isEmpty() || runningChoice == null) {
            return;
        }
        int finalBoxCount = frames.get(frames.size() - 1).snapshot().boxCount();
        resultsPanel.record(runningChoice, finalBoxCount, (finishedAtNanos - runStartNanos) / 1e9);
    }
}
