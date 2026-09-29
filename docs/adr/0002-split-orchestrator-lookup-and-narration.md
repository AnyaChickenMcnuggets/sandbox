# 0002 — Split id/state resolution (I/O) from human-text formatting (pure) into two modules

- Status: Accepted
- Date: 2026-09-29

## Context

Architecture review (`/mattpocock-skills:improve-codebase-architecture`, candidate A) found the same
duplication shape ADR 0001 fixed for queue reads, recurring for human-facing text:

- `StatusPoller.describeState` and `JobStepExecutor.describeError` both called
  `RpaProjectQueuePort.findByAssignment(assignmentId)` independently — same port, same query,
  different field extracted from the result. Not just duplicated phrasing; a duplicated HTTP call.
- `JobStepExecutor.resolveProjectLabel` did its own best-effort id→name resolution
  (`RpaProjectsPort.findById`, fall back to `"id=" + id`) with no shared owner.
- `QueueCheckStepExecutor.describeExpectation`/`describeActual`/`describe` built the
  "ожидалось/фактически" text independently of the above, though the shape (raw data → phrase for
  `StepRun.detail`) is the same concept.

## Decision

Split into two modules rather than one, because the two concerns have different seams:

1. **`OrchestratorLookup`** (`execution/engine`, Spring `@Component`, holds `RpaProjectsPort` +
   `RpaProjectQueuePort`) — owns every id→something resolution that needs an orchestrator call:
   `resolveProjectId`, `resolveProjectLabel`, `findQueueEntries`. Lives in `execution/engine` (not
   `orchestrator/*`) because `resolveProjectId`'s failure mode is execution-specific
   (`StepExecutionException`), matching the existing pattern of `execution/engine` classes holding
   `*Port` fields directly (`StatusPoller`, `JobStepExecutor` already did this before the split).

2. **`OrchestratorNarration`** (`orchestrator/util`, pure static methods, no Spring bean, no I/O) —
   owns turning already-fetched orchestrator data into human text: `describeRunning`,
   `describeQueued`, `describeQueueError`, `describeExpectation`, `describeActual`,
   `describeCheckResult`. Mirrors the existing `OrchestratorNames` utility in style (final class,
   private constructor, static methods) and package (`orchestrator/util`) — it only touches
   orchestrator DTOs and generic `Map`s, no execution-domain types, so it can sit there without
   `orchestrator/*` needing to know about `execution/*`.

Rejected alternative: one combined module. Rejected because bundling I/O and pure formatting behind
one interface would mean every test of a formatting-only behavior (e.g. "does `describeExpectation`
render `minTotalCount` correctly") has to mock ports it doesn't need, the same testability problem
`ExecutionService`'s god-constructor exhibits (see architecture review, candidate B).

`StatusPoller` and `JobStepExecutor` now both call `OrchestratorLookup.findQueueEntries` once and
pass the same result to `OrchestratorNarration` for formatting — the duplicated HTTP call is gone,
not just the duplicated string-building.

## Consequences

- `JobStepExecutor`'s constructor dropped two dependencies (`RpaProjectsPort`,
  `RpaProjectQueuePort` → one `OrchestratorLookup`).
- New tests: `OrchestratorLookupTest` (mocks the 2 ports it wraps), `OrchestratorNarrationTest`
  (mocks nothing — pure functions). Existing `JobStepExecutorTest`/`StatusPollerTest` construct a
  real `OrchestratorLookup` around mocked ports rather than mocking `OrchestratorLookup` itself,
  matching this project's established test convention (`ExchangeQueueProvisioner`,
  `QueueItemFinder`).
- Any future code producing a human-facing status/id message must go through these two modules —
  see `agents.md`.
