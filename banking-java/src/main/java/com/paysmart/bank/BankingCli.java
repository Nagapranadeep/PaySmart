package com.paysmart.bank;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/** Trusted-operator educational CLI. Authentication is outside this Java domain module. */
public final class BankingCli {
    public static void main(String[] args) {
        try {
            int offset = 0; Path file = Path.of("work/interactive.bank");
            if (args.length >= 2 && args[0].equals("--file")) { file = Path.of(args[1]); offset = 2; }
            String[] command = Arrays.copyOfRange(args, offset, args.length);
            if (command.length == 0) { usage(); return; }
            var bank = new BankService(new AtomicFileBankRepository(file));
            switch (command[0]) {
                case "open" -> { count(command, 3); show(bank.openAccount(number(command[1]), new OverdraftLimit(Money.inr(command[2])))); }
                case "deposit" -> { minimum(command, 4); show(bank.deposit(number(command[1]), Money.inr(command[2]), reference(command, 3))); }
                case "withdraw" -> { minimum(command, 4); show(bank.withdraw(number(command[1]), Money.inr(command[2]), reference(command, 3))); }
                case "freeze" -> { count(command, 2); show(bank.freeze(number(command[1]))); }
                case "unfreeze" -> { count(command, 2); show(bank.unfreeze(number(command[1]))); }
                case "close" -> { count(command, 2); show(bank.close(number(command[1]))); }
                case "transfer" -> { count(command, 5); System.out.println(bank.transfer(number(command[1]), number(command[2]), Money.inr(command[3]), command[4])); }
                case "reverse" -> { count(command, 3); System.out.println(bank.reverseTransfer(UUID.fromString(command[1]), command[2])); }
                case "accounts" -> { count(command, 1); bank.snapshot().accounts().values().stream().sorted(java.util.Comparator.comparing(a -> a.number().value())).forEach(BankingCli::show); }
                case "ledger" -> { count(command, 2); var account = bank.account(number(command[1])); account.reconcile(); show(account); account.ledger().forEach(System.out::println); }
                case "transfers" -> { count(command, 1); bank.snapshot().transfers().values().forEach(System.out::println); }
                default -> throw new BankingException("Unknown command. Run without arguments for usage.");
            }
        } catch (BankingException | IllegalArgumentException e) { System.err.println("REJECTED: " + e.getMessage()); System.exit(2); }
    }
    private static AccountNumber number(String value) { return new AccountNumber(value); }
    private static String reference(String[] args, int from) { return String.join(" ", Arrays.copyOfRange(args, from, args.length)); }
    private static void count(String[] args, int expected) { if (args.length != expected) throw new BankingException("Incorrect arguments; run without arguments for usage"); }
    private static void minimum(String[] args, int expected) { if (args.length < expected) throw new BankingException("Missing arguments; run without arguments for usage"); }
    private static void show(BankAccount account) { System.out.println(account.number() + " | " + account.status() + " | " + account.balance() + " | limit " + account.overdraftLimit().amount()); }
    private static void usage() {
        System.out.println("PaySmart Java Banking CLI — simulated accounts, no real banking connections");
        System.out.println("Optional prefix: --file work/custom.bank");
        System.out.println("open NUMBER OVERDRAFT_INR | deposit NUMBER AMOUNT REFERENCE | withdraw NUMBER AMOUNT REFERENCE");
        System.out.println("freeze NUMBER | unfreeze NUMBER | close NUMBER");
        System.out.println("transfer SOURCE DESTINATION AMOUNT REQUEST_ID | reverse TRANSFER_UUID REQUEST_ID");
        System.out.println("accounts | ledger NUMBER | transfers");
    }
}
