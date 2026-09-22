package dev.sagacity.workflows.check;

/**
 * Contextual information passed to a {@link StageCheck} before a stage executes.
 */
public record StageCheckContext(
        /** The unique ID of this workflow run. */
        String runId,
        /** The logical name of the workflow (from {@link dev.sagacity.workflows.annotation.Workflow#value()}). */
        String workflowName,
        /** The name of the stage about to execute. */
        String stageName,
        /** The stage's declared order. */
        int stageOrder,
        /**
         * The input argument passed to the stage, as a string for context.
         * The format is {@code toString()} of the actual argument, or {@code "null"}.
         */
        String stageInput
) {}
