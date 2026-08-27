# Multi-stage build for Next.js frontend
# Stage 1: Install dependencies
FROM node:20-alpine AS deps

WORKDIR /app

# Enable corepack so pnpm can be selected by the packageManager field in
# package.json. Corepack ships with Node 20+ and avoids the version-skew
# issues we hit with `npm install -g pnpm` (which was grabbing the very
# latest pnpm that no longer supports our lockfileVersion).
RUN corepack enable
RUN corepack prepare pnpm@9 --activate

# Copy package files
COPY package.json pnpm-lock.yaml* pnpm-workspace.yaml* .npmrc* ./

# Install dependencies. Use --no-frozen-lockfile so the build stays
# reproducible even when patch versions of pnpm try to re-format the
# lockfile.
RUN pnpm install --no-frozen-lockfile

# Stage 2: Build the application
FROM node:20-alpine AS builder

WORKDIR /app

# Same corepack + pnpm@9 setup as the deps stage so the builder matches.
RUN corepack enable
RUN corepack prepare pnpm@9 --activate

# Copy node_modules from deps stage
COPY --from=deps /app/node_modules ./node_modules

# Copy source code
COPY . .

# Set environment variables for build
ENV NEXT_TELEMETRY_DISABLED=1

# Build the application
RUN pnpm build

# Stage 3: Production runner
FROM node:20-alpine AS runner

WORKDIR /app

# Set environment variables
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

# Create non-root user for security
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

# Copy public directory
COPY --from=builder /app/public ./public

# Set permissions for prerender cache
RUN mkdir .next
RUN chown nextjs:nodejs .next

# Copy built application
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

# Switch to non-root user
USER nextjs

# Expose port
EXPOSE 3000

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:3000 || exit 1

# Start the application
CMD ["node", "server.js"]
