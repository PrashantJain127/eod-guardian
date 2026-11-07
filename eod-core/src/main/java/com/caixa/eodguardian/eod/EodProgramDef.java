package com.caixa.eodguardian.eod;

import java.util.List;

/**
 * Static definition of a FLEXCUBE EOD batch program (as maintained in the EOD function setup).
 *
 * @param postsAccounting true when the program generates accounting entries (duplicate-posting guarded)
 * @param periodEndOnly   program runs only on the financial year-end (otherwise SKIPPED)
 */
public record EodProgramDef(String id, String name, String module, String stage, int sequence,
                            List<String> dependsOn, String plsqlPackage, double targetSeconds,
                            boolean postsAccounting, boolean periodEndOnly, String description) {}
