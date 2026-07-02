# ── Stage 1: Build web (Vite/React → dist/) ─────────────────────────────────
FROM node:20-alpine AS web-builder

WORKDIR /build/web
COPY web/package*.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# ── Stage 2: Build server (TypeScript → dist/) ───────────────────────────────
FROM node:20-alpine AS server-builder

WORKDIR /build/server
COPY server/package*.json ./
RUN npm ci --include=dev
COPY server/tsconfig.json ./
COPY server/src ./src
RUN npm run build

# ── Stage 3: Runtime ──────────────────────────────────────────────────────────
FROM node:20-alpine AS runtime

WORKDIR /app

# Install server production dependencies
COPY server/package*.json ./
RUN npm ci --omit=dev

# Copy compiled server
COPY --from=server-builder /build/server/dist ./dist

# Copy built React app into /app/web (served as static by the BFF)
COPY --from=web-builder /build/web/dist /app/web

ENV NODE_ENV=production
ENV WEB_DIST=/app/web

EXPOSE 8095

CMD ["node", "dist/index.js"]
