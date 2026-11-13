import {initial,view,mutate,DomainError} from './domain.mjs';
const json=(data,status=200,headers={})=>new Response(JSON.stringify(data),{status,headers:{'Content-Type':'application/json','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...headers}});
export async function api(request,store,owner,identity=null){
 try{const url=new URL(request.url);if(!owner)return json({error:'Sign in to use your wallet.'},401);if(request.method==='POST'&&request.headers.get('Origin')&&request.headers.get('Origin')!==url.origin)return json({error:'Cross-origin requests are not allowed.'},403);
 if(request.method==='GET'&&url.pathname==='/api/health')return json({ok:true,mode:'sandbox',storage:'database'});
 const opening=initial();if(identity?.name)opening.profile.name=identity.name;const seed=JSON.stringify(opening);await store.init(owner,seed);
 if(request.method==='GET'&&url.pathname==='/api/state'){const row=await store.read(owner);return json(view(JSON.parse(row.state)))}
 if(request.method==='GET'&&url.pathname==='/api/reports'){const row=await store.read(owner),s=JSON.parse(row.state);return json({balance:s.wallets.self,received:s.received,spent:s.spent,transactions:s.transactions.length,journalBalanced:s.journal.reduce((n,j)=>n+j.delta,0)===0,mode:'sandbox'})}
 if(request.method==='POST'&&['/api/transactions','/api/upi-intents','/api/profile'].includes(url.pathname)){
 const body=await request.text();if(body.length>4096)return json({error:'Request is too large.'},413);let input;try{input=JSON.parse(body)}catch{return json({error:'Invalid JSON.'},400)}if(!input||Array.isArray(input)||typeof input!=='object')return json({error:'Invalid request.'},400);
 if(url.pathname==='/api/upi-intents')input.type='upi_intent';else if(input.type==='upi_intent')return json({error:'Use the UPI intent endpoint.'},400);
 for(let retry=0;retry<8;retry++){const row=await store.read(owner),s=JSON.parse(row.state);let result,next;
 if(url.pathname==='/api/profile'){if(typeof input.name!=='string'||!input.name.trim()||input.name.length>80)throw new DomainError('Enter a name between 1 and 80 characters.');if(input.receiveUpi!==undefined&&input.receiveUpi!==''&&!/^[a-zA-Z0-9.\-_]{2,100}@[a-zA-Z][a-zA-Z0-9.\-]{1,63}$/.test(input.receiveUpi))throw new DomainError('Enter a valid personal UPI ID.');next=structuredClone(s);next.profile.name=input.name.trim();if(input.receiveUpi!==undefined)next.profile.receiveUpi=input.receiveUpi.trim();result=next.profile}else{const change=mutate(s,input);next=change.state;result=change.result;if(change.replayed)return json({state:view(next),result,replayed:true})}
 if(await store.cas(owner,row.version,JSON.stringify(next)))return json({state:view(next),result},201);
 }return json({error:'Your wallet is busy. Retry with the same transaction key.'},409);
 }return json({error:'Endpoint not found.'},404);
 }catch(e){return json({error:e instanceof DomainError?e.message:'The request could not be completed. Please try again.'},e instanceof DomainError?e.status:500)}
}
export function d1Store(db){return {init:(id,state)=>db.prepare('INSERT OR IGNORE INTO accounts (id, state, version) VALUES (?, ?, 0)').bind(id,state).run(),read:id=>db.prepare('SELECT state, version FROM accounts WHERE id = ?').bind(id).first(),cas:async(id,version,state)=>{const r=await db.prepare('UPDATE accounts SET state = ?, version = version + 1 WHERE id = ? AND version = ?').bind(state,id,version).run();return r.meta.changes===1}}}
