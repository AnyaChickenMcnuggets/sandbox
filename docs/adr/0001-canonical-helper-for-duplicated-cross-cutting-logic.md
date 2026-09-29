# 0001 — Canonical helper for duplicated cross-cutting logic, instead of per-caller reimplementation

- Status: Accepted (retroactive — records a convention already in force since Sprint 23)
- Date: 2026-09-23 (Sprint 23), recorded 2026-09-29

## Context

`QueueCheckStepExecutor` and `QueueAuditService` both needed the same behavior: fetch queue items,
optionally filtered by `naturalKey`/`naturalKeyPart`, capped by `MAX_PAGES × PAGE_SIZE` when no
filter is given. Before Sprint 23 each class carried its own private pagination/filtering methods.
The duplication wasn't just extra text — the two copies had already started drifting (one applied
the `deletedAt == null` filter, the other didn't), and the bug that prompted Sprint 23 ("queue-items
view shows the full queue instead of the step's own keys") was easy to miss in one copy while fixed
in the other.

An alternative considered: leave the duplication and just fix each copy's bug independently when
found. Rejected — that's how the drift happened in the first place; a module (per `agents.md`'s
"Списки элементов очереди" rule) either owns the concept or the fix has to be repeated by hand every
time the underlying orchestrator quirk (`MAX_PAGES` cap, undocumented `NaturalKey`/`NaturalKeyPart`
params) needs re-verifying.

## Decision

Duplicated logic that reads/interprets orchestrator data for more than one caller gets pulled into
one canonical module, and every caller goes through it — no caller keeps its own copy "for
convenience" or "because it's slightly different". First instance: `QueueItemFinder`
(`execution/engine`), owning all queue-item pagination/filtering. `QueueCheckStepExecutor` and
`QueueAuditService` both call it; neither has its own pagination loop anymore.

If a caller's need is genuinely different (e.g. "browse one page for debugging" vs. "find all
matches for these keys") — see `QueueAuditService`'s no-filter branch, which deliberately stays on
raw `ExchangeQueuesPort.listItems` because `QueueItemFinder`'s empty-filter path does a different,
incompatible thing (full scan ignoring caller pagination). Document the reason inline; don't route
around the canonical module silently, and don't force an incompatible use case through it either.

## Consequences

- New code that needs queue-item reads must call `QueueItemFinder`, not write a new pagination loop.
- The same principle now applies deliberately to other cross-cutting duplication as it's found — see
  ADR 0002 for the next instance (id→name resolution, status narration).
- Cost: an extra indirection/dependency for callers that previously inlined the logic. Accepted —
  the alternative (drift) already cost a real bug.
