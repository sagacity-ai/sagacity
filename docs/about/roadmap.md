# Sagacity — Roadmap

_"Done" = tested + documented, not just working._  
_Last updated: 2026-09-24_

---

## Where we are right now

**v0.4.0 — live on Maven Central**

The library is feature-complete for its core promise:

- Declarative workflows (`@Workflow`, `@Stage`, `@Gate`, `@Check`)
- Automatic compensation on failure — reverse order, no boilerplate
- Human approval gates that survive JVM restarts (JDBC-backed)
- SHA-256 tamper-evident audit trail — verifiable via REST
- Embedded UI at `/sagacity/ui` — zero config, ships in the JAR
- Universal JDBC — PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite

**The library works. The problem now is nobody knows it exists.**

---

## What's next — and why, in priority order

### 1. 🎬 YouTube video — "Your Spring AI agent broke production. Nothing undid it."
**Why first:** This is the single highest-leverage action. One video reaching the right developer
changes everything — stars, downloads, consulting leads, community#37 response. The library
is good enough. Distribution is the bottleneck.
**Status:** Script written. Slides built. Ready to record.
**Blocking:** Nothing. Just record it.

---

### 2. 📣 Reddit post — r/SpringBoot
**Why second:** r/SpringBoot is where Spring developers are. A well-written post about a real
problem ("AI agents that charge cards but can't undo") gets organic traction. This is the
cheapest distribution channel available right now.
**Status:** Draft exists from previous session. Needs update to v0.4.0.
**Blocking:** Nothing. 30 minutes of work.

---

### 3. 🤝 Nudge Christian Tzolov — spring-ai-community/community#37
**Why third:** Official spring-ai-community inclusion = thousands of downloads with zero
marketing. Christian's original comment ("could be a candidate") was the warm signal.
The library is now dramatically stronger — v0.4.0 with full workflows, durable state,
embedded UI. The case has never been better.
**Status:** Issue open since Aug 8, no response. Overdue for a nudge.
**Blocking:** Nothing. Write a comment, mention v0.4.0.

---

### 4. 🔧 v0.5.0 — Developer experience improvements
**Why:** The library works but some edges are rough. These are the things that will
cause a developer who just discovered Sagacity to give up before they get value.

**In priority order:**

| Item | Why it matters |
|---|---|
| **Typed compensation** — bind original parameters to `@Compensation` directly instead of `CompensationContext` string wrangling | Current API is awkward. Every demo shows the ugly `ctx.result()` cast. |
| **Approval expiry** — `@Gate(timeoutSeconds=3600)` causes workflow to fail automatically | Without this, a gate can wait forever with no observable failure. Breaks production use. |
| **Better error messages** — topology validation errors are terse | "No @Compensation method found" should say which workflow, which stage, what it expected. |
| **`@Stage(name="...")` default** — currently blank name uses method name | Method names like `validateRefund` are fine but `notifyCompliance` looks ugly in the UI. |

---

### 5. 🌍 v0.6.0 — Ecosystem expansion
**Why:** LangChain4j is the #2 Java AI framework. Supporting it doubles the addressable market
without changing the core library at all.

| Item | Why it matters |
|---|---|
| **LangChain4j adapter** — `sagacity-langchain4j` module | LangChain4j has serious enterprise adoption. Same compensation story applies. |
| **MCP tool support** — compensations for MCP-server tools declared client-side | Spring AI 2.0 has first-class MCP. Any agent using MCP tools needs compensation too. |
| **Streaming tool-call support** | Currently synchronous ChatClient flows only. Streaming agents can't use Sagacity. |

---

### 6. 💰 Sagacity Cloud — first paying customer
**Why:** The free library drives adoption. The Cloud tier drives revenue.
The infrastructure exists (`sagacity-cloud-api`, `sagacity-dashboard`). What's missing is
someone paying for it.

**The gap:** The Cloud product isn't positioned or marketed anywhere. The docs mention it
but there's no pricing page, no clear call to action, no email to contact.

**What's needed before pursuing this:**
- Video published (step 1) — creates inbound
- Reddit post live (step 2) — creates awareness
- A landing page rewrite positioning around EU AI Act compliance — "Can your AI system prove what it did and in what order? Sagacity can."

---

## Milestone history

| Version | Date | What shipped |
|---|---|---|
| v0.1.0 (M0–M3) | Aug 7, 2026 | Compensation, hash-chained journal, approval gates, Maven Central, 106 tests |
| v0.2.0 (M3.5) | Sept 17, 2026 | Cloud journal, retry with backoff, universal JDBC (MySQL/Oracle/H2) |
| v0.3.0 (M4) | Sept 22, 2026 | `sagacity-workflows` — `@Stage`, `@Gate`, `@Check`, embedded UI |
| v0.4.0 (M5) | Sept 23, 2026 | Durable JDBC workflow store — gate approvals survive restarts |

---

## Free vs Paid — the line

**Free forever (open-source library):** everything a solo developer or small trusted team
needs — compensation, workflows, gates, audit trail, hash verification, embedded UI (no auth),
JSON Lines export, startup topology validation.

**Sagacity Cloud only:** multi-user + RBAC, SSO/SAML, hosted journal (off your DB),
PDF/CSV audit export for regulators, cross-deployment history, alerting, search,
compliance reports, data retention SLA.

The embedded UI handles everything a developer needs in development and staging.
Cloud is what a production enterprise team needs when multiple people need login,
audit exports, and retention guarantees.

---

## Explicitly deferred

- Python / TypeScript ports
- Agent-to-agent saga propagation
- Automatic undo inference
- SSO/SAML, RBAC, multi-user approval → Sagacity Cloud only
