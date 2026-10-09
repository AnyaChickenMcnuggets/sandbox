# 0008. Report diagrams: a lane map and a timeline instead of Sankey

Status: Accepted (replaces the Sankey choice in ADR 0007; the structure map is replaced by ADR 0009, the timeline and the reasoning stand)
Date: 2026-10-09

## Context

ADR 0007 drew the run as a Sankey diagram. Two problems showed up.

1. **It reads badly.** A Sankey shows a quantity that is conserved along the flow. Here the
   "quantity" is time, which is not conserved: a step does not hand its duration to its children.
   Bands had to be invented (parent time split between children), and fan-out / fan-in made the
   picture hard to interpret.
2. **It does not scale.** Real scenarios are not the 5-step examples: one has 40 steps in a
   sequence, another has 7 parallel branches. A diagram that grows by a whole box or a labelled
   band per step either becomes 11 000 px wide (40 columns of boxes in a left-to-right flowchart)
   or unreadable (7 interleaved bands).

Alternatives looked at: a left-to-right box-and-arrow flowchart (clean for 5-8 steps, but one
column of 210 px per step in a chain), a top-to-bottom flowchart (the same problem rotated, plus
7 branches side by side), a treemap or bars of duration share (parallel branches make the shares
misleading), a JS graph library (needs the CDN or a vendored bundle, and does not print - see 0007).

## Decision

Two diagrams, both server-side inline SVG, each answering one question and each growing only
along an axis a page can afford:

- **Structure map (`LaneMapDiagram`)**: one small numbered square per step (the number is the step's
  number in the table), columns are the execution order, rows are parallel branches ("lanes"), thin
  curves are the edges. A chain of 40 steps is one row 40 squares wide, squeezed to fit about 1000
  px; 7 branches are 7 rows. Names are in tooltips and in the table - the map shows shape and status
  only. Lanes come from a greedy chain decomposition (a step continues the lane of the nearest parent
  nobody continued yet, otherwise it opens a new lane) and are never reused, so a branch keeps its
  row from fork to join.
- **Timeline (`GanttDiagram`)**: one row per step on a shared time axis. It grows downward (a page
  scrolls down for free) and its rows get denser above 20 steps. It shows parallelism, where the
  time went and the gaps.

The detailed per-step text stays in the table below them.

## Consequences

- Neither diagram needs layout work per scenario shape: size is `columns x lanes` for the map and
  `steps` rows for the timeline.
- The map does not show step names. This is deliberate; names would make width depend on label
  length again. The number links the map, the timeline and the table.
- Very wide fan-outs (dozens of lanes) make the map tall, which is acceptable; very long chains are
  capped by a minimum square size (16 px), beyond which the page scales the SVG down.
- Edges that skip columns are drawn as curves behind the squares; in dense fan-in they can overlap.
  Acceptable for an overview - the table has the exact order.
- `SankeyDiagram` and the box-and-arrow prototype were deleted. Do not bring them back for "small"
  scenarios: two diagram styles in one report is more to maintain than it is worth.
