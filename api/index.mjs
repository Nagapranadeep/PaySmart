import {createRemoteDatabase} from '../server/libsql.mjs';
import {createVercelApplication, createNodeHandler} from '../server/vercel.mjs';
let application;
export default createNodeHandler(async () => {
  if (!application) application = createVercelApplication(createRemoteDatabase());
  return application;
});
