import { Request, Response, NextFunction } from 'express';

/**
 * Express middleware that rejects unauthenticated requests with 401.
 * Must be applied after sessionMiddleware. After this middleware, routes
 * may safely use req.session.user! (non-null assertion).
 */
export function requireAuth(req: Request, res: Response, next: NextFunction): void {
  if (!req.session.user) {
    res.status(401).json({ error: 'Unauthorized' });
    return;
  }
  next();
}
