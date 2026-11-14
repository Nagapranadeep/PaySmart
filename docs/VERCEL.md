# Deploy PaySmart to Vercel

This repository includes a Vercel Node.js API adapter and static frontend configuration. The existing Cloudflare Sites deployment and local SQLite server remain supported. The separate Java banking module is a CLI/library and does not run inside Vercel's Node functions.

## Requirements

- Access to the Vercel account/team that will own the project.
- GitHub repository: `Nagapranadeep/PaySmart`.
- A persistent **Turso/libSQL remote database**. Create one directly in Turso or through an available Vercel Marketplace integration. Local SQLite files cannot persist in Vercel functions.

## Project configuration

Import the existing GitHub repository into Vercel. Use:

| Setting | Value |
| --- | --- |
| Root directory | Repository root |
| Framework preset | Other / no framework |
| Node.js | 24.x |
| Install command | `npm ci` |
| Build command | `npm run build:vercel` |
| Output directory | `public` |

`vercel.json` defines the output, API rewrites, login/register rewrites and headers. Requests such as `/api/auth/login` are routed through `api/index.mjs` to the same domain and authentication services used by the existing app.

## Database secrets

Set these in Vercel **Project Settings → Environment Variables**, using the remote database's URL and a read/write database token:

```text
TURSO_DATABASE_URL=libsql://your-database.your-region.turso.io
TURSO_AUTH_TOKEN=<database-token>
```

Keep the token in Vercel's secret settings, not Git, chat, query strings or frontend code. Select the environments where the app should run. Use a separate database for previews if you enable preview deployments, so preview operations do not share production wallet records.

Builds apply the checked-in SQL migrations, inside database transactions, with stored checksums. The build intentionally fails when the remote database is missing or cannot be migrated. It does not publish a frontend that silently relies on temporary wallet data. Existing migrations must not be edited after they are applied.

## Publish

After the Vercel connection and environment variables are configured, deploy the repository's `main` branch. A connected Git integration can redeploy future pushes automatically. Alternatively, an authenticated Vercel CLI can run:

```bash
vercel --prod
```

This command requires an account session; GitHub access alone does not authorize a Vercel deployment. Do not paste access tokens into chat.

## Verify the deployed application

1. Open the returned **actual Vercel deployment URL**; do not infer a URL from the project name.
2. Create a test account, then sign out and sign back in.
3. Make a sandbox merchant payment using code `123456`.
4. Refresh and verify the payment and balance persist.
5. Open the receipt and issue a sandbox refund.
6. Generate an external UPI payment link; confirm the sandbox balance does not change.
7. Confirm the database report says the new journal is balanced.

Real UPI handoffs continue to require an installed UPI app. The project has no live bank settlement or provider callback integration.

## Storage and migration boundaries

Vercel uses the configured remote database; the original Sites app uses D1 and the local server uses its local SQLite file. This setup does not automatically copy accounts or transaction records between those databases. The original records are not deleted. To migrate existing records, plan an explicit, verified migration rather than copying temporary function files.

## Commands and files

```bash
npm ci
npm test
# Requires the two database variables in the shell:
npm run db:migrate:vercel
npm run build:vercel
```

- `api/index.mjs`: Vercel entry point, with lazy remote-database connection.
- `server/vercel.mjs`: Node request bridge, routing, shared auth/API dispatch.
- `server/libsql.mjs`: remote SQL and compare-and-swap account storage.
- `scripts/migrate-libsql.mjs`: transactional, checksum-verified migrations.
- `tests/vercel.test.mjs`: remote-store adapter/migration tests against a real SQLite-compatible libSQL engine, plus Node bridge checks. These tests do not by themselves establish that a Vercel deployment exists.
