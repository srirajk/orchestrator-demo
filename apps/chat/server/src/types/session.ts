/**
 * Augment express-session's SessionData with our application-specific fields.
 *
 * This is a real `.ts` module (NOT a `.d.ts`) on purpose: it emits a `.js`, so the runtime
 * `import './types/session'` in index.ts resolves cleanly (importing a `.d.ts` at runtime crashes
 * Node — no `.js` is emitted). The top-level `import 'express-session'` + `export {}` keep this a
 * module so the `declare module` block *augments* express-session rather than shadowing it.
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

export {};
