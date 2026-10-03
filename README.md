# OptAlgos: Greedy and Local Search for Rectangle Packing

A Java implementation of two generic optimization algorithms, **greedy** and **local search**. Both are applied to a 2D rectangle packing problem. The project includes a Swing GUI that animates each run step by step, plus a headless benchmark harness for comparing the algorithms.

## The assignment

This is the programming assignment for the course *Optimierungsalgorithmen* (Optimization Algorithms), winter semester 2025/26.

### Problem

You are given a finite set of rectangles with integer side lengths and an integer box length `L`. No rectangle side is longer than `L`. Every rectangle has to be placed axis-parallel inside a square box of side `L`, and rectangles may be rotated by 90°. Any two rectangles must be *openly disjoint*: they may share corners and edge segments but no interior points. The objective is to **minimize the number of boxes used**.

### Requirements

- **Greedy and local search, implemented generically.** The algorithm code must not contain anything about the problem it solves, the neighborhood (local search) or the selection strategy (greedy). It has to work unchanged on a completely different, unforeseen problem. In the same way, the problem code must contain nothing specific to the algorithms, neighborhoods or strategies. The task sheet calls this requirement crucial for the program to be accepted.
- **Local search starts from deliberately bad initial solutions**, so that the improvements it makes are clearly visible.
- **An instance generator** that takes `L`, the number of rectangles and a lower and upper bound for each of the two side lengths. Side lengths are drawn uniformly and independently from these ranges.
- **Three local search neighborhoods:**
  - *geometry-based*: rectangles are moved directly, both within a box and from one box to another. The objective function may be modified so that a neighbor counts as better when it is heuristically closer to an improvement, for example when it further empties a box that already holds only a few rectangles.
  - *rule-based*: the search works on permutations of the rectangles. As in greedy, the rectangles are placed into boxes in permutation order by a placement rule, and neighbors are small modifications of the permutation.
  - *partial overlap allowed*: the geometry-based neighborhood, adapted so that rectangles may overlap up to a certain percentage. The overlap of two rectangles is their shared area divided by the larger of the two areas. The allowed percentage starts at 100 and decreases over time, violations are penalized heavily in the objective, and the final solution must be guaranteed overlap-free.
- **Two fundamentally different greedy selection strategies** (orders in which rectangles are taken), plus a rule for placing each rectangle openly disjoint from those already placed.
- **A GUI** where the user can repeatedly generate instances (choosing the number of rectangles, min/max side lengths and `L` for each one) and apply both algorithms, with any neighborhood or strategy, to the same instance as often as they like. Each state between iterations is shown as a clear, sufficiently large still image, and transitions should be easy to follow. The visualization automatically skips steps, so the viewer doesn't see minimal changes in rapid succession. The sheet also gives visualization guidelines: draw important elements last, bold and in strong colors, keep unimportant ones pale or grey, avoid red/green contrasts for colorblind viewers, and keep in mind that an examiner who doesn't know the GUI must be able to understand it.
- **A test environment, separate from the GUI**, parameterized by a sequence of tuples (number of instances, number of rectangles, two minimum side lengths, two maximum side lengths, box length). Every algorithm runs on every generated instance, and the objective values and CPU thread time are logged so the algorithms can be compared. It needs two modes: a small one that finishes in a few minutes and can be demonstrated live at the acceptance meeting (Testat), and a large one whose log is brought to that meeting as evidence of correctness.
- **Tuning.** The textbook versions of the algorithms are too slow, so deviating from them is allowed, for example by evaluating only a few promising neighbors instead of the whole neighborhood. The performance target: **every algorithm should reach a solution that can't be improved by eye within 10 seconds, on instances of up to 1000 rectangles.**

## What's implemented

### Generic algorithms (`optalgos.algorithm`)

- [`Greedy<C, S>`](src/main/java/optalgos/algorithm/greedy/Greedy.java) repeatedly asks a `SelectionStrategy` for the next candidate and adds it to the partial solution through a `SolutionBuilder`. The two interfaces separate *which* item comes next from *where* it goes.
- [`LocalSearch<S>`](src/main/java/optalgos/algorithm/localsearch/LocalSearch.java) asks a `Neighborhood` for candidate moves and applies one, chosen by first or best improvement. When no move improves the solution, it gives the neighborhood a chance to escape the plateau (`onPlateau`). It stops on a configurable `TerminationCriterion` (time budget, iteration limit or stagnation). Before returning, it calls the neighborhood's `finish` exactly once, so the neighborhood can guarantee properties such as feasibility.

[`ArchitectureRulesTest`](src/test/java/optalgos/algorithm/ArchitectureRulesTest.java) (ArchUnit) checks this separation. The build fails if anything in `optalgos.algorithm` imports problem-specific, GUI or benchmark code. As further proof that the algorithms are generic, the tests also run them on an unrelated toy problem: scheduling jobs on two machines ([`algorithm/toy`](src/test/java/optalgos/algorithm/toy)).

### Rectangle packing (`optalgos.rectpack`, `greedystrategy`, `neighborhood`)

| Component | Description |
|---|---|
| [`SkylinePlacement`](src/main/java/optalgos/rectpack/placement/SkylinePlacement.java) | Shared placement rule. It tracks a skyline profile per box and places each rectangle (in either orientation, in any open box) where it wastes the least area, opening a new box only if nothing fits. Both greedy strategies and the permutation neighborhood use it. |
| [`DecreasingAreaStrategy`](src/main/java/optalgos/greedystrategy/DecreasingAreaStrategy.java) | Greedy strategy: largest rectangles first (static order). |
| [`BestFitAdaptiveStrategy`](src/main/java/optalgos/greedystrategy/BestFitAdaptiveStrategy.java) | Greedy strategy: at each step, picks the remaining rectangle that wastes the least space in the current partial solution. For large inputs it samples the candidates. |
| [`GeometricNeighborhood`](src/main/java/optalgos/neighborhood/GeometricNeighborhood.java) | Relocate, rotate, swap and re-nest moves. On a plateau it drains nearly empty boxes, then falls back to ruin-and-recreate. It keeps the best solution found. |
| [`PermutationNeighborhood`](src/main/java/optalgos/neighborhood/PermutationNeighborhood.java) | Block moves and swaps on the placement order, which is decoded through the skyline rule. It prefers moving rectangles out of sparsely filled boxes. |
| [`RelaxedOverlapNeighborhood`](src/main/java/optalgos/neighborhood/RelaxedOverlapNeighborhood.java) | Starts from a compact, fully overlapping arrangement. An [`OverlapSchedule`](src/main/java/optalgos/neighborhood/OverlapSchedule.java) driven by the clock lowers the tolerated overlap from 100% to 0%, with occasional reheats. A repair step in `finish` guarantees an overlap-free result. |

Box count alone is too flat a landscape to search on, so the neighborhoods use their own internal search objectives that also reward emptying boxes. The real objective (number of boxes) is exposed separately through `Measurable`.

[`RectpackAlgorithms`](src/main/java/optalgos/rectpack/RectpackAlgorithms.java) wires all five algorithm and strategy/neighborhood combinations in one place.

### GUI (`optalgos.gui`)

- Generate random instances (count, min/max width and height, `L`) with input validation and a reproducible seed
- View the generated rectangles and the area lower bound `ceil(total area / L²)` before running anything
- Run any of the five combinations on the same instance, repeatedly and in any order
- Play back, pause, step through and scrub the run. Long runs are reduced to a bounded set of representative frames, and box-closing steps are always kept.
- Rectangles that changed since the last frame are highlighted. The palette is colorblind-safe.
- A results table compares box count and runtime of every algorithm on the current instance.

### Benchmark (`optalgos.benchmark`)

[`BenchmarkRunner`](src/main/java/optalgos/benchmark/BenchmarkRunner.java) takes a list of [`BenchmarkCase`](src/main/java/optalgos/benchmark/BenchmarkCase.java) tuples (number of instances, number of rectangles, min/max width, min/max height, `L`). It runs every combination on every generated instance, with a 10 s budget per local search run, and measures CPU thread time. Results are written as CSV to `build/benchmark-results/` with the columns:

```
caseIndex,numRectangles,boxLength,instanceIndex,algorithm,boxCount,areaLowerBound,cpuTimeSeconds
```

There are two presets:

- `quick` (default): up to 100 rectangles, takes a few minutes
- `thorough`: 50, 200, 500 and 1000 rectangles

## Building and running

Requires **JDK 21**. Gradle is provided through the wrapper. On Windows, use `gradlew.bat` instead of `./gradlew`.

```bash
./gradlew run                              # start the GUI
./gradlew test                             # unit, property and architecture tests
./gradlew benchmark                        # quick benchmark
./gradlew benchmark --args="thorough"      # full benchmark (up to n = 1000)
```

## Project structure

```
src/main/java/optalgos/
├── algorithm/        generic Greedy and LocalSearch, with no problem knowledge
├── rectpack/         problem model, instance generator, skyline placement, wiring
├── greedystrategy/   the two greedy selection strategies
├── neighborhood/     the three local search neighborhoods and their moves
├── gui/              Swing visualization
└── benchmark/        headless test environment and CSV logger
src/test/java/optalgos/   JUnit 5 / AssertJ / ArchUnit tests, including the toy problem
```
