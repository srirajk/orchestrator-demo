/**
 * OIDC auth routes.
 *
 * GET  /api/auth/login    — initiate PKCE flow, redirect to IAM authorize
 * GET  /api/auth/callback — exchange code, store user in session, redirect /
 * POST /api/auth/logout   — destroy session
 *
 * Token exchange is done via openid-client v5. The access_token returned by
 * the IAM service is stored verbatim in session.user.token and forwarded to
 * the Conduit gateway on every streaming request.
 *
 * Roles are read from the userinfo `roles` claim (string[]).  If the IAM
 * service places them elsewhere (e.g., inside the JWT), adapt the mapping
 * below — the rest of the code is unaffected.
 */
import { Router, Request, Response } from 'express';
import { generators, getOidcClient } from '../auth/oidc';
import { config } from '../config';

export const authRouter = Router();

authRouter.get('/login', async (req: Request, res: Response) => {
  try {
    const client = await getOidcClient();
    const codeVerifier = generators.codeVerifier();
    const codeChallenge = generators.codeChallenge(codeVerifier);
    const state = generators.state();

    req.session.codeVerifier = codeVerifier;
    req.session.state = state;

    const url = client.authorizationUrl({
      scope: 'openid profile email',
      state,
      code_challenge: codeChallenge,
      code_challenge_method: 'S256',
    });

    res.redirect(url);
  } catch (err) {
    console.error('[auth/login] OIDC discovery failed:', err);
    res.status(502).json({ error: 'OIDC provider unavailable' });
  }
});

authRouter.get('/callback', async (req: Request, res: Response) => {
  try {
    const client = await getOidcClient();
    const { codeVerifier, state } = req.session;

    if (!codeVerifier || !state) {
      res.status(400).json({ error: 'Missing session state — start login again' });
      return;
    }

    const params = client.callbackParams(req);
    const tokenSet = await client.callback(config.oidcRedirectUri, params, {
      code_verifier: codeVerifier,
      state,
    });

    const accessToken = tokenSet.access_token;
    if (!accessToken) {
      res.status(502).json({ error: 'No access token in token response' });
      return;
    }

    const userinfo = await client.userinfo(accessToken);

    const rawRoles = (userinfo as Record<string, unknown>)['roles'];
    const roles: string[] = Array.isArray(rawRoles)
      ? (rawRoles as unknown[]).filter((r): r is string => typeof r === 'string')
      : [];

    req.session.user = {
      id: userinfo.sub,
      username:
        (userinfo.preferred_username as string | undefined) ??
        (userinfo.name as string | undefined) ??
        userinfo.sub,
      email: (userinfo.email as string | undefined) ?? '',
      roles,
      token: accessToken,
    };

    // Clean up PKCE/state from session
    delete req.session.codeVerifier;
    delete req.session.state;

    res.redirect('/');
  } catch (err) {
    console.error('[auth/callback] token exchange failed:', err);
    res.status(502).json({ error: 'Authentication failed' });
  }
});

authRouter.post('/logout', (req: Request, res: Response) => {
  req.session.destroy((err) => {
    if (err) {
      console.error('[auth/logout] session destroy error:', err);
      res.status(500).json({ error: 'Logout failed' });
      return;
    }
    res.clearCookie('connect.sid');
    res.json({ ok: true });
  });
});
