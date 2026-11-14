import {readFileSync,readdirSync} from 'node:fs';
import {resolve} from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {createRemoteDatabase} from '../server/libsql.mjs';
const migrationDirectory=resolve(import.meta.dirname,'../drizzle');

/** Each migration and its checksum commit together. An applied migration may never be edited. */
export async function migrate(client) {
  await client.execute('CREATE TABLE IF NOT EXISTS paysmart_migrations (name TEXT PRIMARY KEY NOT NULL, checksum TEXT NOT NULL, applied_at INTEGER NOT NULL)');
  for (const name of readdirSync(migrationDirectory).filter(n=>n.endsWith('.sql')).sort()) {
    const sql=readFileSync(resolve(migrationDirectory,name),'utf8');
    const checksum=createHash('sha256').update(sql).digest('hex');
    const transaction=await client.transaction('write');
    try {
      const existing=(await transaction.execute({sql:'SELECT checksum FROM paysmart_migrations WHERE name=?',args:[name]})).rows[0];
      if(existing){if(existing.checksum!==checksum)throw new Error('Applied database migration has changed: '+name);await transaction.commit();continue}
      // Authored schema migrations contain simple DDL only; semicolons delimit statements.
      for(const statement of sql.replace(/^--> statement-breakpoint\s*$/gm,'').split(';').map(s=>s.trim()).filter(Boolean))await transaction.execute(statement);
      await transaction.execute({sql:'INSERT INTO paysmart_migrations(name,checksum,applied_at) VALUES (?,?,?)',args:[name,checksum,Date.now()]});
      await transaction.commit();
      console.log('Applied migration:',name);
    } catch(error) {await transaction.rollback();throw error}
    finally {transaction.close()}
  }
}
if(process.argv[1] && import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  let client;
  try {client=createRemoteDatabase();await migrate(client);console.log('Persistent database migrations are ready.');}
  catch(error){console.error('Database setup failed:',error.message);process.exitCode=1}
  finally {client?.close()}
}
