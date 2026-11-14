# UML — implemented Java model

GitHub renders the Mermaid diagrams below. Names correspond to classes in `banking-java/src/main/java/com/paysmart/bank/`.

## Class diagram

```mermaid
classDiagram
    class BankAccount {
        <<entity>>
        -UUID id
        -AccountNumber number
        -Money balance
        -OverdraftLimit overdraftLimit
        -AccountStatus status
        -List~LedgerEntry~ ledger
        +open(number, limit) BankAccount
        +deposit(amount, reference) BankAccount
        +withdraw(amount, reference) BankAccount
        +freeze() BankAccount
        +unfreeze() BankAccount
        +close() BankAccount
        +reconcile() void
    }
    class AccountNumber {
        <<immutable value object>>
        +String value
    }
    class Money {
        <<immutable value object>>
        +BigDecimal amount
        +Currency currency
        +add(other) Money
        +subtract(other) Money
        +requirePositive() void
        +sameCurrency(other) void
    }
    class OverdraftLimit {
        <<immutable value object>>
        +Money amount
        +permits(balance) boolean
    }
    class AccountStatus {
        <<enumeration>>
        ACTIVE
        FROZEN
        CLOSED
    }
    class LedgerEntry {
        <<immutable entity>>
        +UUID id
        +UUID accountId
        +AccountNumber accountNumber
        +long sequence
        +UUID transactionId
        +Direction direction
        +Money amount
        +Money balanceAfter
        +Instant occurredAt
        +String reference
        +UUID reversalOf
        +signedAmount() Money
        +samePosting(other) boolean
    }
    class FundTransfer {
        <<entity>>
        +UUID id
        +String requestId
        +AccountNumber source
        +AccountNumber destination
        +Money amount
        +Instant completedAt
        +Status status
        +UUID reversalOf
        +markReversed() FundTransfer
        +matches(from, to, value, reversal) boolean
    }
    class BankSnapshot {
        <<immutable aggregate state>>
        +Map accounts
        +Map transfers
        +account(number) BankAccount
        +request(requestId) Optional
        +validateTransition(before, after) void
    }
    class BankRepository {
        <<interface>>
        +read() BankSnapshot
        +transact(operation) T
    }
    class InMemoryBankRepository {
        -BankSnapshot state
        +read() BankSnapshot
        +transact(operation) T
    }
    class AtomicFileBankRepository {
        -Path path
        +read() BankSnapshot
        +transact(operation) T
        -load() BankSnapshot
        -save(state) void
    }
    class BankService {
        -BankRepository repository
        -TransferProbe probe
        +openAccount(number, limit) BankAccount
        +deposit(number, amount, reference) BankAccount
        +withdraw(number, amount, reference) BankAccount
        +freeze(number) BankAccount
        +unfreeze(number) BankAccount
        +close(number) BankAccount
        +transfer(from, to, amount, requestId) FundTransfer
        +reverseTransfer(originalId, requestId) FundTransfer
    }
    class BankingCli {
        +main(args) void
    }
    BankAccount "1" *-- "1" AccountNumber
    BankAccount "1" *-- "1" Money : balance
    BankAccount "1" *-- "1" OverdraftLimit
    BankAccount "1" *-- "0..*" LedgerEntry
    BankAccount --> AccountStatus
    OverdraftLimit --> Money
    LedgerEntry --> Money
    FundTransfer --> Money
    FundTransfer --> AccountNumber : source and destination
    BankSnapshot "1" *-- "0..*" BankAccount
    BankSnapshot "1" *-- "0..*" FundTransfer
    BankService --> BankRepository
    BankService --> BankSnapshot
    BankRepository <|.. InMemoryBankRepository
    BankRepository <|.. AtomicFileBankRepository
    BankRepository --> BankSnapshot
    BankingCli --> BankService
```

All record components shown as public accessors are immutable values, not writable public fields. Account identity remains stable while operations return new versions. Ledger-entry and transfer equality follows ID; value-object equality follows canonical contents.

## Account lifecycle

```mermaid
stateDiagram-v2
    [*] --> ACTIVE : open with zero balance
    ACTIVE --> FROZEN : freeze
    FROZEN --> ACTIVE : unfreeze
    ACTIVE --> CLOSED : close only if balance = 0
    CLOSED --> [*]
    note right of ACTIVE
        Positive credits/debits permitted.
        Every debit respects overdraft.
    end note
    note right of FROZEN
        Positive incoming credits allowed by policy.
        All outgoing debits rejected.
        Must unfreeze before closing.
    end note
    note right of CLOSED
        Terminal: no financial postings.
        Cannot freeze, unfreeze or reopen.
    end note
```

## Same-bank transfer and failure boundary

```mermaid
sequenceDiagram
    actor Caller
    participant Service as BankService
    participant Repo as BankRepository
    participant A as Account A
    participant B as Account B
    participant Disk as Atomic bank snapshot
    Caller->>Service: transfer(A, B, INR 5000, requestId)
    Service->>Repo: transact(callback)
    Repo->>Repo: acquire lock and read latest state
    Repo->>Service: execute callback with immutable snapshot
    Service->>Service: check request ID and payload
    Service->>A: post(DEBIT, amount, transferId)
    A-->>Service: candidate A with debit entry
    Note over Service,Disk: Failure here publishes nothing; old A/B remain committed
    Service->>B: post(CREDIT, amount, transferId)
    B-->>Service: candidate B with credit entry
    Service-->>Repo: complete validated candidate and transfer
    Repo->>Repo: enforce append-only transition rules
    Repo->>Disk: write complete temporary snapshot and force file
    Repo->>Disk: atomic rename and force directory
    Repo-->>Service: committed result
    Service-->>Caller: completed transfer
    Note over Caller,Disk: If reply is lost, retry SAME request ID; return original result
```

If a validation exception occurs before commit, the callback exits and the repository does not publish the candidate. A failure after rename may be an uncertain outcome; idempotent retry, not a blind second instruction, resolves it.

## Production cross-bank conceptual sequence

This last diagram is a **proposed integration design**, not implemented network functionality.

```mermaid
sequenceDiagram
    participant A as Sending bank
    participant Outbox as Durable outbox
    participant Rail as Settlement network
    participant B as Receiving bank
    A->>A: reserve/debit into clearing and persist instruction
    A->>Outbox: write stable end-to-end ID in same local transaction
    Outbox->>Rail: dispatch and retry same instruction ID
    Rail->>B: idempotent receive/credit
    B-->>Rail: authenticated outcome
    Rail-->>A: outcome or pending status
    alt Confirmed success
        A->>A: finalize local transfer and clearing entries
    else Definitive rejection
        A->>A: compensate/release reservation exactly once
    else Timeout or unknown
        A->>Rail: query and reconcile; keep pending
    end
```
