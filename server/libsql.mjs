import {createClient} from '@libsql/client/web';

/** Remote SQLite-compatible storage. Never use a Vercel function's temporary disk for records. */
export function createRemoteDatabase(environment = process.env) {
  const url = environment.TURSO_DATABASE_URL;
  const authToken = environment.TURSO_AUTH_TOKEN;
  if (!url || !authToken) throw new Error('Persistent database is not configured. Set TURSO_DATABASE_URL and TURSO_AUTH_TOKEN.');
  const parsed = new URL(url);
  if (!['libsql:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password)
    throw new Error('TURSO_DATABASE_URL must be a remote libsql:// or https:// URL without embedded credentials.');
  return createClient({url, authToken});
}
export function remoteSql(client) {
  return {
    first: async (sql, ...args) => (await client.execute({sql, args})).rows[0] || null,
    run: async (sql, ...args) => client.execute({sql, args})
  };
}
export function remoteAccountStore(client) {
  return {
    init: (id, state) => client.execute({sql:'INSERT OR IGNORE INTO accounts (id,state,version) VALUES (?,?,0)',args:[id,state]}),
    read: async id => (await client.execute({sql:'SELECT state,version FROM accounts WHERE id=?',args:[id]})).rows[0] || null,
    cas: async (id, version, state) => (await client.execute({sql:'UPDATE accounts SET state=?,version=version+1 WHERE id=? AND version=?',args:[state,id,version]})).rowsAffected === 1
  };
}
