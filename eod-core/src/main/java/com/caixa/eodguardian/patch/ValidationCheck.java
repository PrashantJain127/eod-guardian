package com.caixa.eodguardian.patch;

import com.caixa.eodguardian.common.JsonSerializable;
import com.caixa.eodguardian.common.Maps;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A pre- or post-patch validation: a read-only query shown to the reviewer (sqlText) plus
 * the executable check evaluated against the FLEXCUBE gateway.
 */
public final class ValidationCheck implements JsonSerializable {

    public record Result(boolean passed, String observed) {}

    private final String name;
    private final String description;
    private final String sqlText;
    private final boolean blocking;
    private final Supplier<Result> evaluator;
    private Result lastResult;
    private Instant lastRunAt;

    public ValidationCheck(String name, String description, String sqlText, boolean blocking, Supplier<Result> evaluator) {
        this.name = name;
        this.description = description;
        this.sqlText = sqlText;
        this.blocking = blocking;
        this.evaluator = evaluator;
    }

    public synchronized Result run() {
        try {
            lastResult = evaluator.get();
        } catch (RuntimeException e) {
            lastResult = new Result(false, "Check could not be evaluated: " + e.getMessage());
        }
        lastRunAt = Instant.now();
        return lastResult;
    }

    public String name() { return name; }
    public boolean blocking() { return blocking; }
    public synchronized Result lastResult() { return lastResult; }

    @Override
    public synchronized Map<String, Object> toMap() {
        return Maps.of("name", name, "description", description, "sql", sqlText, "blocking", blocking,
                "passed", lastResult == null ? null : lastResult.passed(),
                "observed", lastResult == null ? null : lastResult.observed(), "lastRunAt", lastRunAt);
    }
}
