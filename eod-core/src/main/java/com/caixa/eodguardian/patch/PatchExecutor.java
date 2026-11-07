package com.caixa.eodguardian.patch;

import java.util.Map;

/** Implemented by the Data Patch Execution Agent - the only component allowed to write to FLEXCUBE. */
public interface PatchExecutor {

    record Outcome(boolean success, boolean rolledBack, String message, Map<String, Object> details) {}

    Outcome apply(DataPatch patch);

    Outcome revert(DataPatch patch);
}
