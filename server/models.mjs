/** Domain interfaces are enforced by abstract base classes at runtime. */
export class DomainError extends Error {
  constructor(message, status = 400) { super(message); this.name = 'DomainError'; this.status = status; }
}
export class WalletAccount {
  #balance;
  constructor(id, balance) {
    if (!Number.isSafeInteger(balance) || balance < 0) throw new DomainError('Invalid wallet balance.');
    this.id = id; this.#balance = balance;
  }
  get balance() { return this.#balance; }
  validateAmount(amount) {
    if (!Number.isSafeInteger(amount) || amount <= 0) throw new DomainError('Invalid payment amount.');
  }
  debit(amount) {
    this.validateAmount(amount);
    if (amount > this.#balance) throw new DomainError('The funding wallet has insufficient funds.', 409);
    this.#balance -= amount;
  }
  credit(amount) {
    this.validateAmount(amount);
    if (!Number.isSafeInteger(this.#balance + amount)) throw new DomainError('Wallet limit exceeded.', 409);
    this.#balance += amount;
  }
}
export class PersonalAccount extends WalletAccount {
  validateAmount(amount) {
    super.validateAmount(amount);
    if (amount > 10000000) throw new DomainError('The per-transaction limit is ₹1,00,000.');
  }
}
export class MerchantAccount extends WalletAccount {
  constructor(id, balance) { super(id, balance); this.accountType = 'merchant'; }
}
export class SettlementAccount extends WalletAccount {
  constructor(id, balance) { super(id, balance); this.accountType = 'sandbox_settlement'; }
}
export class PaymentMethod {
  prepare() { throw new DomainError('A payment method must implement prepare().'); }
}
export class WalletTransfer extends PaymentMethod {
  prepare({name}) {
    const counterparty = ({'priya@paysmart':'priya','rahul@paysmart':'rahul'})[name.toLowerCase()];
    if (!counterparty) throw new DomainError('Use priya@paysmart or rahul@paysmart for a sandbox wallet transfer. Use UPI for a real UPI ID.');
    return {counterparty, display:counterparty === 'priya' ? 'Priya Sharma' : 'Rahul Verma', incoming:false};
  }
}
export class MerchantPayment extends PaymentMethod {
  prepare({name}) {
    const counterparty = ({'swiggy':'swiggy','amazon india':'amazon','starbucks':'starbucks','bigbasket':'bigbasket','zomato':'zomato','dmart':'dmart'})[name.toLowerCase()];
    if (!counterparty) throw new DomainError('Choose a registered demo merchant.');
    return {counterparty, display:name, incoming:false};
  }
}
export class BillPayment extends PaymentMethod {
  prepare({name}) { return {counterparty:'biller', display:name, incoming:false}; }
}
export class MobileRecharge extends BillPayment {
  prepare(input) {
    if (!/^[6-9]\d{9}$/.test(input.name)) throw new DomainError('Enter a valid 10-digit Indian mobile number.');
    return super.prepare(input);
  }
}
export class BankTopUp extends PaymentMethod {
  prepare() { return {counterparty:'bank', display:'Wallet top-up', incoming:true}; }
}
export class RefundPayment extends PaymentMethod {
  prepare({state, originalId, amount}) {
    const original = state.transactions.find(t => t.id === originalId);
    if (!original || original.type !== 'merchant' || original.status !== 'Completed' || original.amount !== amount)
      throw new DomainError('Only completed merchant payments can be refunded once.', 409);
    return {counterparty:original.counterparty, display:original.name + ' refund', incoming:true, original};
  }
}
export class ExternalUPIPayment extends PaymentMethod {
  prepare({name, amount, payeeName, id}) {
    if (!/^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z][a-zA-Z0-9.\-]{1,63}$/.test(name)) throw new DomainError('Enter a valid UPI ID.');
    if (payeeName !== undefined && typeof payeeName !== 'string') throw new DomainError('Enter a valid payee name.');
    return {uri:'upi://pay?' + new URLSearchParams({pa:name, pn:payeeName?.trim().slice(0,80) || name, am:(amount/100).toFixed(2), cu:'INR', tn:'PaySmart payment', tr:id}).toString()};
  }
}
export class PaymentProcessor {
  #methods = new Map();
  register(type, method) {
    if (!(method instanceof PaymentMethod)) throw new TypeError('Payment methods must implement the PaymentMethod contract.');
    this.#methods.set(type, method); return this;
  }
  prepare(type, input) {
    const strategy = this.#methods.get(type);
    if (!strategy) throw new DomainError('Unsupported transaction type.');
    return strategy.prepare(input);
  }
}
export const processor = new PaymentProcessor()
  .register('transfer', new WalletTransfer()).register('merchant', new MerchantPayment())
  .register('recharge', new MobileRecharge()).register('bill', new BillPayment())
  .register('topup', new BankTopUp()).register('refund', new RefundPayment())
  .register('upi_intent', new ExternalUPIPayment());
