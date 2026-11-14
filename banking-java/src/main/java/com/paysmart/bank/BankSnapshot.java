package com.paysmart.bank;

import java.util.*;
import static com.paysmart.bank.LedgerEntry.Direction.*;

/** Small educational bank aggregate. Immutable snapshots are the transaction boundary. */
public record BankSnapshot(Map<AccountNumber, BankAccount> accounts, Map<UUID, FundTransfer> transfers) {
    public BankSnapshot {
        accounts = Map.copyOf(accounts); transfers = Map.copyOf(transfers);
        var accountIds = new HashSet<UUID>(); var entryIds = new HashSet<UUID>(); var requests = new HashSet<String>();
        var journal = new HashMap<UUID, List<LedgerEntry>>();
        for (var pair : accounts.entrySet()) {
            BankAccount account = pair.getValue();
            if (!pair.getKey().equals(account.number()) || !accountIds.add(account.id())) throw new BankingException("Duplicate account identity");
            account.reconcile();
            for (LedgerEntry entry : account.ledger()) {
                if (!entryIds.add(entry.id())) throw new BankingException("Duplicate ledger entry ID");
                journal.computeIfAbsent(entry.transactionId(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        for (var pair : transfers.entrySet()) {
            FundTransfer transfer = pair.getValue();
            if (!pair.getKey().equals(transfer.id()) || !requests.add(transfer.requestId())) throw new BankingException("Duplicate transfer identity or request ID");
            var entries = journal.getOrDefault(transfer.id(), List.of());
            if (entries.size() != 2) throw new BankingException("Transfer must have two matching ledger entries");
            var debit = entries.stream().filter(e -> e.direction() == DEBIT).findFirst().orElseThrow(() -> new BankingException("Transfer debit missing"));
            var credit = entries.stream().filter(e -> e.direction() == CREDIT).findFirst().orElseThrow(() -> new BankingException("Transfer credit missing"));
            if (!debit.accountNumber().equals(transfer.source()) || !credit.accountNumber().equals(transfer.destination())
                    || !debit.amount().equals(transfer.amount()) || !credit.amount().equals(transfer.amount())) throw new BankingException("Unbalanced transfer");
            if (transfer.reversalOf() != null) {
                FundTransfer original = transfers.get(transfer.reversalOf());
                if (original == null || original.status() != FundTransfer.Status.REVERSED
                        || !original.source().equals(transfer.destination()) || !original.destination().equals(transfer.source())
                        || !original.amount().equals(transfer.amount())) throw new BankingException("Invalid reversal");
                for (LedgerEntry entry : entries) {
                    if (entry.reversalOf() == null || !accounts.get(entry.accountNumber()).ledger().stream()
                            .anyMatch(e -> e.id().equals(entry.reversalOf()) && e.transactionId().equals(original.id()))) throw new BankingException("Missing reversal audit reference");
                }
            }
            if (transfer.status() == FundTransfer.Status.REVERSED) {
                long reversals = transfers.values().stream().filter(t -> transfer.id().equals(t.reversalOf())).count();
                if (reversals != 1) throw new BankingException("Reversed transfer must have exactly one compensation");
            }
        }
        for (var group : journal.entrySet()) {
            if (group.getValue().size() != 1 && !transfers.containsKey(group.getKey())) throw new BankingException("Unidentified multi-account transaction");
        }
    }
    /** Commit guard: historical facts and account identities cannot be discarded. */
    public static void validateTransition(BankSnapshot before, BankSnapshot after) {
        for (BankAccount original : before.accounts.values()) {
            BankAccount next = after.accounts.get(original.number());
            if (next == null || !next.id().equals(original.id()) || !next.overdraftLimit().equals(original.overdraftLimit()))
                throw new BankingException("Cannot remove or replace an account or its agreed overdraft");
            if (original.status() == AccountStatus.CLOSED && next.status() != AccountStatus.CLOSED)
                throw new BankingException("Closed accounts cannot be reopened");
            if (original.status() == AccountStatus.FROZEN && next.status() == AccountStatus.CLOSED)
                throw new BankingException("Unfreeze the account before closing");
            if (next.ledger().size() < original.ledger().size()) throw new BankingException("Ledger history cannot be deleted");
            for (int i = 0; i < original.ledger().size(); i++) {
                if (!original.ledger().get(i).samePosting(next.ledger().get(i))) throw new BankingException("Posted ledger entries cannot be modified");
            }
            for (int i = original.ledger().size(); i < next.ledger().size(); i++) {
                if (original.status() == AccountStatus.CLOSED || original.status() == AccountStatus.FROZEN && next.ledger().get(i).direction() == DEBIT)
                    throw new BankingException("Account status prohibits this posting");
            }
        }
        for (FundTransfer original : before.transfers.values()) {
            FundTransfer next = after.transfers.get(original.id());
            if (next == null || !original.requestId().equals(next.requestId())
                    || !original.matches(next.source(), next.destination(), next.amount(), next.reversalOf())
                    || !original.completedAt().equals(next.completedAt())
                    || original.status() == FundTransfer.Status.REVERSED && next.status() != original.status())
                throw new BankingException("Transfer history cannot be removed or rewritten");
        }
    }
    public static BankSnapshot empty() { return new BankSnapshot(Map.of(), Map.of()); }
    public BankAccount account(AccountNumber number) {
        BankAccount account = accounts.get(number);
        if (account == null) throw new BankingException("Account not found: " + number);
        return account;
    }
    public Optional<FundTransfer> request(String requestId) {
        return transfers.values().stream().filter(t -> t.requestId().equals(requestId)).findFirst();
    }
}
