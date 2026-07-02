import session from 'express-session';
import MongoStore from 'connect-mongo';
import { config } from '../config';

/**
 * Configured express-session middleware backed by MongoDB via connect-mongo.
 * Import and mount this before all authenticated routes.
 */
export const sessionMiddleware = session({
  secret: config.sessionSecret,
  resave: false,
  saveUninitialized: false,
  store: MongoStore.create({
    mongoUrl: config.mongodbUri,
    collectionName: 'sessions',
    ttl: 24 * 60 * 60, // 1 day in seconds
  }),
  cookie: {
    httpOnly: true,
    // Secure cookies are dropped over http. The demo runs on http://localhost, so default OFF
    // and gate on an explicit env — set COOKIE_SECURE=true only when served behind https/TLS.
    secure: process.env['COOKIE_SECURE'] === 'true',
    sameSite: 'lax',
    maxAge: 24 * 60 * 60 * 1000, // 1 day in ms
  },
});
