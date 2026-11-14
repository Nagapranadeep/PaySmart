package com.paysmart.bank;

import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import static com.paysmart.bank.LedgerEntry.Direction.*;

/** Application service coordinates an atomic aggregate; entities enforce local rules. */
public final class BankService {
    @FunctionalInterface public interface TransferProbe { void afterStagedDebit(); }
    private final BankRepository repository;
    private final TransferProbe probe;
    public BankService(BankRepository repository) { this(repository, () -> { }); }
    public BankService(BankRepository repository, TransferProbe probe) {
        this.repository = Objects.requireNonNull(repository); this.probe = Objects.requireNonNull(probe);
    }
    public BankSnapshot snapshot() { return repository.read(); }
    public BankAccount account(AccountNumber number) { return snapshot().account(number); }
    public BankAccount openAccount(AccountNumber number, OverdraftLimit limit) {
        return repository.transact(before -> {
            if (before.accounts().containsKey(number)) throw new BankingException("Account number already exists");
            BankAccount account = BankAccount.open(number, limit); var accounts = new HashMap<>(before.accounts()); accounts.put(number, account);
            return new BankRepository.Change<>(new BankSnapshot(accounts, before.transfers()), account);
        });
    }
    public BankAccount deposit(AccountNumber number, Money amount, String reference) { return changeAccount(number, a -> a.deposit(amount, reference)); }
    public BankAccount withdraw(AccountNumber number, Money amount, String reference) { return changeAccount(number, a -> a.withdraw(amount, reference)); }
    public BankAccount freeze(AccountNumber number) { return changeAccount(number, BankAccount::freeze); }
    public BankAccount unfreeze(AccountNumber number) { return changeAccount(number, BankAccount::unfreeze); }
    public BankAccount close(AccountNumber number) { return changeAccount(number, BankAccount::close); }
    private BankAccount changeAccount(AccountNumber number, UnaryOperator<BankAccount> operation) {
        return repository.transact(before -> {
            BankAccount next = operation.apply(before.account(number)); var accounts = new HashMap<>(before.accounts()); accounts.put(number, next);
            return new BankRepository.Change<>(new BankSnapshot(accounts, before.transfers()), next);
        });
    }
    public FundTransfer transfer(AccountNumber source, AccountNumber destination, Money amount, String requestId) {
        return repository.transact(before -> stageTransfer(before, source, destination, amount, requestId, null));
    }
    public FundTransfer reverseTransfer(UUID originalId, String requestId) {
        return repository.transact(before -> {
            FundTransfer original = before.transfers().get(originalId);
            if (original == null || original.reversalOf() != null) throw new BankingException("Original transfer not found or is itself a reversal");
            var replay = before.request(requestId);
            if (replay.isPresent()) {
                if (!replay.get().matches(original.destination(), original.source(), original.amount(), originalId)) throw new BankingException("Request ID belongs to a different operation");
                return new BankRepository.Change<>(before, replay.get());
            }
            if (original.status() != FundTransfer.Status.COMPLETED) throw new BankingException("Transfer has already been reversed");
            return stageTransfer(before, original.destination(), original.source(), original.amount(), requestId, originalId);
        });
    }
    private BankRepository.Change<FundTransfer> stageTransfer(BankSnapshot before, AccountNumber from, AccountNumber to,
                                                             Money amount, String requestId, UUID reversalOf) {
        amount.requirePositive();
        var existing = before.request(requestId);
        if (existing.isPresent()) {
            if (!existing.get().matches(from, to, amount, reversalOf)) throw new BankingException("Request ID reused with a different transfer");
            return new BankRepository.Change<>(before, existing.get());
        }
        if (from.equals(to)) throw new BankingException("Source and destination must differ");
        BankAccount source = before.account(from), destination = before.account(to);
        source.balance().sameCurrency(amount); destination.balance().sameCurrency(amount);
        UUID transactionId = UUID.randomUUID(); Instant time = Instant.now();
        UUID sourceReversal = originalEntry(source, reversalOf), destinationReversal = originalEntry(destination, reversalOf);
        BankAccount debited = source.post(DEBIT, amount, transactionId, "Transfer to " + to, time, sourceReversal);
        // Deliberate failure seam: NOTHING has been persisted or exposed to readers yet.
        probe.afterStagedDebit();
        BankAccount credited = destination.post(CREDIT, amount, transactionId, "Transfer from " + from, time, destinationReversal);
        var accounts = new HashMap<>(before.accounts()); accounts.put(from, debited); accounts.put(to, credited);
        var transfers = new HashMap<>(before.transfers());
        FundTransfer transfer = new FundTransfer(transactionId, requestId, from, to, amount, time, FundTransfer.Status.COMPLETED, reversalOf);
        if (reversalOf != null) transfers.put(reversalOf, transfers.get(reversalOf).markReversed());
        transfers.put(transfer.id(), transfer);
        return new BankRepository.Change<>(new BankSnapshot(accounts, transfers), transfer);
    }
    private UUID originalEntry(BankAccount account, UUID transferId) {
        if (transferId == null) return null;
        return account.ledger().stream().filter(e -> e.transactionId().equals(transferId)).map(LedgerEntry::id).findFirst()
                .orElseThrow(() -> new BankingException("Original ledger entry missing"));
    }
}
