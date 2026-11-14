import {api} from './api.mjs';
import {authStore, authenticate, authRoute} from './auth.mjs';
import {remoteSql, remoteAccountStore} from './libsql.mjs';

const response = (message, status) => new Response(JSON.stringify({error:message}), {status,headers:{'Content-Type':'application/json','Cache-Control':'no-store'}});

/** Shared route dispatch, tested against a real SQLite engine via a libSQL-shaped adapter. */
export function createVercelApplication(client) {
  const auth = authStore(remoteSql(client));
  const accounts = remoteAccountStore(client);
  return async function fetch(request, ip = 'unknown') {
    try {
      if (new URL(request.url).pathname.startsWith('/api/auth/')) return authRoute(request, auth, ip);
      const user = await authenticate(request, auth);
      return api(request, accounts, user?.id, user);
    } catch {
      return response('The database service is temporarily unavailable. Please try again.', 503);
    }
  };
}

/** Classic Vercel Node function bridge; the rewrite supplies the original API path. */
export function createNodeHandler(getApplication) {
  return async function handler(req, res) {
    try {
      const host = req.headers.host;
      if (typeof host !== 'string' || !/^[a-zA-Z0-9.:-]+$/.test(host)) {
        res.statusCode = 400; res.end('Invalid host'); return;
      }
      const forwardedUrl = new URL(req.url, 'https://' + host);
      const route = req.query?.route ?? forwardedUrl.searchParams.get('route');
      if (typeof route !== 'string' || !/^[a-zA-Z0-9/_-]+$/.test(route) || route.includes('..')) {
        res.statusCode = 404; res.end('Not found'); return;
      }
      const headers = new Headers();
      for (const [key,value] of Object.entries(req.headers)) if (value !== undefined) headers.set(key,Array.isArray(value)?value.join(','):value);
      const limit = 8192;
      let body;
      if (!['GET','HEAD'].includes(req.method)) {
        if (req.body !== undefined) body = typeof req.body === 'string' ? req.body : Buffer.isBuffer(req.body) ? req.body.toString('utf8') : JSON.stringify(req.body);
        else {
          const chunks=[]; let bytes=0;
          for await (const chunk of req) {bytes+=Buffer.byteLength(chunk); if(bytes>limit){res.statusCode=413;res.end('Request is too large');return}chunks.push(chunk)}
          body=Buffer.concat(chunks.map(c=>Buffer.isBuffer(c)?c:Buffer.from(c))).toString('utf8');
        }
        if (Buffer.byteLength(body)>limit) {res.statusCode=413;res.end('Request is too large');return}
      }
      const request = new Request('https://' + host + '/api/' + route,{method:req.method,headers,...(body===undefined?{}:{body})});
      const application = await getApplication();
      const ip = String(req.headers['x-vercel-forwarded-for'] || req.headers['x-forwarded-for'] || req.socket?.remoteAddress || 'unknown').split(',')[0].trim();
      const result = await application(request, ip);
      res.statusCode = result.status;
      result.headers.forEach((value,key)=>res.setHeader(key,value));
      res.end(await result.text());
    } catch {
      res.statusCode = 503;
      res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');
      res.end(JSON.stringify({error:'The payment service is not configured or is temporarily unavailable. Please try again later.'}));
    }
  };
}
