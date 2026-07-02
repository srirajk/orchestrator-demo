import { Router, Request, Response } from 'express';
import { requireAuth } from '../middleware/requireAuth';

export const meRouter = Router();

/**
 * GET /api/me
 * Returns the authenticated user's public profile.
 * Returns 401 if not logged in.
 */
meRouter.get('/', requireAuth, (req: Request, res: Response) => {
  const { id, username, email, roles } = req.session.user!;
  res.json({ id, username, email, roles });
});
