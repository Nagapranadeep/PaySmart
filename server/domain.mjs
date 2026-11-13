export class DomainError extends Error {constructor(message,status=400){super(message);this.status=status}}
export const rupees=n=>'₹'+(n/100).toLocaleString('en-IN',{minimumFractionDigits:2});
export const today=(now=new Date())=>now.toLocaleDateString('en-CA',{timeZone:'Asia/Kolkata'});
export function initial(){return {profile:{name:'Arjun Kumar',upi:'arjun@paysmart',country:'India'},wallets:{self:2845060,priya:5000000,rahul:3000000,swiggy:0,amazon:0,starbucks:0,bigbasket:0,zomato:0,dmart:0,biller:0,bank:100000000},received:4250000,spent:1849940,transactions:[
{id:'PS1006',name:'Swiggy',handle:'Food & dining',amount:48500,type:'merchant',date:'2026-11-13T07:45:00+05:30',status:'Completed',icon:'S',color:'#fff0e5',counterparty:'swiggy'},
{id:'PS1005',name:'Priya Sharma',handle:'Received from UPI',amount:250000,type:'received',date:'2026-11-12T18:30:00+05:30',status:'Completed',icon:'PS',color:'#f0eaf6',counterparty:'priya'},
{id:'PS1004',name:'Amazon India',handle:'Shopping',amount:249900,type:'merchant',date:'2026-11-12T14:12:00+05:30',status:'Completed',icon:'a',color:'#f4f0e9',counterparty:'amazon'},
{id:'PS1003',name:'Rahul Verma',handle:'Sent to UPI',amount:150000,type:'transfer',date:'2026-11-11T20:15:00+05:30',status:'Completed',icon:'RV',color:'#e8eff5',counterparty:'rahul'},
{id:'PS1002',name:'Jio Prepaid',handle:'Mobile recharge',amount:29900,type:'recharge',date:'2026-11-11T10:05:00+05:30',status:'Completed',icon:'Jio',color:'#eaeefa',counterparty:'biller'},
{id:'PS1001',name:'Starbucks',handle:'Food & dining',amount:38000,type:'merchant',date:'2026-11-10T09:42:00+05:30',status:'Completed',icon:'★',color:'#e9f1e8',counterparty:'starbucks'}],journal:[],intents:[]}}
const merchants={'swiggy':'swiggy','amazon india':'amazon','starbucks':'starbucks','bigbasket':'bigbasket','zomato':'zomato','dmart':'dmart'};
export function view(s){return {balance:s.wallets.self,received:s.received,spent:s.spent,profile:s.profile,transactions:s.transactions,intents:s.intents,wallets:Object.entries(s.wallets).filter(([k])=>k!=='bank').map(([id,balance])=>({id,balance})),journal:s.journal}}
export const validUpi=v=>typeof v==='string'&&/^[a-zA-Z0-9.\-_]{2,256}@[a-zA-Z][a-zA-Z0-9.\-]{1,63}$/.test(v);
export function mutate(s,input,now=new Date()){
const {type,name:raw,amount,key,code,method='wallet',originalId}=input;const name=typeof raw==='string'?raw.trim():'';
if(typeof key!=='string'||key.length<8||key.length>100)throw new DomainError('A valid idempotency key is required.');
const duplicate=s.transactions.find(t=>t.key===key)||s.intents.find(t=>t.key===key);if(duplicate){if(duplicate.type!==type||duplicate.amount!==amount||duplicate.requestName!==name||duplicate.originalId!==originalId)throw new DomainError('This key belongs to a different request.',409);return {state:s,result:duplicate,replayed:true}}
if(!Number.isSafeInteger(amount)||amount<100||amount>10000000)throw new DomainError('Enter an amount between ₹1 and ₹1,00,000, with up to two decimal places.');
if(!name||name.length>100)throw new DomainError('Enter a valid recipient.');
if(type==='upi_intent'){
 if(!validUpi(name))throw new DomainError('Enter a valid UPI ID.');
 const next=structuredClone(s),id='UPI'+crypto.randomUUID().replaceAll('-','').slice(0,16).toUpperCase();const intent={id,key,type,requestName:name,name,amount,status:'Unverified',date:now.toISOString(),uri:'upi://pay?'+new URLSearchParams({pa:name,pn:input.payeeName?.slice(0,80)||name,am:(amount/100).toFixed(2),cu:'INR',tn:'PaySmart payment',tr:id}).toString()};next.intents.unshift(intent);next.intents=next.intents.slice(0,200);return {state:next,result:intent};
}
if(code!=='123456')throw new DomainError('Use the sandbox verification code 123456.',422);
if(method!=='wallet')throw new DomainError('Unsupported sandbox payment method.');
if(!['transfer','merchant','recharge','bill','topup','refund'].includes(type))throw new DomainError('Unsupported transaction type.');
let counterparty,display=name,original;const incoming=['topup','refund'].includes(type);
if(type==='transfer'){counterparty=({'priya@paysmart':'priya','rahul@paysmart':'rahul'})[name.toLowerCase()];if(!counterparty)throw new DomainError('Wallet transfers support priya@paysmart and rahul@paysmart. Use External UPI for a real UPI ID.');display=counterparty==='priya'?'Priya Sharma':'Rahul Verma'}
if(type==='merchant'){counterparty=merchants[name.toLowerCase()];if(!counterparty)throw new DomainError('Choose a registered demo merchant.')}
if(type==='topup')counterparty='bank';
if(type==='bill')counterparty='biller';
if(type==='recharge'){if(!/^[6-9]\d{9}$/.test(name))throw new DomainError('Enter a valid 10-digit Indian mobile number.');counterparty='biller'}
if(type==='refund'){original=s.transactions.find(t=>t.id===originalId);if(!original||original.type!=='merchant'||original.status!=='Completed'||original.amount!==amount)throw new DomainError('Only completed merchant payments can be refunded once.',409);counterparty=original.counterparty;display=original.name+' refund'}
if(!incoming){if(s.wallets.self<amount)throw new DomainError('Insufficient wallet balance.',409);const daily=s.transactions.filter(t=>!['topup','refund','received'].includes(t.type)&&today(new Date(t.date))===today(now)).reduce((sum,t)=>sum+t.amount,0);if(daily+amount>10000000)throw new DomainError('The daily outgoing limit is ₹1,00,000.',409)}
const next=structuredClone(s),id='PS'+crypto.randomUUID().replaceAll('-','').slice(0,16).toUpperCase();const debit=incoming?counterparty:'self',credit=incoming?'self':counterparty;
// Opening merchant payments have historical settlement outside this sandbox ledger.
// A refund uses the sandbox settlement account when refunding opening history.
const source=type==='refund'&&!s.journal.some(j=>j.transactionId===originalId)?'bank':debit;
if(next.wallets[source]<amount)throw new DomainError('The funding wallet has insufficient funds.',409);
next.wallets[source]-=amount;next.wallets[credit]+=amount;
if(incoming)next.received+=amount;else next.spent+=amount;
if(original)next.transactions.find(t=>t.id===originalId).status='Refunded';
const tx={id,key,type,requestName:name,name:display,handle:type==='refund'?'Refund · '+originalId:type==='topup'?'Demo linked bank':type==='transfer'?'Wallet transfer':type==='merchant'?'Merchant payment':type==='recharge'?'Mobile recharge':'Bill payment',amount,date:now.toISOString(),status:'Completed',icon:display.split(' ').map(n=>n[0]).slice(0,2).join(''),color:incoming?'#e9f1e8':'#f5eee3',counterparty,originalId};
next.transactions.unshift(tx);next.journal.push({transactionId:id,wallet:source,delta:-amount},{transactionId:id,wallet:credit,delta:amount});return {state:next,result:tx};
}
