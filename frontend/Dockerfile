# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Production image for the Next.js banking console.
#
# Multi-stage: dependencies and the build stay out of the final image, which
# ships only the standalone server bundle and static assets, and runs as a
# non-root user.
# ---------------------------------------------------------------------------

FROM node:22-alpine AS deps
WORKDIR /app

# Optional extra trust anchor, for machines behind a TLS-inspecting proxy or
# antivirus (Zscaler, Netskope, Norton Web Shield and friends). Such a product
# re-signs registry.npmjs.org with its own root; the host trusts that root but
# this container does not, so `npm ci` fails every fetch with
# UNABLE_TO_VERIFY_LEAF_SIGNATURE and then dies with npm's unhelpful
# "Exit handler never called!".
#
# Pass the interceptor's root CA (PEM text) to build behind one:
#   docker build --build-arg EXTRA_CA_CERT_PEM="$(cat extra-ca.crt)" ...
# The CA is a local machine detail, so it is passed in rather than committed.
#
# Certificate verification is never disabled; this only ADDS a trust anchor.
# With no build arg the image builds exactly as before.
ARG EXTRA_CA_CERT_PEM=""
RUN mkdir -p /usr/local/share/ca-certificates \
 && printf '%s' "$EXTRA_CA_CERT_PEM" > /usr/local/share/ca-certificates/extra-ca.crt

COPY package.json package-lock.json ./
# --no-audit/--no-fund cut work the image does not need; the lockfile is the
# source of truth so the install stays reproducible.
#
# NODE_EXTRA_CA_CERTS *adds* to Node's bundled roots rather than replacing them
# (unlike npm's `cafile`), and it expands to empty when no CA was supplied, so
# the default build is untouched. It is set per-command rather than via ENV so
# nothing points at the file once this stage is done.
RUN NODE_EXTRA_CA_CERTS=${EXTRA_CA_CERT_PEM:+/usr/local/share/ca-certificates/extra-ca.crt} \
    npm ci --no-audit --no-fund

FROM node:22-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

RUN addgroup --system --gid 1001 nodejs \
 && adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static

USER nextjs
EXPOSE 3000

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:3000/login').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
