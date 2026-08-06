# Design — early foreground-budget warning

## Context

The engine parks on budget exhaustion and reschedules. The user finds out after
the run has already stopped, and is never told about the option that was
available the whole time: work done with the app visible costs no budget,
because a visible activity keeps the process alive without a foreground service.

## Goals / Non-Goals

**Goals** — surface the foreground route before the budget is gone, once, with
enough information to decide.

**Non-Goals** — more background time. There is no honest way to get it.

## Decisions

### Warn, do not extend

The instinct is to find a way to keep running. There isn't one that is both
honest and reliable:

- **A different FGS type.** `mediaPlayback`, `location` and `specialUse` are
  uncapped. All three would mean telling the OS the service does something it
  does not, and `specialUse` additionally requires a Play declaration that a
  reviewer reads. This is the kind of workaround that gets an app removed, and it
  is not worth an edge case that affects the largest runs only.
- **Restarting the service after timeout.** The cap is cumulative over a rolling
  24 hours, not per service instance. A new service inherits the spent budget.
- **`WorkManager` without a foreground service.** Background execution limits are
  stricter still, not looser.

What genuinely works is moving the work out of the background: with a visible
activity the process needs no service and consumes no allowance. So the change is
informational, and the honest framing is "open the app to finish now" rather than
"we found extra time".

### Once per slice, at 80%

At the default five-hour soft budget that is a warning after four hours with an
hour of runway — enough to act on, not so early it is noise.

Repeating it would be worse than not sending it. A notification that fires every
few minutes gets swallowed by the user's habit of dismissing it, including the
one time it mattered.

### The exhaustion notification stays

The warning is best-effort: the user may be asleep, or notifications may be
denied. The existing "continues tomorrow" alert remains the truthful fallback.

## Risks / Trade-offs

**The user taps and immediately backgrounds the app again** → billing resumes;
they are no worse off than before the warning.

**Notifications denied** → nothing is posted and the run parks as it does today.
Already the documented degradation.

**80% is wrong for very short budgets** → configurable, and the default is only
meaningful against the default budget.

## Open Questions

**Does bringing the app to the foreground reset the platform's 24-hour counter?**
Unknown, and deliberately not depended upon. The design is correct either way: if
it does reset, the user gains background time as a bonus; if it does not, the
work still proceeds unbilled while visible. Worth measuring on a real device
during a long run, because it changes the advice we give — not the code.
