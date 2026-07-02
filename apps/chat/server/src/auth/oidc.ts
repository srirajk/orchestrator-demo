/**
 * openid-client v5 singleton.
 *
 * We lazy-initialise the OIDC client so the server can boot even when the
 * IAM service is temporarily unavailable (Kubernetes readiness pattern).
 * The first login request will perform discovery; after that the Client
 * is cached for the lifetime of the process.
 *
 * STUBBED: The token exchange is fully implemented via openid-client v5.
 * If the IAM service does not expose a standard OIDC discovery document
 * at OIDC_ISSUER/.well-known/openid-configuration, Issuer.discover() will
 * throw and the /login route will return 502 — nothing is swallowed silently.
 */
import { Issuer, Client, generators } from 'openid-client';
import { config } from '../config';

export { generators };

let _client: Client | null = null;

export async function getOidcClient(): Promise<Client> {
  if (_client) return _client;

  const issuer = await Issuer.discover(config.oidcIssuer);
  _client = new issuer.Client({
    client_id: config.oidcClientId,
    client_secret: config.oidcClientSecret,
    redirect_uris: [config.oidcRedirectUri],
    response_types: ['code'],
  });

  return _client;
}
