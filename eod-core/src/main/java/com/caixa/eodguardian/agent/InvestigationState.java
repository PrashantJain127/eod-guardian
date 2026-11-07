package com.caixa.eodguardian.agent;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static java.util.Map.entry;

/**
 * State machine for the failure -> investigation -> approval -> execution -> recovery lifecycle.
 * Illegal transitions are rejected, so no step (e.g. resume before patch execution) can be skipped.
 */
public enum InvestigationState {
    DETECTED, INVESTIGATING, REFLECTING, ROOT_CAUSE_IDENTIFIED, NEEDS_HUMAN_REVIEW, PATCH_RECOMMENDED, NO_PATCH_REQUIRED,
    AWAITING_APPROVAL, APPROVED, REJECTED, PATCH_EXECUTED, ROLLED_BACK, READY_TO_RESUME, RESUMED, CLOSED, ERROR;

    private static final Map<InvestigationState, Set<InvestigationState>> NEXT = Map.ofEntries(
            entry(DETECTED, EnumSet.of(INVESTIGATING, ERROR)),
            entry(INVESTIGATING, EnumSet.of(ROOT_CAUSE_IDENTIFIED, REFLECTING, NEEDS_HUMAN_REVIEW, ERROR)),
            entry(REFLECTING, EnumSet.of(ROOT_CAUSE_IDENTIFIED, NEEDS_HUMAN_REVIEW, ERROR)),
            entry(ROOT_CAUSE_IDENTIFIED, EnumSet.of(REFLECTING, PATCH_RECOMMENDED, NO_PATCH_REQUIRED, NEEDS_HUMAN_REVIEW, ERROR)),
            entry(NEEDS_HUMAN_REVIEW, EnumSet.of(INVESTIGATING, PATCH_RECOMMENDED, CLOSED)),
            entry(PATCH_RECOMMENDED, EnumSet.of(AWAITING_APPROVAL, INVESTIGATING)),
            entry(NO_PATCH_REQUIRED, EnumSet.of(READY_TO_RESUME, INVESTIGATING)),
            entry(AWAITING_APPROVAL, EnumSet.of(APPROVED, REJECTED)),
            entry(APPROVED, EnumSet.of(PATCH_EXECUTED, APPROVED, REJECTED)),
            entry(REJECTED, EnumSet.of(INVESTIGATING, PATCH_RECOMMENDED, CLOSED)),
            entry(PATCH_EXECUTED, EnumSet.of(READY_TO_RESUME, ROLLED_BACK)),
            entry(ROLLED_BACK, EnumSet.of(INVESTIGATING, PATCH_RECOMMENDED, CLOSED)),
            entry(READY_TO_RESUME, EnumSet.of(RESUMED, ROLLED_BACK, READY_TO_RESUME)),
            entry(RESUMED, EnumSet.of(CLOSED, DETECTED)),
            entry(CLOSED, EnumSet.noneOf(InvestigationState.class)),
            entry(ERROR, EnumSet.of(INVESTIGATING, CLOSED)));

    public boolean canMoveTo(InvestigationState next) { return NEXT.get(this).contains(next); }

    public static Map<InvestigationState, Set<InvestigationState>> graph() { return NEXT; }
}
