package dev.sagacity.core.journal;

/** Lifecycle phase of one side effect, as recorded in the journal. */
public enum Phase {

	/** Journaled before the tool executes: "we are about to do this". */
	INTENT,

	/** The tool executed successfully; payload holds the result snapshot. */
	EXECUTED,

	/** The tool threw; payload holds the error. Effect state may be unknown. */
	FAILED,

	/** The declared compensation for this effect ran successfully. */
	COMPENSATED,

	/** The declared compensation itself failed; payload holds the error. */
	COMPENSATION_FAILED,

	/** The saga is suspended waiting for human approval on an IRREVERSIBLE tool. */
	AWAITING_APPROVAL,

	/** A human approved the execution of an IRREVERSIBLE tool. */
	APPROVED,

	/** A human rejected the execution of an IRREVERSIBLE tool. */
	REJECTED

}
