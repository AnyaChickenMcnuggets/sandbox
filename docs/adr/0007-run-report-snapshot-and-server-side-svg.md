# 0007. Run report: a snapshot built at completion, rendered as server-side HTML + SVG

Status: Accepted (the Sankey diagram of decision 3 is replaced by ADR 0008; server-side SVG without scripts still stands)
Date: 2026-10-08

## Context

Users want a report when a test run ends: who ran what, what each step did, which checks
(`QUEUE_CHECK`) passed, and a diagram of the step sequence. A person reads it, saves it as PDF, and
optionally gets a mail about it.

Three choices had a real alternative.

1. **Where the data comes from.** The report could be computed on every request from live data
   (`scenario_run`, `step_run`, the orchestrator queues), or frozen when the run ends.
2. **How a check's outcome is stored.** `StepRun.detail` and `errorMessage` already hold text such
   as "Expected: SUCCESS>=5 - actual: total=1 SUCCESS=1". Parsing text back into a table is fragile
   and the text is not meant as an interface.
3. **How the Sankey diagram is drawn.** A JS library (d3-sankey, Plotly) in the page, or SVG built
   on the server.

## Decision

1. **Snapshot at completion, stored in `run_report` (JSONB).** `ExecutionService` calls
   `RunCompletionHandler.onRunFinished` right after `engine.runScenario` (also when the engine
   throws). The handler rebuilds and stores the snapshot, then sends the notification. The engine
   knows nothing about reports. Rejected - computing on demand: queue contents disappear on
   `cleanup`, and `PUT /scenarios/{id}` recreates every `scenario_step` and edge, so a late report
   would silently lose the transactions and the DAG it is supposed to show. Runs that finished
   before this feature get a snapshot built on first request (their steps have no structured
   result, and the report says "no data" for their checks).
2. **Structured `step_run.result` (JSONB), filled by the executors** (robot and assignment of a
   `JOB`; expected, actual, passed and up to 200 transactions of a `QUEUE_CHECK`; queue and
   ownership of a `QUEUE`). The check result is written on every poll, before the step may throw,
   so a failed check reports its last snapshot. The snapshot is stored as a generic JSON map
   (the same mapping `ScenarioStep.config` already proves), converted from and to the typed
   `RunReportSnapshot` by the service.
3. **Server-side inline SVG, no scripts.** The report must be one self-contained file: no CDN (the
   service often runs in a network without internet), no JS (it is opened from a mail link, printed
   to PDF, and served with `Content-Security-Policy: default-src 'none'`). Rejected - a JS Sankey
   library: it needs the CDN or a vendored bundle, breaks under the strict CSP and does not print
   reliably. Cost: the layout is our own code (`SankeyDiagram`, about 200 lines): columns are the
   longest-path depth, band width is the parent's time split between its children, node height is
   the larger of its own time and the flow through it.

The mail is a short notice (verdict, times, first error, link) and not the report: mail clients
strip CSS and SVG, and the full report is behind authentication anyway.

## Consequences

- A report is immutable after the run ends, even if the scenario is edited or the queues cleaned.
- Adding a field to a step result is cheap (a map), but the HTML renderer only shows keys it knows;
  a new step type needs a renderer section.
- The snapshot grows with the number of steps and transactions (capped at 200 per check).
- `RunReportSnapshot` is a stored format: renaming or removing a field breaks old rows; add fields
  instead (missing fields deserialize to `null`).
- A run stopped by the user is reported when the engine finishes with it, which can be late; the
  on-demand build covers a report requested earlier.
- Everything from users or the orchestrator that reaches the HTML is escaped; keep it so when
  adding sections.
