import http from 'node:http';
import {DatabaseSync} from 'node:sqlite';
import {mkdirSync,readFileSync,readdirSync} from 'node:fs';
import {resolve,extname} from 'node:path';
import {api} from './api.mjs';
import {authStore,authenticate,authRoute} from './auth.mjs';
const root=resolve(import.meta.dirname,'..');
const data=process.env.PAYSMART_DATA_DIR||resolve(root,'data');mkdirSync(data,{recursive:true});
const db=new DatabaseSync(resolve(data,'paysmart.sqlite'));
db.exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON; CREATE TABLE IF NOT EXISTS schema_migrations (name TEXT PRIMARY KEY)');
for(const file of readdirSync(resolve(root,'drizzle')).filter(f=>f.endsWith('.sql')).sort()){
  if(db.prepare('SELECT name FROM schema_migrations WHERE name=?').get(file))continue;
  db.exec('BEGIN IMMEDIATE');
  try{let sql=readFileSync(resolve(root,'drizzle',file),'utf8');if(file==='0000_wallets.sql')sql=sql.replace('CREATE TABLE accounts','CREATE TABLE IF NOT EXISTS accounts');db.exec(sql);db.prepare('INSERT INTO schema_migrations(name) VALUES (?)').run(file);db.exec('COMMIT')}catch(e){db.exec('ROLLBACK');throw e}
}
const store={init:(id,state)=>db.prepare('INSERT OR IGNORE INTO accounts (id,state,version) VALUES (?,?,0)').run(id,state),read:id=>db.prepare('SELECT state,version FROM accounts WHERE id=?').get(id),cas:(id,version,state)=>db.prepare('UPDATE accounts SET state=?,version=version+1 WHERE id=? AND version=?').run(state,id,version).changes===1};
const auth=authStore({first:async(sql,...args)=>db.prepare(sql).get(...args),run:async(sql,...args)=>db.prepare(sql).run(...args)});
const mime={'.html':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.svg':'image/svg+xml','.txt':'text/plain; charset=utf-8'};
const server=http.createServer(async(req,res)=>{
 try{
  const url=new URL(req.url,'http://'+req.headers.host);
  if(url.pathname.startsWith('/api/')){
    let body='',size=0;for await(const chunk of req){size+=chunk.length;if(size>8192){res.writeHead(413);res.end();return}body+=chunk}
    const headers=new Headers();for(const[k,v]of Object.entries(req.headers))if(v)headers.set(k,Array.isArray(v)?v.join(','):v);
    const request=new Request(url,{method:req.method,headers,...(!['GET','HEAD'].includes(req.method)?{body}:{})});
    let response;
    if(url.pathname.startsWith('/api/auth/'))response=await authRoute(request,auth,req.socket.remoteAddress);
    else{const user=await authenticate(request,auth);response=await api(request,store,user?.id,user)}
    res.writeHead(response.status,Object.fromEntries(response.headers));res.end(await response.text());return;
  }
  const name=['/','/login','/register'].includes(url.pathname)?'index.html':decodeURIComponent(url.pathname).slice(1);
  const path=resolve(root,'public',name);
  if(!path.startsWith(resolve(root,'public')+'/')){res.writeHead(403);res.end();return}
  let file;try{file=readFileSync(path)}catch{res.writeHead(404);res.end('Not found');return}
  res.writeHead(200,{'Content-Type':mime[extname(path)]||'application/octet-stream','X-Content-Type-Options':'nosniff','Referrer-Policy':'strict-origin-when-cross-origin'});res.end(file);
 }catch{res.writeHead(500);res.end('Server error')}
});
server.listen(Number(process.env.PORT)||3000,process.env.HOST||'127.0.0.1',()=>console.log('PaySmart backend listening on http://127.0.0.1:'+(Number(process.env.PORT)||3000)));
