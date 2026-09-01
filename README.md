# URL Shortener

A Spring Boot URL shortener with create/redirect APIs and click analytics, built as the Schwab
"AI-Assisted Software Engineering System" interview assignment
(`docs/010 - Assignment - AI-Proficient Software Engineer.pdf`).

Short-code generation is built as a **Strategy pattern** with two implementations — see
[Architecture overview](docs/02-architecture-overview.md).

## Quick start

```bash
./scripts/run.sh
```
Full detail: [Setup instructions](docs/04-setup-instructions.md).

## The five deliverables

The assignment's Section 5 asks for five specific deliverables. Each has its own detailed doc:

1. **[Working prototype (runnable end-to-end)](docs/01-working-prototype.md)** — what's actually
   been verified running, including the two real bugs only found by running the app rather than
   just compiling/testing it.
2. **[Architecture overview](docs/02-architecture-overview.md)** — components, tools, control
   flow, key decisions, and the Strategy pattern used for short-code generation.
3. **[Three scenarios — greenfield, brownfield, ambiguous](docs/03-three-scenarios.md)** — each
   with decomposition, execution, and validation.
4. **[Setup instructions](docs/04-setup-instructions.md)**.
5. **[Testing approach, limitations, and trade-offs](docs/05-testing-approach.md)** — automated
   tests, manual verification, quality gates (PMD, SpotBugs, OWASP dependency-check, jacoco), and
   every known limitation, named on purpose rather than left for a reviewer to discover.

See [`.agents/`](.agents/) for the full AI-collaboration traceability log — what was generated,
edited, or rejected, and why, including the design arguments that didn't survive (e.g. a proposed
`ShortCodeAllocator` component that got rejected in favor of each generator owning its own retry
logic).
