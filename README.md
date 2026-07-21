# Sagacity

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.x-green.svg)](https://spring.io/projects/spring-ai)

**The SAGA pattern for AI agents.** Declarative compensation for Spring AI tool calls, with a tamper-evident audit trail.

> Your agent crashed after step 3 of 5. Sagacity undoes the mess — and produces the evidence.

---

## The Problem

AI agents perform multi-step tasks with **real-world side effects** — reserving inventory, creating orders, sending emails. When step 4 fails:

- ❌ Steps 1–3's side effects are **live in production**
- ❌ Nothing undoes them automatically
- ❌ There's no compliance-grade record of what happened

Every framework retries. **None compensates. None produces evidence.**

## The Solution

```java
@Tool(description = "Reserve inventory for a product")
@Compensable(by = "releaseInventory")
public String reserveInventory(String productId, int quantity) {
    // real side effect
}

@Compensation
public void releaseInventory(CompensationContext ctx) {
    // undoes the side effect using the original result
}
```

```java
// Wrap any agent task in a saga scope:
SagaResult<ChatResponse> result = sagacity.saga("place-order-123",
    () -> chatClient.prompt()
        .user("Place an order for 2 units of product p-1")
        .tools(orderTools)
        .call().chatResponse());

// On failure → compensations run in reverse order
// Every step is journaled with tamper-evident hash chain
```

## How It Works

```
ChatClient → ToolCallingManager → ToolCallback
                                       ↑
                              SagacityToolCallback (decorator)
                                       │
                     ┌─────────────────┼─────────────────┐
                     │                 │                 │
               1. Journal         2. Execute        3. Journal
                  INTENT            the tool         EXECUTED/FAILED
                                       │
                                       ↓ (on failure)
                              CompensationRunner
                              walks journal backward
                              runs @Compensation methods
```

**Key design decision:** Sagacity decorates at the `ToolCallback` level, not `ToolCallingManager`. This ensures we see raw failures *before* Spring AI's error handling swallows them.

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| `@Compensable` / `@Compensation` | ✅ Working | Declare undo logic per tool |
| Side-effect journal | ✅ In-memory | Append-only log: INTENT → EXECUTED/FAILED → COMPENSATED |
| Reverse-order compensation | ✅ Working | On failure, undo steps in reverse order |
| Saga scope | ✅ Working | `sagacity.saga(id, () -> ...)` wraps any agent task |
| Hash-chained journal (Postgres) | 🚧 M1 | Tamper-evident, EU AI Act Article 12 compliant |
| Reversibility classification | 🚧 M1 | `REVERSIBLE` / `COMPENSATABLE` / `IRREVERSIBLE` |
| Human approval gates | 📋 M2 | Irreversible actions require approval before execution |
| Audit export + verification | 📋 M2 | JSON Lines export, chain verification CLI |
| Spring Boot Starter | 📋 M3 | Auto-configuration, REST endpoints |

## Quick Start

> **Pre-alpha.** API will change. Use for evaluation only.

```xml
<!-- Maven (not yet on Central — build from source) -->
<dependency>
    <groupId>dev.sagacity</groupId>
    <artifactId>sagacity-spring-ai</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```bash
git clone https://github.com/sumitvairagar/sagacity.git
cd sagacity
mvn clean install
```

### Run the demo

```bash
cd sagacity-examples
mvn exec:java -Dexec.mainClass="dev.sagacity.examples.PlaceOrderDemo"
```

The demo shows: 3 tool calls, step 3 fails, steps 1–2 are compensated in reverse.

## Why Not Just Use Temporal / DBOS / Restate?

Those solve **durability** (resume after crash). Sagacity solves **compensation** and **evidence**:

| | Temporal/DBOS/Restate | Sagacity |
|---|---|---|
| Resume after crash | ✅ | ❌ (M4 via DBOS integration) |
| Undo side effects on failure | ❌ | ✅ |
| Tamper-evident audit trail | ❌ | ✅ |
| EU AI Act Article 12 | ❌ | ✅ |
| Spring AI native | ❌ | ✅ |
| Annotation-based DX | ❌ | ✅ |

They're complementary. Sagacity + DBOS (planned M4) gives you both.

## Architecture

```
sagacity-core          # Journal, hash chain, saga state machine, compensation runner
                       # (no Spring AI dependency — reusable for LangChain4j etc.)

sagacity-spring-ai     # SagacityToolCallback, @Compensable processing, Sagacity facade

sagacity-examples      # Order-placing agent demo with induced failure

(coming)
sagacity-spring-boot-starter   # Auto-config, approval REST endpoint, schema init
sagacity-langchain4j           # LangChain4j adapter
```

## Roadmap

| Milestone | Target | Status |
|-----------|--------|--------|
| **M0** — Walking skeleton | ✅ Done | In-memory journal, annotations, compensation runner, 10 tests green |
| **M1** — Real persistence | In progress | Postgres journal, hash chain, crash recovery |
| **M2** — Approval gates + audit | Next | IRREVERSIBLE tools, REST approve/reject, audit export |
| **M3** — Launch | — | Spring Boot starter, Maven Central, docs, demo app |
| **M4** — Ecosystem | — | DBOS integration, LangChain4j, streaming, MCP tools |

See [docs/ROADMAP.md](docs/ROADMAP.md) for details.

## Compliance: EU AI Act Article 12

The EU AI Act (enforceable for high-risk systems from **2026-08-02**) requires:
- Automatic logging of AI system operations
- Tamper-evident records retained 6–24 months
- Traceable decision chains

Sagacity's hash-chained journal is designed with Article 12 in mind. Every tool call is journaled with:
- Saga ID, tool name, timestamp
- Input parameters, output/error
- SHA-256 hash linking to the previous entry (tamper detection)
- Compensation outcome (if triggered)

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

Areas where help is most impactful:
- **M1:** Postgres journal implementation (Spring Data JDBC)
- **M1:** Hash chain verification
- **M2:** Approval gate REST API design
- **Testing:** More failure scenario tests
- **Docs:** Usage guides, architecture diagrams

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

<p align="center">
  <em>Built by <a href="https://github.com/sumitvairagar">@sumitvairagar</a> — 
  AI doesn't undo its mistakes. Sagacity does.</em>
</p>
