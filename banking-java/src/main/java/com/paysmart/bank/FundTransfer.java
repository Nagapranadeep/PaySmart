package com.paysmart.bank;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FundTransfer(UUID id, String requestId, AccountNumber source, AccountNumber destination,
                           Money amount, Instant completedAt, Status status, UUID reversalOf) {
    public enum Status { COMPLETED, REVERSED }
    public FundTransfer {
        Objects.requireNonNull(id); Objects.requireNonNull(requestId); Objects.requireNonNull(source);
        Objects.requireNonNull(destination); Objects.requireNonNull(amount); Objects.requireNonNull(completedAt); Objects.requireNonNull(status);
        amount.requirePositive();
        if (source.equals(destination)) throw new BankingException("Source and destination must differ");
        if (requestId.isBlank() || requestId.length() > 100) throw new BankingException("Invalid transfer request ID");
    }
    public FundTransfer markReversed() {
        if (status != Status.COMPLETED || reversalOf != null) throw new BankingException("Transfer cannot be reversed again");
        return new FundTransfer(id, requestId, source, destination, amount, completedAt, Status.REVERSED, null);
    }
    public boolean matches(AccountNumber from, AccountNumber to, Money value, UUID reversal) {
        return source.equals(from) && destination.equals(to) && amount.equals(value) && Objects.equals(reversalOf, reversal);
    }
    @Override public boolean equals(Object other) { return other instanceof FundTransfer transfer && id.equals(transfer.id); }
    @Override public int hashCode() { return id.hashCode(); }
}
