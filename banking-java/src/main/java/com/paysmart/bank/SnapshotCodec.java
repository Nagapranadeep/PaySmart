package com.paysmart.bank;

import java.io.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Explicit versioned format, not Java object deserialization. */
final class SnapshotCodec {
    private SnapshotCodec() { }
    static byte[] encode(BankSnapshot state) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(bytes)) {
            out.writeInt(1); out.writeInt(state.accounts().size());
            for (BankAccount account : state.accounts().values()) {
                uuid(out, account.id()); out.writeUTF(account.number().value()); money(out, account.balance()); money(out, account.overdraftLimit().amount());
                out.writeUTF(account.status().name()); out.writeInt(account.ledger().size());
                for (LedgerEntry entry : account.ledger()) {
                    uuid(out, entry.id()); out.writeLong(entry.sequence()); uuid(out, entry.transactionId()); out.writeUTF(entry.direction().name());
                    money(out, entry.amount()); money(out, entry.balanceAfter()); out.writeUTF(entry.occurredAt().toString()); out.writeUTF(entry.reference()); nullableUuid(out, entry.reversalOf());
                }
            }
            out.writeInt(state.transfers().size());
            for (FundTransfer transfer : state.transfers().values()) {
                uuid(out, transfer.id()); out.writeUTF(transfer.requestId()); out.writeUTF(transfer.source().value()); out.writeUTF(transfer.destination().value());
                money(out, transfer.amount()); out.writeUTF(transfer.completedAt().toString()); out.writeUTF(transfer.status().name()); nullableUuid(out, transfer.reversalOf());
            }
        }
        return bytes.toByteArray();
    }
    static BankSnapshot decode(byte[] payload) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != 1) throw new IOException("Unsupported bank snapshot version");
            var accounts = new HashMap<AccountNumber, BankAccount>();
            int accountCount = count(in);
            for (int i = 0; i < accountCount; i++) {
                UUID id = uuid(in); var number = new AccountNumber(in.readUTF()); Money balance = money(in); var limit = new OverdraftLimit(money(in));
                var status = AccountStatus.valueOf(in.readUTF()); int entryCount = count(in); var ledger = new ArrayList<LedgerEntry>();
                for (int j = 0; j < entryCount; j++) {
                    ledger.add(new LedgerEntry(uuid(in), id, number, in.readLong(), uuid(in), LedgerEntry.Direction.valueOf(in.readUTF()),
                            money(in), money(in), Instant.parse(in.readUTF()), in.readUTF(), nullableUuid(in)));
                }
                if (accounts.put(number, BankAccount.restore(id, number, balance, limit, status, ledger)) != null) throw new IOException("Duplicate account number");
            }
            var transfers = new HashMap<UUID, FundTransfer>(); int transferCount = count(in);
            for (int i = 0; i < transferCount; i++) {
                var transfer = new FundTransfer(uuid(in), in.readUTF(), new AccountNumber(in.readUTF()), new AccountNumber(in.readUTF()),
                        money(in), Instant.parse(in.readUTF()), FundTransfer.Status.valueOf(in.readUTF()), nullableUuid(in));
                if (transfers.put(transfer.id(), transfer) != null) throw new IOException("Duplicate transfer ID");
            }
            if (in.read() != -1) throw new IOException("Unexpected trailing snapshot data");
            return new BankSnapshot(accounts, transfers);
        }
    }
    private static int count(DataInputStream in) throws IOException {
        int count = in.readInt(); if (count < 0 || count > 1_000_000) throw new IOException("Invalid collection size"); return count;
    }
    private static void money(DataOutputStream out, Money value) throws IOException { out.writeUTF(value.currency().getCurrencyCode()); out.writeUTF(value.amount().toPlainString()); }
    private static Money money(DataInputStream in) throws IOException { Currency currency = Currency.getInstance(in.readUTF()); return new Money(new BigDecimal(in.readUTF()), currency); }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
    private static void nullableUuid(DataOutputStream out, UUID id) throws IOException { out.writeBoolean(id != null); if (id != null) uuid(out, id); }
    private static UUID nullableUuid(DataInputStream in) throws IOException { return in.readBoolean() ? uuid(in) : null; }
}
