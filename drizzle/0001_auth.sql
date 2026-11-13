CREATE TABLE users (id TEXT PRIMARY KEY NOT NULL, email TEXT UNIQUE NOT NULL, name TEXT NOT NULL, password_hash TEXT NOT NULL, salt TEXT NOT NULL, created_at INTEGER NOT NULL);
--> statement-breakpoint
CREATE TABLE sessions (token_hash TEXT PRIMARY KEY NOT NULL, user_id TEXT NOT NULL REFERENCES users(id), expires_at INTEGER NOT NULL);
--> statement-breakpoint
CREATE INDEX sessions_user_id ON sessions(user_id);
--> statement-breakpoint
CREATE TABLE auth_attempts (id TEXT PRIMARY KEY NOT NULL, count INTEGER NOT NULL DEFAULT 0, reset_at INTEGER NOT NULL);
