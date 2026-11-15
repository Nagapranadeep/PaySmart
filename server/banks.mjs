import {DomainError} from './models.mjs';

/** College-only bank-linking simulator. Never calls a bank or sends an SMS. */
export const BANK_CATALOG = Object.freeze([
  {id:'hdfc',name:'HDFC Bank',shortName:'HDFC',color:'#6b96ec',ifsc:'HDFC0001234'},
  {id:'sbi',name:'State Bank of India',shortName:'SBI',color:'#68badc',ifsc:'SBIN0001234'},
  {id:'icici',name:'ICICI Bank',shortName:'ICICI',color:'#dd9b6c',ifsc:'ICIC0001234'}
]);
function catalogBank(id){const bank=BANK_CATALOG.find(b=>b.id===id);if(!bank)throw new DomainError('Choose HDFC, SBI or ICICI from the demo bank list.');return bank}
function discoveredAccounts(bankId){const bank=catalogBank(bankId);return [
  {id:bankId+'-savings',bankId,bankName:bank.name,accountType:'Savings',last4:({hdfc:'4829',sbi:'7104',icici:'6251'})[bankId],ifsc:bank.ifsc},
  {id:bankId+'-current',bankId,bankName:bank.name,accountType:'Current',last4:({hdfc:'9632',sbi:'2836',icici:'8417'})[bankId],ifsc:bank.ifsc}
]}
export function bankMutation(state,path,input,now=Date.now()){
  const next=structuredClone(state);next.bankAccounts??=[];next.bankChallenges??=[];
  if(path==='/api/banks/start'){
    if(input.consent!==true)throw new DomainError('Confirm that you are using fictitious demo accounts.');
    const bank=catalogBank(input.bankId);
    next.bankChallenges=next.bankChallenges.filter(c=>c.expiresAt>now).slice(-9);
    const challenge={id:crypto.randomUUID(),bankId:bank.id,status:'pending',attempts:0,issuedAt:now,expiresAt:now+300000};
    next.bankChallenges.push(challenge);
    return {state:next,result:{challengeId:challenge.id,bank,expiresAt:challenge.expiresAt,demoOtp:'123456',demoPhone:'+91 90000 00001',mode:'college_demo',smsSent:false}};
  }
  if(path==='/api/banks/verify'||path==='/api/banks/link'){
    const challenge=next.bankChallenges.find(c=>c.id===input.challengeId);
    if(!challenge)throw new DomainError('Demo verification session not found. Start again.',404);
    if(challenge.expiresAt<=now)throw new DomainError('Demo verification expired. Select the bank again.',410);
    if(path==='/api/banks/verify'){
      if(challenge.status==='linked')throw new DomainError('This demo confirmation has already been used.',409);
      if(challenge.attempts>=5)throw new DomainError('Demo attempt limit reached. Start a new confirmation.',429);
      if(input.code!=='123456'){
        challenge.attempts++;
        return {state:next,status:422,error:`Incorrect demo code. ${5-challenge.attempts} attempts remaining.`,result:{attemptsRemaining:5-challenge.attempts}};
      }
      challenge.status='confirmed';
      return {state:next,result:{challengeId:challenge.id,mode:'college_demo',accounts:discoveredAccounts(challenge.bankId)}};
    }
    if(challenge.status==='linked'){
      if(challenge.linkedAccountId!==input.accountId)throw new DomainError('This confirmation was used for another demo account.',409);
      const existing=next.bankAccounts.find(a=>a.id===input.accountId);
      if(!existing)throw new DomainError('This demo account was removed. Start a new confirmation.',409);
      return {state:next,result:existing,status:200};
    }
    if(challenge.status!=='confirmed')throw new DomainError('Complete the demo OTP confirmation before selecting an account.',409);
    const selected=discoveredAccounts(challenge.bankId).find(a=>a.id===input.accountId);
    if(!selected)throw new DomainError('Choose an account returned by this demo bank.',400);
    let account=next.bankAccounts.find(a=>a.id===selected.id);
    if(!account){account={...selected,status:'demo_linked',mode:'college_demo',isDefault:next.bankAccounts.length===0,linkedAt:new Date(now).toISOString()};next.bankAccounts.push(account)}
    challenge.status='linked';challenge.linkedAccountId=account.id;
    return {state:next,result:account};
  }
  if(path==='/api/banks/default'||path==='/api/banks/unlink'){
    const account=next.bankAccounts.find(a=>a.id===input.accountId);
    if(!account)throw new DomainError('Demo bank account not found.',404);
    if(path==='/api/banks/default'){
      next.bankAccounts=next.bankAccounts.map(a=>({...a,isDefault:a.id===account.id}));
      return {state:next,result:next.bankAccounts.find(a=>a.id===account.id)};
    }
    next.bankAccounts=next.bankAccounts.filter(a=>a.id!==account.id);
    if(account.isDefault&&next.bankAccounts.length)next.bankAccounts[0].isDefault=true;
    return {state:next,result:{removedAccountId:account.id}};
  }
  throw new DomainError('Bank demo endpoint not found.',404);
}
