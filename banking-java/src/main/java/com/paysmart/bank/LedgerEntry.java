package com.paysmart.bank;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable entity: equality follows its entry ID, not its financial contents. */
public record LedgerEntry(UUID id, UUID accountId, AccountNumber accountNumber, long sequence,
                          UUID transactionId, Direction direction, Money amount, Money balanceAfter,
                          Instant occurredAt, String reference, UUID reversalOf) {
    public enum Direction { DEBIT, CREDIT }
    public LedgerEntry {
        Objects.requireNonNull(id); Objects.requireNonNull(accountId); Objects.requireNonNull(accountNumber);
        Objects.requireNonNull(transactionId); Objects.requireNonNull(direction); Objects.requireNonNull(amount);
        Objects.requireNonNull(balanceAfter); Objects.requireNonNull(occurredAt); Objects.requireNonNull(reference);
        if (sequence < 1 || reference.isBlank() || reference.length() > 200) throw new BankingException("Invalid ledger metadata");
        amount.requirePositive(); amount.sameCurrency(balanceAfter);
    }
    public Money signedAmount() { return direction == Direction.CREDIT ? amount : amount.negate(); }
    public boolean samePosting(LedgerEntry other) {
        return id.equals(other.id) && accountId.equals(other.accountId) && accountNumber.equals(other.accountNumber)
                && sequence == other.sequence && transactionId.equals(other.transactionId) && direction == other.direction
                && amount.equals(other.amount) && balanceAfter.equals(other.balanceAfter) && occurredAt.equals(other.occurredAt)
                && reference.equals(other.reference) && Objects.equals(reversalOf, other.reversalOf);
    }
    @Override public boolean equals(Object other) { return other instanceof LedgerEntry entry && id.equals(entry.id); }
    @Override public int hashCode() { return id.hashCode(); }
}
