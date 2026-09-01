# URL Shortener

Turns a long URL into a short one, like bit.ly — you POST a URL and get back a short code; anyone
who visits it gets redirected to the original.

A Spring Boot implementation with create/redirect APIs, click analytics, and short-code generation
built as a Strategy pattern — the Schwab "AI-Assisted Software Engineering System" interview
assignment (`docs/010 - Assignment - AI-Proficient Software Engineer.pdf`).

## Quick start

```bash
./scripts/run.sh
```

## Docs

| | |
|---|---|
| [Working prototype](docs/01-working-prototype.md) | Proof it runs end-to-end, and the real bugs found getting there |
| [Architecture overview](docs/02-architecture-overview.md) | Components, control flow, key decisions |
| [Three scenarios](docs/03-three-scenarios.md) | Greenfield, brownfield, ambiguous |
| [Setup instructions](docs/04-setup-instructions.md) | Full setup, config, scripts |
| [Testing approach](docs/05-testing-approach.md) | Tests, load testing, quality gates, limitations |

See [`.agents/`](.agents/) for the AI-collaboration traceability log.
