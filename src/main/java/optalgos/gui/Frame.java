package optalgos.gui;

import optalgos.rectpack.model.PackingSolution;

/** One display-ready snapshot in an algorithm run: an already-{@code snapshot()}'d box placement at
 *  some point in the run (never the live, still-mutating solution - see {@link AlgorithmRunner}),
 *  plus enough metadata for the status bar to describe it. {@code isRepairStep} marks a state that
 *  came from a neighborhood's {@link optalgos.algorithm.localsearch.Neighborhood#finish}/{@link
 *  optalgos.algorithm.localsearch.Neighborhood#onPlateau} intermediate-step reporting (e.g. relaxed-
 *  overlap's from-scratch repair rebuild) rather than an accepted search move - see {@code
 *  AlgorithmRunner} and {@code MainFrame}'s status labeling. */
record Frame(PackingSolution snapshot, long stepNumber, boolean isFinal, boolean isRepairStep) {
}
