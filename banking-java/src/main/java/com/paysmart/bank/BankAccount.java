package com.paysmart.bank;

import java.time.Instant;
import java.util.*;
import static com.paysmart.bank.LedgerEntry.Direction.*;

/** Self-enforcing immutable entity: every successful operation returns a new version. */
public final class BankAccount {
    private final UUID id;
    private final AccountNumber number;
    private final Money balance;
    private final OverdraftLimit overdraftLimit;
    private final AccountStatus status;
    private final List<LedgerEntry> ledger;

    private BankAccount(UUID id, AccountNumber number, Money balance, OverdraftLimit limit,
                        AccountStatus status, List<LedgerEntry> ledger) {
        this.id = Objects.requireNonNull(id); this.number = Objects.requireNonNull(number);
        this.balance = Objects.requireNonNull(balance); this.overdraftLimit = Objects.requireNonNull(limit);
        this.status = Objects.requireNonNull(status); this.ledger = List.copyOf(ledger);
        balance.sameCurrency(limit.amount());
        if (!limit.permits(balance)) throw new BankingException("Overdraft limit exceeded");
        if (status == AccountStatus.CLOSED && !balance.isZero()) throw new BankingException("A closed account must have zero balance");
        reconcile();
    }
    public static BankAccount open(AccountNumber number, OverdraftLimit limit) {
        return new BankAccount(UUID.randomUUID(), number, Money.zero(limit.amount().currency()), limit, AccountStatus.ACTIVE, List.of());
    }
    static BankAccount restore(UUID id, AccountNumber number, Money balance, OverdraftLimit limit,
                               AccountStatus status, List<LedgerEntry> ledger) {
        return new BankAccount(id, number, balance, limit, status, ledger);
    }
    public UUID id() { return id; }
    public AccountNumber number() { return number; }
    public Money balance() { return balance; }
    public OverdraftLimit overdraftLimit() { return overdraftLimit; }
    public AccountStatus status() { return status; }
    public List<LedgerEntry> ledger() { return ledger; }
    public BankAccount deposit(Money amount, String reference) {
        return post(CREDIT, amount, UUID.randomUUID(), reference, Instant.now(), null);
    }
    public BankAccount withdraw(Money amount, String reference) {
        return post(DEBIT, amount, UUID.randomUUID(), reference, Instant.now(), null);
    }
    BankAccount post(LedgerEntry.Direction direction, Money amount, UUID transactionId,
                     String reference, Instant time, UUID reversalOf) {
        Objects.requireNonNull(amount); amount.requirePositive(); balance.sameCurrency(amount);
        if (status == AccountStatus.CLOSED) throw new BankingException("Account is closed");
        // Policy: frozen accounts may receive money, but no outgoing debit is permitted.
        if (direction == DEBIT && status != AccountStatus.ACTIVE) throw new BankingException("Frozen accounts cannot be debited");
        Money nextBalance = direction == CREDIT ? balance.add(amount) : balance.subtract(amount);
        if (!overdraftLimit.permits(nextBalance)) throw new BankingException("Overdraft limit exceeded");
        var entries = new ArrayList<>(ledger);
        entries.add(new LedgerEntry(UUID.randomUUID(), id, number, ledger.size() + 1L, transactionId,
                direction, amount, nextBalance, time, reference, reversalOf));
        return new BankAccount(id, number, nextBalance, overdraftLimit, status, entries);
    }
    public BankAccount freeze() {
        requireStatus(AccountStatus.ACTIVE); return withStatus(AccountStatus.FROZEN);
    }
    public BankAccount unfreeze() {
        requireStatus(AccountStatus.FROZEN); return withStatus(AccountStatus.ACTIVE);
    }
    public BankAccount close() {
        requireStatus(AccountStatus.ACTIVE);
        if (!balance.isZero()) throw new BankingException("Settle the balance before closing");
        return withStatus(AccountStatus.CLOSED);
    }
    private BankAccount withStatus(AccountStatus next) {
        return new BankAccount(id, number, balance, overdraftLimit, next, ledger);
    }
    private void requireStatus(AccountStatus expected) {
        if (status != expected) throw new BankingException("Account must be " + expected + "; current state is " + status);
    }
    public void reconcile() {
        Money running = Money.zero(balance.currency()); long sequence = 0;
        var ids = new HashSet<UUID>();
        for (LedgerEntry entry : ledger) {
            if (!entry.accountId().equals(id) || !entry.accountNumber().equals(number)
                    || entry.sequence() != ++sequence || !ids.add(entry.id())) throw new BankingException("Invalid ledger identity or sequence");
            running = running.add(entry.signedAmount());
            if (!running.equals(entry.balanceAfter()) || !overdraftLimit.permits(running)) throw new BankingException("Invalid running ledger balance");
        }
        if (!running.equals(balance)) throw new BankingException("Balance does not reconcile with ledger");
    }
    @Override public boolean equals(Object other) { return other instanceof BankAccount account && id.equals(account.id); }
    @Override public int hashCode() { return id.hashCode(); }
}
