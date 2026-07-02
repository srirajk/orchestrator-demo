/**
 * Central config — all environment variables with typed defaults.
 * Nothing in the codebase should read process.env directly; import from here.
 */
export const config = {
  port: parseInt(process.env['PORT'] ?? '8095', 10),

  mongodbUri: process.env['MONGODB_URI'] ?? 'mongodb://mongodb:27017/conduitchat',

  gatewayBase: process.env['GATEWAY_BASE'] ?? 'http://gateway:8080',

  oidcIssuer: process.env['OIDC_ISSUER'] ?? 'http://iam-service:8084',
  oidcClientId: process.env['OIDC_CLIENT_ID'] ?? 'conduit-chat',
  oidcClientSecret: process.env['OIDC_CLIENT_SECRET'] ?? '',
  oidcRedirectUri:
    process.env['OIDC_REDIRECT_URI'] ?? 'http://localhost:8095/api/auth/callback',

  sessionSecret: process.env['SESSION_SECRET'] ?? 'change-me-in-production',

  chatRecentMessages: parseInt(process.env['CHAT_RECENT_MESSAGES'] ?? '8', 10),
  chatSummaryAfter: parseInt(process.env['CHAT_SUMMARY_AFTER'] ?? '12', 10),
  chatSummaryMaxTokens: parseInt(process.env['CHAT_SUMMARY_MAX_TOKENS'] ?? '150', 10),

  // MinIO — STUBBED (not implemented)
  minioEndpoint: process.env['MINIO_ENDPOINT'] ?? 'http://minio:9000',
  minioAccessKey: process.env['MINIO_ACCESS_KEY'] ?? 'minioadmin',
  minioSecretKey: process.env['MINIO_SECRET_KEY'] ?? 'minioadmin',
  minioBucket: process.env['MINIO_BUCKET'] ?? 'conduit-chat-files',

  nodeEnv: process.env['NODE_ENV'] ?? 'development',
} as const;
