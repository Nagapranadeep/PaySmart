package com.paysmart.bank;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free executable tests. Assertions are explicit and always enabled. */
public final class BankingTests {
    private static final AccountNumber A = new AccountNumber("1000000001"), B = new AccountNumber("1000000002");
    private static int passed;
    public static void main(String[] args) throws Exception {
        test("Money value equality, exact decimals and currency safety", BankingTests::money);
        test("Positive amounts only; rejected operations leave no entry", BankingTests::amounts);
        test("Exact overdraft boundary accepted; next paisa rejected", BankingTests::overdraft);
        test("Freeze, incoming-credit policy, unfreeze and closed terminal state", BankingTests::lifecycle);
        test("Ledger and returned collections cannot be modified", BankingTests::encapsulation);
        test("INR 5000 transfer conserves funds and has two linked entries", BankingTests::transfer);
        test("Missing, same, closed and foreign-currency destinations leave source untouched", BankingTests::badDestinations);
        test("Exception between debit and credit rolls back all staged state", BankingTests::rollback);
        test("Transfer retry is idempotent and conflicting reuse is rejected", BankingTests::idempotency);
        test("Reversal adds compensating entries; originals stay unchanged", BankingTests::reversal);
        test("Concurrent double-spend attempts respect available funds", BankingTests::concurrency);
        test("Competing freezes and withdrawals are serialized", BankingTests::freezeRace);
        test("Persistence, restart, checksum validation and reconciliation", BankingTests::persistence);
        test("Real child-process crash after debit loses no money", BankingTests::processCrash);
        test("Separate repository instances serialize updates to one file", BankingTests::repositoryConcurrency);
        test("Invalid balance/ledger reconstruction is rejected", BankingTests::corruption);
        test("Repository rejects account removal and ledger rewriting", BankingTests::appendOnly);
        System.out.println("\nPASS: " + passed + " Java banking tests");
    }
    private static BankService bank() { return seeded(new InMemoryBankRepository()); }
    private static BankService seeded(BankRepository repository) {
        var bank = new BankService(repository); bank.openAccount(A, new OverdraftLimit(Money.inr("1000"))); bank.openAccount(B, new OverdraftLimit(Money.inr("0")));
        bank.deposit(A, Money.inr("10000"), "Opening deposit"); bank.deposit(B, Money.inr("2000"), "Opening deposit"); return bank;
    }
    private static void money() {
        equal(Money.inr("1"), Money.inr("1.00"));
        equal(new AccountNumber("1000000001"), A);
        rejects(() -> Money.inr("0.001")); rejects(() -> new OverdraftLimit(Money.inr("-1")));
        rejects(() -> Money.inr("1").add(new Money(new BigDecimal("1"), Currency.getInstance("USD"))));
        rejects(() -> new AccountNumber("not-a-number"));
    }
    private static void amounts() {
        var bank = bank(); int count = bank.account(A).ledger().size();
        for (String invalid : List.of("0", "-1")) {
            rejects(() -> bank.deposit(A, Money.inr(invalid), "Invalid")); rejects(() -> bank.withdraw(A, Money.inr(invalid), "Invalid"));
            rejects(() -> bank.transfer(A, B, Money.inr(invalid), "invalid-" + invalid));
        }
        equal(bank.account(A).balance(), Money.inr("10000")); equal(bank.account(A).ledger().size(), count);
    }
    private static void overdraft() {
        var bank = bank(); bank.withdraw(A, Money.inr("11000"), "Boundary"); equal(bank.account(A).balance(), Money.inr("-1000"));
        rejects(() -> bank.withdraw(A, Money.inr("0.01"), "Beyond limit")); equal(bank.account(A).balance(), Money.inr("-1000"));
        rejects(() -> bank.close(A));
    }
    private static void lifecycle() {
        var bank = bank(); bank.freeze(A); rejects(() -> bank.withdraw(A, Money.inr("1"), "Frozen")); rejects(() -> bank.transfer(A, B, Money.inr("1"), "frozen"));
        bank.deposit(A, Money.inr("1"), "Incoming to frozen allowed"); equal(bank.account(A).balance(), Money.inr("10001")); rejects(() -> bank.freeze(A));
        bank.unfreeze(A); bank.withdraw(A, Money.inr("10001"), "Settle to zero"); bank.close(A);
        rejects(() -> bank.deposit(A, Money.inr("1"), "Closed")); rejects(() -> bank.withdraw(A, Money.inr("1"), "Closed")); rejects(() -> bank.freeze(A)); rejects(() -> bank.unfreeze(A)); rejects(() -> bank.close(A));
    }
    private static void encapsulation() {
        var bank = bank(); var before = bank.account(A); var next = before.withdraw(Money.inr("1"), "Detached immutable version");
        equal(before.balance(), Money.inr("10000")); equal(next.balance(), Money.inr("9999")); equal(bank.account(A).balance(), before.balance());
        rejects(() -> before.ledger().clear()); rejects(() -> bank.snapshot().accounts().clear());
        for (var field : BankAccount.class.getDeclaredFields()) {
            check(java.lang.reflect.Modifier.isPrivate(field.getModifiers()), "Account field is not private");
            check(java.lang.reflect.Modifier.isFinal(field.getModifiers()), "Account field is not final");
        }
    }
    private static void transfer() {
        var bank = bank(); Money total = bank.account(A).balance().add(bank.account(B).balance()); var t = bank.transfer(A, B, Money.inr("5000"), "transfer-5000");
        equal(bank.account(A).balance(), Money.inr("5000")); equal(bank.account(B).balance(), Money.inr("7000")); equal(bank.account(A).balance().add(bank.account(B).balance()), total);
        var debit = bank.account(A).ledger().getLast(); var credit = bank.account(B).ledger().getLast(); equal(debit.transactionId(), t.id()); equal(credit.transactionId(), t.id());
        equal(debit.signedAmount().add(credit.signedAmount()), Money.inr("0")); bank.account(A).reconcile(); bank.account(B).reconcile();
    }
    private static void badDestinations() {
        var bank = bank(); rejects(() -> bank.transfer(A, new AccountNumber("9999999999"), Money.inr("5000"), "missing")); rejects(() -> bank.transfer(A, A, Money.inr("1"), "same"));
        var c = new AccountNumber("1000000003"); bank.openAccount(c, new OverdraftLimit(Money.inr("0"))); bank.close(c); rejects(() -> bank.transfer(A, c, Money.inr("5000"), "closed"));
        var d = new AccountNumber("1000000004"); bank.openAccount(d, new OverdraftLimit(new Money(BigDecimal.ZERO, Currency.getInstance("USD")))); rejects(() -> bank.transfer(A, d, Money.inr("5000"), "currency"));
        equal(bank.account(A).balance(), Money.inr("10000")); equal(bank.account(A).ledger().size(), 1);
    }
    private static void rollback() {
        var repository = new InMemoryBankRepository(); var bank = seeded(repository);
        var failing = new BankService(repository, () -> { throw new BankingException("Injected failure"); });
        rejects(() -> failing.transfer(A, B, Money.inr("5000"), "failure")); equal(bank.account(A).balance(), Money.inr("10000")); equal(bank.account(B).balance(), Money.inr("2000")); equal(bank.snapshot().transfers().size(), 0);
    }
    private static void idempotency() {
        var bank = bank(); var first = bank.transfer(A, B, Money.inr("5000"), "same-id"); var second = bank.transfer(A, B, Money.inr("5000.00"), "same-id"); equal(first.id(), second.id());
        equal(bank.account(A).ledger().size(), 2); rejects(() -> bank.transfer(A, B, Money.inr("1"), "same-id")); equal(bank.account(A).balance(), Money.inr("5000"));
    }
    private static void reversal() {
        var bank = bank(); var t = bank.transfer(A, B, Money.inr("5000"), "original"); var entry = bank.account(A).ledger().getLast();
        var reverse = bank.reverseTransfer(t.id(), "reverse"); equal(bank.account(A).balance(), Money.inr("10000")); equal(bank.account(B).balance(), Money.inr("2000"));
        check(bank.account(A).ledger().get(1) == entry, "Original entry was replaced"); equal(bank.account(A).ledger().getLast().reversalOf(), entry.id()); equal(bank.snapshot().transfers().get(t.id()).status(), FundTransfer.Status.REVERSED);
        equal(bank.reverseTransfer(t.id(), "reverse").id(), reverse.id()); rejects(() -> bank.reverseTransfer(t.id(), "reverse-again"));
        var other = bank(); var original = other.transfer(A, B, Money.inr("5000"), "original-2"); other.freeze(B); rejects(() -> other.reverseTransfer(original.id(), "frozen-reversal")); equal(other.account(A).balance(), Money.inr("5000"));
    }
    private static void concurrency() throws Exception {
        var bank = bank(); var pool = Executors.newFixedThreadPool(8); var start = new CountDownLatch(1);
        try {
            var jobs = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) { final int id = i; jobs.add(pool.submit(() -> { start.await(); try { bank.transfer(A, B, Money.inr("6000"), "concurrent-" + id); return true; } catch (BankingException rejected) { return false; } })); }
            start.countDown(); int successful = 0; for (var job : jobs) if (job.get(10, TimeUnit.SECONDS)) successful++;
            equal(successful, 1); equal(bank.account(A).balance(), Money.inr("4000")); equal(bank.account(B).balance(), Money.inr("8000"));
        } finally { pool.shutdownNow(); }
    }
    private static void freezeRace() throws Exception {
        var bank = bank(); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var freeze = pool.submit(() -> { start.await(); bank.freeze(A); return true; });
            var debit = pool.submit(() -> { start.await(); try { bank.withdraw(A, Money.inr("100"), "Concurrent debit"); return true; } catch (BankingException expected) { return false; } });
            start.countDown(); freeze.get(); boolean debited = debit.get(); equal(bank.account(A).status(), AccountStatus.FROZEN); equal(bank.account(A).balance(), Money.inr(debited ? "9900" : "10000"));
            rejects(() -> bank.withdraw(A, Money.inr("1"), "After frozen"));
        } finally { pool.shutdownNow(); }
    }
    private static void persistence() throws Exception {
        Path path = Files.createTempDirectory("paysmart-bank-test-").resolve("bank.data");
        try {
            var bank = seeded(new AtomicFileBankRepository(path)); var first = bank.transfer(A, B, Money.inr("5000"), "persisted");
            var reloaded = new BankService(new AtomicFileBankRepository(path)); equal(reloaded.account(A).balance(), Money.inr("5000")); equal(reloaded.transfer(A, B, Money.inr("5000"), "persisted").id(), first.id());
            byte[] bytes = Files.readAllBytes(path); bytes[10] ^= 1; Files.write(path, bytes); rejects(reloaded::snapshot);
        } finally { cleanup(path.getParent()); }
    }
    private static void processCrash() throws Exception {
        Path path = Files.createTempDirectory("paysmart-bank-crash-").resolve("bank.data");
        try {
            var bank = seeded(new AtomicFileBankRepository(path)); String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), CrashDuringTransfer.class.getName(), path.toString()).inheritIO().start();
            check(process.waitFor(15, TimeUnit.SECONDS), "Child did not finish"); equal(process.exitValue(), 99);
            var reloaded = new BankService(new AtomicFileBankRepository(path)); equal(reloaded.account(A).balance(), Money.inr("10000")); equal(reloaded.account(B).balance(), Money.inr("2000")); equal(reloaded.account(A).ledger().size(), 1);
            reloaded.transfer(A, B, Money.inr("5000"), "process-crash-transfer"); equal(reloaded.account(B).balance(), Money.inr("7000"));
        } finally { cleanup(path.getParent()); }
    }
    private static void repositoryConcurrency() throws Exception {
        Path path = Files.createTempDirectory("paysmart-bank-lock-").resolve("bank.data"); var pool = Executors.newFixedThreadPool(2);
        try {
            seeded(new AtomicFileBankRepository(path)); var first = new BankService(new AtomicFileBankRepository(path)); var second = new BankService(new AtomicFileBankRepository(path));
            var one = pool.submit(() -> first.transfer(A, B, Money.inr("1000"), "file-one")); var two = pool.submit(() -> second.transfer(A, B, Money.inr("1000"), "file-two")); one.get(); two.get();
            equal(first.account(A).balance(), Money.inr("8000")); equal(second.account(B).balance(), Money.inr("4000"));
        } finally { pool.shutdownNow(); cleanup(path.getParent()); }
    }
    private static void corruption() {
        var a = bank().account(A); rejects(() -> BankAccount.restore(a.id(), a.number(), Money.inr("9999"), a.overdraftLimit(), a.status(), a.ledger()));
        var bad = new LedgerEntry(UUID.randomUUID(), a.id(), a.number(), 2, UUID.randomUUID(), LedgerEntry.Direction.CREDIT, Money.inr("1"), Money.inr("9000"), Instant.now(), "Bad running balance", null);
        rejects(() -> BankAccount.restore(a.id(), a.number(), Money.inr("9000"), a.overdraftLimit(), a.status(), List.of(a.ledger().getFirst(), bad)));
    }
    private static void appendOnly() {
        var repository = new InMemoryBankRepository(); var bank = seeded(repository);
        rejects(() -> repository.transact(before -> new BankRepository.Change<>(BankSnapshot.empty(), null)));
        rejects(() -> repository.transact(before -> {
            var accounts = new HashMap<>(before.accounts());
            var original = before.account(A); var entry = original.ledger().getFirst();
            var forged = new LedgerEntry(entry.id(), entry.accountId(), entry.accountNumber(), entry.sequence(), entry.transactionId(), entry.direction(), entry.amount(), entry.balanceAfter(), entry.occurredAt(), "Rewritten narrative", entry.reversalOf());
            accounts.put(A, BankAccount.restore(original.id(), A, original.balance(), original.overdraftLimit(), original.status(), List.of(forged)));
            return new BankRepository.Change<>(new BankSnapshot(accounts, before.transfers()), null);
        }));
        equal(bank.account(A).ledger().getFirst().reference(), "Opening deposit");
        equal(bank.account(A).balance(), Money.inr("10000"));
    }
    private static void cleanup(Path dir) throws Exception { try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } }
    private static void rejects(Runnable operation) { try { operation.run(); } catch (RuntimeException expected) { return; } throw new AssertionError("Expected rejection"); }
    private static void equal(Object actual, Object expected) { if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + "; got " + actual); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface CheckedTest { void run() throws Exception; }
    private static void test(String name, CheckedTest test) throws Exception { test.run(); passed++; System.out.println("PASS " + name); }
}
