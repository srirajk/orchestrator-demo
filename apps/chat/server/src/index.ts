/**
 * Conduit Chat — BFF server
 *
 * Listens on PORT (default 8095).
 * All API routes live under /api.
 * OIDC auth routes are unauthenticated; everything else requires a session.
 */
import express from 'express';
import path from 'path';
import { config } from './config';
import { connectDb } from './db/connection';
import { sessionMiddleware } from './auth/session';
import { authRouter } from './routes/auth';
import { meRouter } from './routes/me';
import { conversationsRouter } from './routes/conversations';
import { projectsRouter } from './routes/projects';
import { filesRouter } from './routes/files';

// Pull in the session SessionData augmentation (real .ts module → emits .js → safe at runtime).
import './types/session';

async function bootstrap(): Promise<void> {
  await connectDb();

  const app = express();

  // ── Global middleware ──────────────────────────────────────────────────────
  app.use(express.json({ limit: '2mb' }));
  app.use(sessionMiddleware);

  // ── Routes ────────────────────────────────────────────────────────────────
  app.use('/api/auth', authRouter);
  app.use('/api/me', meRouter);
  app.use('/api/conversations', conversationsRouter);
  app.use('/api/projects', projectsRouter);
  app.use('/api/files', filesRouter);

  // Health check (no auth)
  app.get('/health', (_req, res) => res.json({ ok: true }));

  // ── Static web app (SPA) ──────────────────────────────────────────────────
  app.use(express.static(config.webDist));
  app.get('*', (_req, res) => res.sendFile(path.join(config.webDist, 'index.html')));

  // ── Start ─────────────────────────────────────────────────────────────────
  app.listen(config.port, () => {
    console.log(`[server] conduit-chat-server listening on :${config.port}`);
  });
}

bootstrap().catch((err) => {
  console.error('[server] fatal startup error:', err);
  process.exit(1);
});
