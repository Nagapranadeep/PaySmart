const enc = new TextEncoder();
const hex = bytes => Array.from(new Uint8Array(bytes), b => b.toString(16).padStart(2,'0')).join('');
const random = () => hex(crypto.getRandomValues(new Uint8Array(32)));
const digest = async value => hex(await crypto.subtle.digest('SHA-256', enc.encode(value)));
const authJson = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), {status,headers:{'Content-Type':'application/json','Cache-Control':'no-store',...headers}});
async function passwordHash(password, salt) {
  const key = await crypto.subtle.importKey('raw', enc.encode(password), 'PBKDF2', false, ['deriveBits']);
  return hex(await crypto.subtle.deriveBits({name:'PBKDF2', salt:enc.encode(salt), iterations:100000, hash:'SHA-256'}, key, 256));
}
function equal(a,b) { let difference = a.length ^ b.length; for(let i=0;i<a.length;i++) difference |= a.charCodeAt(i) ^ (b.charCodeAt(i)||0); return difference === 0; }
const sessionCookie = (request,token,age=604800) => `paysmart_auth=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${age}${new URL(request.url).protocol==='https:'?'; Secure':''}`;
export function authStore(db) {
  return {
    userByEmail: email => db.first('SELECT * FROM users WHERE email = ?',email),
    createUser: user => db.run('INSERT INTO users (id,email,name,password_hash,salt,created_at) VALUES (?,?,?,?,?,?)',user.id,user.email,user.name,user.hash,user.salt,Date.now()),
    createSession: (hash,userId,expiry) => db.run('INSERT INTO sessions (token_hash,user_id,expires_at) VALUES (?,?,?)',hash,userId,expiry),
    session: hash => db.first('SELECT users.id,users.email,users.name,sessions.expires_at FROM sessions JOIN users ON users.id=sessions.user_id WHERE token_hash = ?',hash),
    deleteSession: hash => db.run('DELETE FROM sessions WHERE token_hash = ?',hash),
    prune: now => db.run('DELETE FROM sessions WHERE expires_at < ?',now),
    attempt: async (id,now) => {await db.run('INSERT INTO auth_attempts (id,count,reset_at) VALUES (?,1,?) ON CONFLICT(id) DO UPDATE SET count=CASE WHEN reset_at < ? THEN 1 ELSE count+1 END, reset_at=CASE WHEN reset_at < ? THEN ? ELSE reset_at END',id,now+900000,now,now,now+900000);return db.first('SELECT count FROM auth_attempts WHERE id = ?',id)}
  };
}
export async function authenticate(request, store) {
  const token = request.headers.get('Cookie')?.match(/(?:^|;\s*)paysmart_auth=([a-f0-9]{64})(?:;|$)/)?.[1];
  if (!token) return null;
  const hash = await digest(token), user = await store.session(hash);
  if (!user || user.expires_at < Date.now()) return null;
  return {id:user.id, email:user.email, name:user.name, hash};
}
export async function authRoute(request, store, ip='unknown') {
  const url = new URL(request.url), path = url.pathname;
  try {
    if (request.method==='GET' && path==='/api/auth/me') {const user=await authenticate(request,store);return user?authJson({user:{id:user.id,email:user.email,name:user.name}}):authJson({user:null},401)}
    if (request.method!=='POST') return authJson({error:'Endpoint not found.'},404);
    if (request.headers.get('Origin') && request.headers.get('Origin')!==url.origin) return authJson({error:'Cross-origin requests are not allowed.'},403);
    if (path==='/api/auth/logout') {const user=await authenticate(request,store);if(user)await store.deleteSession(user.hash);return authJson({ok:true},200,{'Set-Cookie':sessionCookie(request,'',0)})}
    if (!['/api/auth/register','/api/auth/login'].includes(path)) return authJson({error:'Endpoint not found.'},404);
    const text=await request.text();if(text.length>4096)return authJson({error:'Request is too large.'},413);
    let body;try{body=JSON.parse(text)}catch{return authJson({error:'Invalid request.'},400)}
    const email=typeof body?.email==='string'?body.email.trim().toLowerCase():'';
    if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)||email.length>254||typeof body?.password!=='string'||body.password.length>128)return authJson({error:'Enter a valid email and password.'},400);
    const rate=await store.attempt(await digest(ip+'|'+email),Date.now());
    const ipRate=await store.attempt(await digest('ip|'+ip),Date.now());
    if(rate.count>8||ipRate.count>40)return authJson({error:'Too many attempts. Try again in 15 minutes.'},429,{'Retry-After':'900'});
    let user=await store.userByEmail(email);
    if(path==='/api/auth/register') {
      const name=typeof body.name==='string'?body.name.trim():'';
      if(!name||name.length>80)return authJson({error:'Enter a name between 1 and 80 characters.'},400);
      if(body.password.length<12)return authJson({error:'Use a password with at least 12 characters.'},400);
      if(user)return authJson({error:'An account with this email already exists. Please sign in.'},409);
      const salt=random();user={id:crypto.randomUUID(),email,name,salt,hash:await passwordHash(body.password,salt)};
      await store.createUser(user);
    } else {
      // Dummy derivation keeps password work comparable for an unknown account.
      const computed=await passwordHash(body.password,user?.salt||'0'.repeat(64));
      if(!user||!equal(computed,user.password_hash))return authJson({error:'Email or password is incorrect.'},401);
    }
    const token=random();await store.prune(Date.now());await store.createSession(await digest(token),user.id,Date.now()+604800000);
    return authJson({user:{id:user.id,email:user.email,name:user.name}},path==='/api/auth/register'?201:200,{'Set-Cookie':sessionCookie(request,token)});
  } catch {return authJson({error:'Account service is temporarily unavailable. Please try again.'},503)}
}
