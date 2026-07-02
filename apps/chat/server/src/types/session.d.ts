/**
 * Augment express-session's SessionData with our application-specific fields.
 */
import 'express-session';

declare module 'express-session' {
  interface SessionData {
    user?: {
      id: string;
      username: string;
      email: string;
      roles: string[];
      /** The OIDC access token forwarded verbatim to the Conduit gateway. */
      token: string;
    };
    /** PKCE code_verifier stored between /login and /callback */
    codeVerifier?: string;
    /** OAuth state parameter stored between /login and /callback */
    state?: string;
  }
}
