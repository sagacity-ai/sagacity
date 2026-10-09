package dev.sagacity.mcp;

import javax.sql.DataSource;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import dev.sagacity.control.ApprovalStore;
import dev.sagacity.control.PostgresApprovalStore;
import dev.sagacity.recovery.CompensationRegistry;
import dev.sagacity.recovery.CompensationRunner;
import dev.sagacity.audit.JdbcAuditStore;
import dev.sagacity.audit.AuditStore;

/**
 * Sagacity MCP Server — exposes Sagacity governance as MCP tools.
 *
 * <p>Any AI agent on any framework (LangChain4j, Spring AI, Koog, Python LangChain)
 * can connect to this server over Streamable-HTTP MCP and call:
 * <ul>
 *   <li>{@code log_tool_call} — append an entry to the tamper-evident audit trail</li>
 *   <li>{@code request_approval} — suspend a saga and wait for human approval</li>
 *   <li>{@code compensate} — trigger rollback for a failed saga</li>
 * </ul>
 */
@SpringBootApplication
public class SagacityMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SagacityMcpServerApplication.class, args);
    }

    /**
     * JDBC-backed journal — works on PostgreSQL, MySQL, MariaDB, Oracle, H2, SQLite.
     * Schema is initialised by Spring Boot's {@code spring.sql.init} on first run.
     */
    @Bean
    public AuditStore sideEffectJournal(DataSource dataSource) {
        return new JdbcAuditStore(dataSource);
    }

    /**
     * Postgres-backed approval store — durable across restarts.
     * Pending approvals survive a JVM restart; in-memory would lose them.
     */
    @Bean
    public ApprovalStore approvalStore(DataSource dataSource) {
        return new PostgresApprovalStore(dataSource);
    }

    /**
     * Compensation registry — empty by default when accessed via MCP.
     *
     * <p>When agents use the MCP server, they trigger compensation by calling
     * {@code compensate(sagaId)}. The actual compensation handlers run inside
     * the agent's own process (registered via {@code @Compensable} annotations)
     * — not here. The MCP server records the compensation signal in the journal
     * and the agent's local CompensationRunner does the work.
     *
     * <p>For future: support remote compensation handler registration.
     */
    @Bean
    public CompensationRegistry compensationRegistry() {
        return new CompensationRegistry();
    }

    /**
     * Compensation runner — walks journal in reverse, triggers registered handlers.
     */
    @Bean
    public CompensationRunner compensationRunner(AuditStore journal, CompensationRegistry registry) {
        return new CompensationRunner(journal, registry);
    }
}
