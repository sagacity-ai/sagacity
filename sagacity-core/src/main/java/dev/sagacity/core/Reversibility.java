package dev.sagacity.core;

/**
 * How a tool's side effect can be undone. Declared per tool; drives what Sagacity
 * does when a saga fails after the tool has executed.
 */
public enum Reversibility {

	/** A perfect inverse exists (e.g. delete the row that was inserted). */
	REVERSIBLE,

	/** An imperfect but acceptable undo exists (e.g. send a correction email). */
	COMPENSATABLE,

	/**
	 * No undo exists (e.g. an outbound wire transfer). From M2 on, executing such a
	 * tool requires a human approval gate before it runs.
	 */
	IRREVERSIBLE

}
