# 0009. Step graph: top-down lanes with numbered blocks, no text inside

Status: Accepted (replaces the "structure map" of ADR 0008; the timeline and the rest of 0008 stand)
Date: 2026-10-09

## Context

ADR 0008 chose a compact lane map (columns = execution order, rows = parallel branches) plus a
timeline. Looking at it with real scenario shapes (40 steps in a row, 7 parallel branches) two
things were still wrong: it ran left to right while people read an execution top to bottom, and the
squares carried only a number, so a reader could not tell a job from a queue check or a 4 s step
from a 5 min step without hovering.

A git-history style graph with the step name written in every row was tried first. It reads well,
but it spends one text row per step, so the picture becomes as tall as the step list, and it
duplicates the table that already has the names.

## Decision

`StepGraphDiagram`: a top-to-bottom graph, git-history style, with **no text except the number in
each block**.

- A row is one depth of the DAG (longest path from the roots), so parallel steps share a row;
  parallel branches are vertical colored lanes that fork and merge with smooth bends. 7 branches of
  5 steps are about 7 rows and 7 lanes; 40 steps in a row are 40 short rows in one narrow lane.
- Shape is the step type: circle - queue creation, square - queue check, triangle pointing down - job.
  Fill is the status. Size is the duration, on a logarithmic scale so 4 s and 5 min both stay
  visible; the smallest block still holds a two-digit number. A step that did not run is the
  smallest block, grey, with dotted edges.
- The number is the step's number in the "Steps" table. Every block is a link (`#step-N`) to its
  row (`<tr id="step-N">`, highlighted with `:target`). Names, statuses and durations are in the
  table and in the hover tooltip.
- A legend and a short description sit above the diagram (what the shapes, colors, sizes and lanes
  mean). The diagram itself stays text-free.
- The timeline (`GanttDiagram`) stays: size says "longer or shorter", not "3 times longer", and
  only the timeline shows start times and overlaps.

## Consequences

- No label means no layout that depends on name length; width is the number of lanes.
- A block's size is an approximation of the duration; exact values are in the table and the timeline.
- Rows are by DAG depth, not by start time, so inside a lane the order is exact but between lanes
  "higher" does not strictly mean "started earlier" (the timeline shows that).
- An edge from a parent that is not the end of its lane to a child that is not the start of its
  lane is drawn along the parent's lane and can run behind later blocks of that lane. Rare in
  practice; the table has the exact order.
- The table numbering and the block numbers are one thing: change one, change the other.

## Amendment (2026-10-09): full width and a min-max size scale

Two parameters of the decision above changed after looking at the first result on a 7-branch
scenario; the decision itself (top-down, no text in blocks, number = link to the table row) stands.

- **Width.** Lanes are spread over the whole block (the graph is laid out for 1000 px and the SVG
  scales to the page) instead of being packed next to each other: lane pitch is 1000 / lanes, at
  most 320 px (two branches must not sit a page apart) and at least what the biggest block needs.
  The main lane (it holds the root and usually the join) goes to the middle, the others alternate
  to its sides, so forks and merges fan out symmetrically; with the main lane at the edge they
  were nearly horizontal lines. Rows get more vertical room when there are parallel branches,
  because a fork or merge has to bend inside the gap between two rows.
- **Size.** Linear between the quickest and the slowest executed step (radius 10 .. 30), not
  logarithmic. The point of the size is to show at a glance which step took the time; a log scale
  made a 5-minute step only slightly bigger than a 20-second one. Cost: with one very slow outlier
  the other blocks cluster near the smallest size. Equal durations get the middle size; a step
  that did not run is the smallest.

## Amendment 2 (2026-10-09): logarithmic between the minimum and the maximum

The linear min-max scale above had the weakness noted in its consequences, and it showed up on a
real-shaped chain: with one slow step among steps of 10-60 s, every block except the slow one was
drawn in nearly the same smallest size, although the times differ several times over. The scale is
now **logarithmic between the quickest and the slowest executed step**: the quickest is still the
smallest block (radius 10), the slowest the biggest (radius 30), and the steps in between are spread
by how many times slower than the quickest they are. A consequence: the size is relative to the
run (a 20-second step can be small in one run and big in another), and small real differences are
stretched when the whole run is uniform. Exact values stay in the tooltip, the table and the timeline.
