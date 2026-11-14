import test from 'node:test';
import assert from 'node:assert/strict';
import {createClient} from '@libsql/client';
import {migrate} from '../scripts/migrate-libsql.mjs';
import {createRemoteDatabase,remoteAccountStore} from '../server/libsql.mjs';
import {createVercelApplication,createNodeHandler} from '../server/vercel.mjs';

const req=(path,body,cookie='',origin='https://paysmart.test')=>new Request('https://paysmart.test'+path,{method:body===undefined?'GET':'POST',headers:{Cookie:cookie,Origin:origin,...(body===undefined?{}:{'Content-Type':'application/json'})},...(body===undefined?{}:{body:JSON.stringify(body)})});
async function fixture(){const client=createClient({url:'file::memory:'});await migrate(client);return {client,app:createVercelApplication(client)}}
const signup={name:'Vercel QA',email:'vercel@example.test',password:'vercel-password-test-123'};

test('Vercel requires a remote persistent database and never falls back to a file',()=>{
 assert.throws(()=>createRemoteDatabase({}),/not configured/);
 assert.throws(()=>createRemoteDatabase({TURSO_DATABASE_URL:'file:/tmp/wallet.sqlite',TURSO_AUTH_TOKEN:'test'}),/remote/);
 assert.throws(()=>createRemoteDatabase({TURSO_DATABASE_URL:'http://localhost/db',TURSO_AUTH_TOKEN:'test'}),/remote/);
});
test('libSQL migration runner is repeatable, preserves accounts and rejects changed migration history',async()=>{
 const {client}=await fixture();try{
  const store=remoteAccountStore(client);await store.init('preserved','{"balance":42}');await migrate(client);
  assert.equal((await store.read('preserved')).state,'{"balance":42}');
  assert.equal((await client.execute('SELECT count(*) AS count FROM paysmart_migrations')).rows[0].count,2);
  await client.execute("UPDATE paysmart_migrations SET checksum='invalid' WHERE name='0000_wallets.sql'");
  await assert.rejects(()=>migrate(client),/migration has changed/);
 }finally{client.close()}
});
test('Vercel adapter supports register, wallet CAS, refund, real UPI isolation and logout',async()=>{
 const {client,app}=await fixture();try{
  assert.equal((await app(req('/api/state'))).status,401);
  const registered=await app(req('/api/auth/register',signup),'test-ip');assert.equal(registered.status,201);const cookie=registered.headers.get('set-cookie');assert.match(cookie,/Secure/);
  const opening=await(await app(req('/api/state',undefined,cookie))).json();
  const input={type:'merchant',name:'Swiggy',amount:10000,key:'vercel-payment-key',code:'123456'};
  const paid=await(await app(req('/api/transactions',input,cookie))).json();assert.equal(paid.state.balance,opening.balance-10000);
  const replay=await(await app(req('/api/transactions',input,cookie))).json();assert.equal(replay.replayed,true);assert.equal(replay.state.balance,paid.state.balance);
  const store=remoteAccountStore(client);const row=await store.read((await(await app(req('/api/auth/me',undefined,cookie))).json()).user.id);assert.equal(await store.cas('missing',row.version,row.state),false);
  const intent=await(await app(req('/api/upi-intents',{name:'receiver@okaxis',amount:10000,key:'vercel-upi-key'},cookie))).json();assert.equal(intent.result.status,'Unverified');assert.equal(intent.state.balance,paid.state.balance);
  const refund=await(await app(req('/api/transactions',{type:'refund',name:'Swiggy refund',amount:10000,originalId:paid.result.id,key:'vercel-refund-key',code:'123456'},cookie))).json();assert.equal(refund.state.balance,opening.balance);
  assert.equal((await app(req('/api/auth/login',signup,'','https://other.test'))).status,403);
  await app(req('/api/auth/logout',{},cookie));assert.equal((await app(req('/api/state',undefined,cookie))).status,401);
 }finally{client.close()}
});
test('Vercel Node bridge preserves route, cookie headers, status and rejects oversized requests',async()=>{
 let seen;const handler=createNodeHandler(async()=>async request=>{seen=request;return new Response('{"ok":true}',{status:201,headers:{'Content-Type':'application/json','Set-Cookie':'test=session; Secure; HttpOnly'}})});
 const response=()=>({headers:{},setHeader(k,v){this.headers[k.toLowerCase()]=v},end(value){this.body=value}});
 const out=response();await handler({method:'POST',url:'/api/index?route=auth/login',query:{route:'auth/login'},headers:{host:'paysmart.test',origin:'https://paysmart.test','content-type':'application/json'},body:{email:'test@example.test'},socket:{}},out);
 assert.equal(new URL(seen.url).pathname,'/api/auth/login');assert.equal((await seen.json()).email,'test@example.test');assert.equal(out.statusCode,201);assert.match(out.headers['set-cookie'],/Secure/);
 const oversized=response();await handler({method:'POST',url:'/api/index',query:{route:'auth/login'},headers:{host:'paysmart.test'},body:'x'.repeat(9000)},oversized);assert.equal(oversized.statusCode,413);
 const unconfigured=response();await createNodeHandler(async()=>{throw Error('secret-value-must-not-leak')})({method:'GET',url:'/api/index',query:{route:'state'},headers:{host:'paysmart.test'}},unconfigured);assert.equal(unconfigured.statusCode,503);assert.doesNotMatch(unconfigured.body,/secret-value/);
});
