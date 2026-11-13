import {readFileSync,mkdirSync,writeFileSync,rmSync,cpSync,readdirSync} from 'node:fs';
import {resolve,relative} from 'node:path';
const root=resolve(import.meta.dirname,'..');
function collect(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?collect(resolve(dir,e.name)):[resolve(dir,e.name)])}
const files=Object.fromEntries(collect(resolve(root,'public')).map(path=>['/'+relative(resolve(root,'public'),path).replaceAll('\\','/'),readFileSync(path,'utf8')]));
files['/']=files['/index.html'];files['/login']=files['/'];files['/register']=files['/'];
const moduleSource=name=>readFileSync(resolve(root,'server',name),'utf8').replace(/^import .*?;\n/gm,'').replace(/^export \{.*?\} from .*?;\n/gm,'').replaceAll('export ','');
const modules=['models.mjs','domain.mjs','api.mjs','auth.mjs'].map(moduleSource).join('\n');
const worker=`${modules}
const assets=${JSON.stringify(files)};
export default {async fetch(request,env){
 const url=new URL(request.url);
 if(url.pathname.startsWith('/api/')){
  if(!env.DB)return new Response(JSON.stringify({error:'The database binding is not configured.'}),{status:503,headers:{'Content-Type':'application/json'}});
  const auth=authStore({first:(sql,...args)=>env.DB.prepare(sql).bind(...args).first(),run:(sql,...args)=>env.DB.prepare(sql).bind(...args).run()});
  if(url.pathname.startsWith('/api/auth/'))return authRoute(request,auth,request.headers.get('CF-Connecting-IP')||'unknown');
  const user=await authenticate(request,auth);
  return api(request,d1Store(env.DB),user?.id,user);
 }
 const file=assets[url.pathname];if(file===undefined)return new Response('Not found',{status:404});
 return new Response(file,{headers:{'Content-Type':url.pathname.endsWith('.css')?'text/css; charset=utf-8':url.pathname.endsWith('.js')?'text/javascript; charset=utf-8':url.pathname.endsWith('.txt')?'text/plain; charset=utf-8':'text/html; charset=utf-8','X-Content-Type-Options':'nosniff','Referrer-Policy':'strict-origin-when-cross-origin'}});
}};`;
rmSync(resolve(root,'dist'),{recursive:true,force:true});mkdirSync(resolve(root,'dist/server'),{recursive:true});mkdirSync(resolve(root,'dist/.openai'),{recursive:true});
writeFileSync(resolve(root,'dist/server/index.js'),worker);cpSync(resolve(root,'.openai/hosting.json'),resolve(root,'dist/.openai/hosting.json'));cpSync(resolve(root,'drizzle'),resolve(root,'dist/drizzle'),{recursive:true});
console.log('Built full-stack Worker with login, database API and embedded assets.');
