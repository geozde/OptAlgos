package optalgos.gui;

import optalgos.rectpack.model.PackingSolution;

/** Receives the LIVE (not yet snapshotted) solution after each algorithm step, together with a step
 *  number - implementations decide whether the step is worth turning into a displayed {@link Frame}
 *  (see {@link FrameHistory}) and, only if so, snapshot it themselves. Keeping the sampling decision
 *  before the snapshot avoids paying for a copy on every discarded step. */
@FunctionalInterface
interface FrameConsumer {
    void accept(PackingSolution liveState, long stepNumber);
}
