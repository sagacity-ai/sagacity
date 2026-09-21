# Sagacity

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.x-green.svg)](https://spring.io/projects/spring-ai)
[![Build](https://github.com/sagacity-ai/sagacity/actions/workflows/build.yml/badge.svg)](https://github.com/sagacity-ai/sagacity/actions/workflows/build.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.sumitvairagar/sagacity-spring-boot-starter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.sumitvairagar/sagacity-spring-boot-starter)
[![Docs](https://img.shields.io/badge/docs-sagacity--ai.github.io%2Fsagacity-blue.svg)](https://sagacity-ai.github.io/sagacity/)
[![Tests](https://img.shields.io/badge/tests-167%20passing-brightgreen.svg)]()

**The SAGA pattern for AI agents.** Declarative compensation for Spring AI tool calls, with a tamper-evident audit trail.

> Your agent crashed after step 3 of 5. Sagacity undoes the mess — and produces the evidence.

<!-- Animated diagram: tools execute → failure → compensation flows backward -->
<p align="center">
  <img src="docs/saga-flow.svg" alt="Sagacity flow: execute tools, detect failure, compensate in reverse" width="800"/>
</p>

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

@Tool(description = "Charge the customer")
@Compensable(by = "refundCharge", retries = 3, retryOn = { PaymentTimeoutException.class })
public String chargeCustomer(String customerId, double amount) {
    // retried up to 3x on transient failures before failing the saga
}

@Tool(description = "Send wire transfer")
@Compensable(reversibility = Reversibility.IRREVERSIBLE)  // requires human approval
public String sendWireTransfer(String orderId) {
    // dangerous — can't be undone
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
// IRREVERSIBLE tools → suspended until human approves
// Every step journaled with tamper-evident hash chain
```

## Documentation

Full documentation: **[sagacity-ai.github.io/sagacity](https://sagacity-ai.github.io/sagacity/)**

| | |
|---|---|
| [Getting started](https://sagacity-ai.github.io/sagacity/getting-started/) | Working example in five minutes |
| [Approval gates](https://sagacity-ai.github.io/sagacity/guides/approval-gates/) | Human sign-off for irreversible tools |
| [Production checklist](https://sagacity-ai.github.io/sagacity/guides/production-checklist/) | Read before pointing this at real money |
| [Threat model](https://sagacity-ai.github.io/sagacity/concepts/threat-model/) | What the audit trail does and does not defend against |
| [REST API](https://sagacity-ai.github.io/sagacity/reference/rest-api/) | Endpoint reference |

## Quick Start

> **Want a runnable example in 5 minutes?**
> Clone [sagacity-quickstart](https://github.com/sumitvairagar/sagacity-quickstart) — a self-contained Spring Boot app that shows compensation and the audit trail in action. Just add your API key and run.

### 1. Add the dependency

```xml
<dependency>
    <groupId>io.github.sumitvairagar</groupId>
    <artifactId>sagacity-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

Or build from source:

```bash
git clone https://github.com/sagacity-ai/sagacity.git
cd sagacity
mvn clean install
```

### 2. Annotate your tools

```java
@Component
public class OrderTools {

    @Tool(description = "Reserve inventory for a product")
    @Compensable(by = "releaseInventory")
    public String reserveInventory(String productId, int quantity) {
        inventoryService.reserve(productId, quantity);
        return "Reserved " + quantity + " of " + productId;
    }

    @Compensation
    public void releaseInventory(CompensationContext ctx) {
        // ctx.input() has the original JSON input
        // ctx.result() has what reserveInventory returned
        inventoryService.release(extractProductId(ctx.input()));
    }

    @Tool(description = "Charge the customer")
    @Compensable(by = "refundCustomer")
    public String chargeCustomer(String customerId, double amount) {
        return paymentService.charge(customerId, amount);
    }

    @Compensation
    public void refundCustomer(CompensationContext ctx) {
        paymentService.refund(extractChargeId(ctx.result()));
    }

    @Tool(description = "Send confirmation email")
    @Compensable(reversibility = Reversibility.IRREVERSIBLE)
    public String sendConfirmation(String orderId) {
        // Can't unsend an email — requires human approval before executing
        return emailService.send(orderId);
    }
}
```

### 3. Run your agent in a saga

```java
@Service
public class OrderAgent {

    @Autowired private Sagacity sagacity;
    @Autowired private ChatClient chatClient;
    @Autowired private OrderTools orderTools;

    public SagaResult<ChatResponse> placeOrder(String userRequest) {
        ToolCallback[] tools = sagacity.wrap(orderTools);

        return sagacity.saga("order-" + UUID.randomUUID(), () ->
            chatClient.prompt()
                .user(userRequest)
                .toolCallbacks(tools)
                .call()
                .chatResponse()
        );
    }
}
```

### 4. Configure (application.yml)

```yaml
# Sagacity auto-configures with sensible defaults. All optional:
sagacity:
  enabled: true                      # default
  schema-init: true                  # auto-create tables on startup
  approval-endpoints-enabled: true   # expose REST API

  # v0.2.0: tool-level retry config
  retry:
    initial-delay-ms: 200            # default
    backoff-multiplier: 2.0          # default, caps at 30s

  # v0.2.0: use Sagacity Cloud instead of local DB (optional)
  # cloud:
  #   api-key: ${SAGACITY_API_KEY}

# Point to your database (PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite):
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/myapp
    username: myuser
    password: mypass
```

No DataSource? Sagacity falls back to an in-memory journal (great for dev/testing).

## How It Works

```
ChatClient → ToolCallingManager → ToolCallback
                                       ↑
                              SagacityToolCallback (decorator)
                                       │
                  ┌────────────────────┼────────────────────┐
                  │                    │                    │
            1. Journal           2. Execute           3. Journal
               INTENT             the tool            EXECUTED/FAILED
                  │                    │
                  │    (if IRREVERSIBLE)│   (on failure)
                  │         ↓          │        ↓
                  │  AWAITING_APPROVAL │  CompensationRunner
                  │  (saga suspends)   │  walks journal backward
                  │                    │  runs @Compensation methods
                  │                    │
                  ↓                    ↓
           Human approves       Journal records
           via REST API         every compensation
```

## REST API (Spring Boot Starter)

The starter exposes these endpoints automatically:

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/sagacity/approvals` | List all pending approval requests |
| `GET` | `/sagacity/approvals/{sagaId}` | Pending approvals for a saga |
| `POST` | `/sagacity/approve/{sagaId}/{seq}` | Approve (body: `{"approver": "admin@co.com"}`) |
| `POST` | `/sagacity/reject/{sagaId}/{seq}` | Reject + trigger compensation |
| `POST` | `/sagacity/resume/{sagaId}/{seq}` | Execute an approved tool (body: `{"payload": "..."}`) |
| `GET` | `/sagacity/audit/{sagaId}` | Export journal as JSON Lines |
| `GET` | `/sagacity/audit/{sagaId}/verify` | Verify hash chain integrity |

Approving does not execute. `/approve` records who signed off; `/resume` runs
the tool and is where the payload is checked against what was approved. Both
steps are required — `/resume` refuses with `409` if no approval was recorded,
and refuses again if the payload changed since.

### Example: Approve, then execute

```bash
# 1. Record the human decision
curl -X POST http://localhost:8080/sagacity/approve/order-123/5 \
  -H "Content-Type: application/json" \
  -d '{"approver": "manager@company.com"}'

# 2. Execute, binding to the exact payload that was approved
curl -X POST http://localhost:8080/sagacity/resume/order-123/5 \
  -H "Content-Type: application/json" \
  -d '{"payload": "{\"amount\":100,\"to\":\"alice\"}"}'
# {"sagaId":"order-123","status":"COMPLETED","result":"\"transfer-ok\""}

# A payload that differs from the approved one is refused, not executed:
# 409 {"sagaId":"order-123","status":"COMPENSATED",
#      "reason":"Stale approval rejected: payload changed since approval was granted"}
```

### Example: Verify audit trail

```bash
curl http://localhost:8080/sagacity/audit/order-123/verify
# {"valid":true,"entryCount":8,"breakAtIndex":-1,"message":"all 8 entries verified"}
```

## Features

| Feature | Status | Description |
|---------|--------|-------------|
| `@Compensable` / `@Compensation` | ✅ | Declare undo logic per tool |
| Reverse-order compensation | ✅ | On failure, undo steps in reverse |
| Universal JDBC journal + SHA-256 hash chain | ✅ | PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite |
| Human approval gates | ✅ | IRREVERSIBLE tools suspend until approved |
| Approve/Reject REST API | ✅ | With approver identity in audit trail |
| Audit export (JSON Lines) | ✅ | Compliance-ready, one entry per line |
| Hash chain verification | ✅ | Detect any modification to history |
| Spring Boot Starter | ✅ | Zero-config auto-wiring |
| Concurrent-safe | ✅ | Optimistic concurrency + unique-constraint retry, tested with 10 threads |
| Tool-level retry | ✅ | `@Compensable(retries=3, retryOn={...})` with exponential backoff |
| Sagacity Cloud journal | ✅ | Write journal to hosted cloud instead of local DB |

## Why Not Just Use Temporal / DBOS / Restate?

Those solve **durability** (resume after crash). Sagacity solves **compensation** and **evidence**:

| | Temporal/DBOS/Restate | Sagacity |
|---|---|---|
| Resume after crash | ✅ | 📋 (via DBOS integration) |
| Undo side effects on failure | ❌ | ✅ |
| Tamper-evident audit trail | ❌ | ✅ |
| EU AI Act Article 12 | ❌ | ✅ |
| Human approval gates | ❌ | ✅ |
| Spring AI native | ❌ | ✅ |
| Annotation-based DX | ❌ | ✅ |

They're complementary. Sagacity + DBOS (planned) gives you both.

## Architecture

```
sagacity-core                    # Journal, hash chain, compensation runner, approval store
                                 # Zero framework dependencies — reusable anywhere

sagacity-spring-ai               # SagacityToolCallback, @Compensable processing, Sagacity facade

sagacity-spring-boot-starter     # Auto-config, REST endpoints, schema init

sagacity-examples                # Order-placing agent demo with induced failure
```

## Compliance: EU AI Act Article 12

The EU AI Act (enforceable for high-risk systems from **2026-08-02**) requires:
- Automatic logging of AI system operations
- Tamper-evident records retained 6–24 months
- Traceable decision chains

Sagacity's hash-chained journal maps directly to Article 12:

| Article 12 Requirement | Sagacity Feature |
|------------------------|-----------------|
| Automatic logging | Every tool call journaled (INTENT/EXECUTED/FAILED) |
| Tamper-evident | SHA-256 hash chain, verifiable via REST API |
| Traceable decisions | Saga ID links all steps; approval identity recorded |
| Retention | JDBC persistence (PostgreSQL, MySQL, and more); retention policies (roadmap) |

## Roadmap

| Milestone | Status |
|-----------|--------|
| **M0** — Walking skeleton | ✅ Done |
| **M1** — Postgres journal + hash chain | ✅ Done |
| **M2** — Approval gates + audit export | ✅ Done |
| **M3** — Spring Boot Starter + Maven Central | ✅ Done (v0.1.0, Aug 2026) |
| **M3.5** — Cloud journal + retry + universal JDBC | ✅ Done (v0.2.0, Sept 2026) |
| **M4** — Ecosystem | 📋 Planned |

### M4 (planned):
- DBOS integration (durable compensation runs)
- LangChain4j adapter
- Streaming tool-call support
- MCP tool support
- Approval dashboard UI
- Authenticated approver identity + approval expiry

See [docs/about/roadmap.md](docs/about/roadmap.md) for details.

## Contributing

Contributions welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

**High-impact areas:**
- Testing: more failure scenarios, distributed tests
- LangChain4j adapter
- Documentation and examples
- Approval dashboard UI (React/Vue)

## License

Apache License 2.0 — see [LICENSE](LICENSE).

---

<p align="center">
  <strong>AI doesn't undo its mistakes. Sagacity does.</strong><br><br>
  Built by <a href="https://github.com/sumitvairagar">@sumitvairagar</a> | 
  <a href="https://youtube.com/@EngineerInAi">YouTube</a>
</p>
