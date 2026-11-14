package com.paysmart.bank;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import static java.nio.file.StandardOpenOption.*;

/** Single-host durable aggregate, serialized across threads AND cooperating JVMs. */
public final class AtomicFileBankRepository implements BankRepository {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path path;
    public AtomicFileBankRepository(Path path) {
        this.path = path.toAbsolutePath().normalize();
        try { Files.createDirectories(this.path.getParent()); }
        catch (IOException e) { throw new BankingException("Cannot create bank data directory", e); }
    }
    @Override public BankSnapshot read() { return locked(this::load); }
    @Override public <T> T transact(Function<BankSnapshot, Change<T>> operation) {
        return locked(() -> {
            BankSnapshot before = load(); Change<T> change = operation.apply(before);
            BankSnapshot.validateTransition(before, change.state());
            if (change.state() != before) save(change.state());
            return change.result();
        });
    }
    private <T> T locked(IOOperation<T> operation) {
        ReentrantLock local = JVM_LOCKS.computeIfAbsent(path, ignored -> new ReentrantLock());
        local.lock();
        try (var channel = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"), CREATE, WRITE);
             var ignored = channel.lock()) { return operation.execute(); }
        catch (IOException e) { throw new BankingException("Bank storage operation failed; retry transfers with the SAME request ID", e); }
        finally { local.unlock(); }
    }
    private BankSnapshot load() throws IOException {
        if (!Files.exists(path)) return BankSnapshot.empty();
        long size = Files.size(path);
        if (size < 33 || size > MAX_BYTES) throw new IOException("Invalid snapshot size");
        byte[] bytes = Files.readAllBytes(path), payload = Arrays.copyOf(bytes, bytes.length - 32), checksum = Arrays.copyOfRange(bytes, bytes.length - 32, bytes.length);
        if (!MessageDigest.isEqual(checksum, hash(payload))) throw new IOException("Snapshot checksum mismatch; restore a verified backup");
        try { return SnapshotCodec.decode(payload); }
        catch (RuntimeException e) { throw new IOException("Snapshot violates banking invariants", e); }
    }
    private void save(BankSnapshot state) throws IOException {
        byte[] payload = SnapshotCodec.encode(state);
        if (payload.length + 32L > MAX_BYTES) throw new IOException("Snapshot size limit reached");
        Path temporary = Files.createTempFile(path.getParent(), ".bank-", ".tmp");
        try {
            try (var file = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocate(payload.length + 32).put(payload).put(hash(payload)); buffer.flip();
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            // No non-atomic fallback: unsupported filesystems fail without publishing the candidate.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Persist the directory entry. Failure here is an UNCERTAIN outcome; reread/retry the same ID.
            try (var directory = FileChannel.open(path.getParent(), READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    @FunctionalInterface private interface IOOperation<T> { T execute() throws IOException; }
}
