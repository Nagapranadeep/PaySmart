import {createRemoteDatabase} from '../server/libsql.mjs';
import {migrate} from './migrate-libsql.mjs';
import {readFileSync} from 'node:fs';
const client=createRemoteDatabase();
try {
  await migrate(client);
  for(const asset of ['index.html','style.css','app.js','upi.js','vendor/qrcode.js','vendor/jsQR.js'])readFileSync(new URL('../public/'+asset,import.meta.url));
  console.log('PaySmart frontend and persistent Vercel database are ready.');
} finally {client.close()}
