"""Generate the Northbank architecture diagrams (light and dark SVG pairs).

Standard library only. Run from the repository root:

    python docs/diagrams/generate_diagrams.py

Writes into docs/architecture/:

    northbank-logical.svg / northbank-logical-dark.svg
        Logical architecture: client, edge (BFF + gateway), domain services,
        data and messaging, operations. Used by the README.
    northbank-aws-reference.svg / northbank-aws-reference-dark.svg
        AWS reference deployment, drawn only from infrastructure/aws/*.tf.

The detailed service-level diagram (northbank-architecture*.svg, used by
docs/ARCHITECTURE.md) is maintained separately and is not regenerated here.

Arrowheads are explicit triangles rather than SVG <marker> elements, because
some renderers drop markers.
"""
import math
import os
from html import escape

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "architecture")

THEMES = {
    "light": dict(bg="#ffffff", box="#f3f5fb", box2="#fafbff", group="#fafbff", group_stroke="#9aa3d9",
                  stroke="#3f4ab8", title="#14213d", text="#4b587c", accent="#3f4ab8", muted="#7a849e",
                  green="#2e7d4f", green_fill="#eef7f1", amber="#9a6700", amber_fill="#fff8e6",
                  data_fill="#eef6fb", data="#0b6f8a", zone="#8a93ad"),
    "dark": dict(bg="#0d1117", box="#161b22", box2="#11161d", group="#11161d", group_stroke="#4a5280",
                 stroke="#8b93e8", title="#e6edf3", text="#9aa4b2", accent="#8b93e8", muted="#7d8796",
                 green="#56c88a", green_fill="#132a1d", amber="#e3b341", amber_fill="#2b2410",
                 data_fill="#0f2430", data="#4fb3cf", zone="#6b7487"),
}


class Svg:
    def __init__(self, w, h, t, label):
        self.w, self.h, self.t, self.label, self.parts = w, h, t, label, []

    def rect(self, x, y, w, h, fill, stroke, dash=False, rx=12, sw=2):
        d = ' stroke-dasharray="7 6"' if dash else ""
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
                          f'stroke="{stroke}" stroke-width="{sw}"{d}/>')

    def text(self, x, y, s, size=15, weight=400, color=None, anchor="middle", italic=False):
        st = ' font-style="italic"' if italic else ""
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" '
                          f'fill="{color or self.t["text"]}" text-anchor="{anchor}"{st}>{escape(s)}</text>')

    def box(self, x, y, w, h, title, lines=(), title_color=None, fill=None, stroke=None, dash=False,
            tsize=18, lsize=15, gap=22):
        self.rect(x, y, w, h, fill or self.t["box"], stroke or self.t["stroke"], dash)
        n = 1 + len(lines)
        top = y + h / 2 - (n - 1) * gap / 2 + tsize * 0.35
        self.text(x + w / 2, top, title, tsize, 700, title_color or self.t["title"])
        for i, ln in enumerate(lines):
            self.text(x + w / 2, top + gap * (i + 1), ln, lsize)

    def zone(self, x, y, w, h, label, color=None):
        c = color or self.t["zone"]
        self.rect(x, y, w, h, "none", c, dash=True, rx=16, sw=1.6)
        self.text(x + 16, y + 24, label.upper(), 13, 700, c, "start")

    def line(self, pts, color, dash=False, width=2.5):
        d = ' stroke-dasharray="8 6"' if dash else ""
        path = " ".join(f"{'M' if i == 0 else 'L'}{x:.1f},{y:.1f}" for i, (x, y) in enumerate(pts))
        self.parts.append(f'<path d="{path}" fill="none" stroke="{color}" stroke-width="{width}"{d}/>')

    def head(self, p_from, p_to, color):
        (x1, y1), (x2, y2) = p_from, p_to
        a = math.atan2(y2 - y1, x2 - x1)
        hl, hw = 13, 7
        bx, by = x2 - hl * math.cos(a), y2 - hl * math.sin(a)
        p1 = (bx + hw * math.sin(a), by - hw * math.cos(a))
        p2 = (bx - hw * math.sin(a), by + hw * math.cos(a))
        self.parts.append(f'<path d="M{x2:.1f},{y2:.1f} L{p1[0]:.1f},{p1[1]:.1f} '
                          f'L{p2[0]:.1f},{p2[1]:.1f} z" fill="{color}"/>')
        return bx, by

    def arrow(self, pts, color=None, label=None, lx=None, ly=None, dash=False, anchor="middle", both=False,
              lsize=14):
        c = color or self.t["accent"]
        pts = list(pts)
        end = self.head(pts[-2], pts[-1], c)
        line = pts[:-1] + [end]
        if both:
            start = self.head(pts[1], pts[0], c)
            line = [start] + line[1:]
        self.line(line, c, dash)
        if label:
            self.text(lx, ly, label, lsize, 600, c, anchor)

    def render(self):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" '
                f'height="{self.h}" role="img" aria-label="{escape(self.label)}">\n'
                '<style>text{font-family:-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}</style>\n'
                f'<rect width="{self.w}" height="{self.h}" fill="{self.t["bg"]}"/>\n'
                + "\n".join(self.parts) + "\n</svg>\n")


# ── Logical architecture ─────────────────────────────────────────────────────

LOGICAL_LABEL = (
    "Northbank logical architecture. Customers and staff use a browser that talks only to the Next.js "
    "backend-for-frontend, which keeps the JWT in an httpOnly cookie and calls the API gateway server-side. "
    "The Spring Cloud Gateway validates the JWT, overwrites the X-User identity headers, rate-limits with Redis "
    "and discovers services through Eureka. Eleven Spring Boot services sit on a private network, grouped by "
    "domain: identity (user), accounts and money movement (account, transaction, payment, integration), "
    "lending and cards (application, loan, credit-card), and risk and insight (fraud-detection, statistics, "
    "notification). Authoritative operations are synchronous REST calls into account-service; derived "
    "workflows consume Kafka events published through a transactional outbox. Each service owns its own "
    "PostgreSQL database; Redis holds rate limits, login throttling, fraud velocity counters and the "
    "statistics cache. Prometheus, Grafana, Zipkin and CI with CodeQL and Trivy run across the platform."
)


def logical(t):
    W, H = 1400, 1210
    s = Svg(W, H, t, LOGICAL_LABEL)
    rest, kafka, data = t["accent"], t["amber"], t["data"]

    # Trust zones
    s.zone(30, 16, W - 60, 128, "Public")
    s.zone(30, 160, W - 60, 168, "Application edge")
    s.zone(30, 346, W - 60, 446, "Private service network")
    s.zone(30, 812, W - 60, 196, "Data and messaging")

    # Client
    s.box(60, 48, 380, 80, "Customers and staff", ["web browser · no bearer token"], fill=t["box2"])

    # Edge
    s.box(60, 196, 380, 112, "Next.js BFF", ["server components + server actions",
                                              "JWT held in an httpOnly cookie"],
          title_color=rest, fill=t["box2"])
    s.box(540, 196, 440, 112, "API Gateway", ["Spring Cloud Gateway · validates JWT",
                                              "overwrites X-User-* · Redis rate limit"],
          title_color=rest, fill=t["box2"])
    s.box(1080, 210, 260, 84, "Eureka", ["service registry"], fill=t["box2"])

    s.arrow([(250, 128), (250, 196)], label="session cookie", lx=264, ly=168, anchor="start")
    s.arrow([(440, 252), (540, 252)], label="REST", lx=490, ly=240)
    s.text(490, 274, "server-side", 13, 400, t["muted"])
    s.arrow([(980, 252), (1080, 252)], dash=True, color=t["muted"], label="discovery", lx=1030, ly=240,
            lsize=13)

    # Domain groups
    gy = 418
    cols = [
        (50, 240, "Identity"),
        (320, 330, "Accounts and money movement"),
        (730, 300, "Lending and cards"),
        (1060, 290, "Risk and insight"),
    ]
    gh = 356
    # gateway fan-out bus
    bus_y = 392
    s.line([(760, 308), (760, bus_y)], rest)
    s.line([(170, bus_y), (1205, bus_y)], rest)
    for x, w, _ in cols:
        s.arrow([(x + w / 2, bus_y), (x + w / 2, gy)], color=rest)
    s.text(774, 340, "routes /api/** · caller identity in headers", 14, 600, rest, "start")

    for x, w, title in cols:
        s.rect(x, gy, w, gh, t["group"], t["group_stroke"], rx=14)
        s.text(x + w / 2, gy + 27, title, 16, 700, t["accent"])

    def svc(x, y, w, name, detail, h=60, **kw):
        s.box(x + 12, y, w - 24, h, name, [detail], tsize=16, lsize=14, gap=21, **kw)

    # Identity
    x, w, _ = cols[0]
    svc(x, gy + 44, w, "user-service", "sign-in · issues JWT", h=60)
    s.text(x + w / 2, gy + 134, "TOTP 2FA · BCrypt", 14, 400, t["text"])
    s.text(x + w / 2, gy + 156, "login throttle · KYC", 14, 400, t["text"])

    # Money movement: account on top, callers below
    x, w, _ = cols[1]
    svc(x, gy + 44, w, "account-service", "only writer of balances · row locks",
        fill=t["box2"], stroke=rest, title_color=rest)
    svc(x, gy + 150, w, "transaction-service", "deposits · transfers · idempotency")
    svc(x, gy + 216, w, "payment-service", "payees · scheduled payments")
    svc(x, gy + 282, w, "integration-service", "simulated wire · ACH · SWIFT")
    s.arrow([(x + 60, gy + 150), (x + 60, gy + 104)], color=rest)
    s.text(x + 74, gy + 132, "OpenFeign · circuit breaker", 13.5, 600, rest, "start")

    # Lending and cards
    x, w, _ = cols[2]
    svc(x, gy + 44, w, "application-service", "underwriting · offers")
    svc(x, gy + 150, w, "loan-service", "amortization · repayment")
    svc(x, gy + 216, w, "credit-card-service", "cards · statements")
    # lending -> account (/internal money endpoints)
    s.arrow([(x + 12, gy + 74), (650, gy + 74)], color=rest)
    s.text(690, gy + 64, "REST", 13.5, 600, rest)
    # provisioning note between application and loan/card
    s.text(x + w / 2, gy + 128, "offer accepted → provisioning", 13, 400, t["muted"], italic=True)

    # Risk and insight
    x, w, _ = cols[3]
    svc(x, gy + 44, w, "fraud-detection", "rules · velocity · freezes")
    svc(x, gy + 150, w, "statistics-service", "read models · cached")
    svc(x, gy + 216, w, "notification-service", "customer alerts")

    # Data and messaging
    dy, dh = 852, 136
    s.box(60, dy, 230, dh, "Redis 7", ["rate limit · throttle", "fraud velocity", "statistics cache"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14, gap=21)
    s.box(320, dy, 330, dh, "PostgreSQL 16", ["one database per service", "Flyway migrations",
                                              "SELECT … FOR UPDATE"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14, gap=21)
    s.box(730, dy, 620, dh, "Apache Kafka", ["transactional outbox → topic → idempotent consumer",
                                             "bounded retry → dead-letter topic",
                                             "derived state only: never the money path"],
          title_color=kafka, fill=t["amber_fill"], stroke=kafka, tsize=17, lsize=14, gap=21)

    s.arrow([(255, gy + gh), (255, dy)], color=data)
    s.arrow([(485, gy + gh), (485, dy)], color=data, label="JDBC · own DB each", lx=473, ly=826, anchor="end")
    # money movement publishes events
    s.arrow([(620, gy + gh), (620, 800), (790, 800), (790, dy)], color=kafka, dash=True)
    # lending publishes and consumes (provisioning)
    s.arrow([(880, gy + gh), (880, dy)], color=kafka, dash=True, both=True)
    s.text(894, 832, "provisioning", 13.5, 600, kafka, "start")
    # risk and insight consume
    s.arrow([(1205, dy), (1205, gy + gh)], color=kafka, dash=True, label="Kafka events (derived)",
            lx=1191, ly=832, anchor="end")

    # Operations strip
    oy = 1030
    s.text(48, oy + 4, "OPERATIONS · CROSS-CUTTING", 13, 700, t["zone"], "start")
    s.box(48, oy + 18, 640, 82, "Observability", ["Micrometer → Prometheus → Grafana · Zipkin · X-Request-Id"],
          fill=t["box2"], stroke=t["group_stroke"], tsize=16, lsize=14.5)
    s.box(712, oy + 18, 640, 82, "Delivery and supply chain",
          ["GitHub Actions · CodeQL · Trivy · Docker Compose · Terraform"],
          fill=t["box2"], stroke=t["group_stroke"], tsize=16, lsize=14.5)

    # Legend
    ly = 1178
    s.line([(60, ly), (110, ly)], rest)
    s.text(120, ly + 5, "REST · authoritative, synchronous", 14, 400, t["text"], "start")
    s.line([(440, ly), (490, ly)], kafka, dash=True)
    s.text(500, ly + 5, "Kafka event · derived, asynchronous", 14, 400, t["text"], "start")
    s.line([(830, ly), (880, ly)], data)
    s.text(890, ly + 5, "data access", 14, 400, t["text"], "start")
    s.rect(1040, ly - 9, 40, 18, "none", t["zone"], dash=True, rx=5, sw=1.6)
    s.text(1090, ly + 5, "trust boundary", 14, 400, t["text"], "start")
    return s.render()


# ── AWS reference deployment ─────────────────────────────────────────────────

AWS_LABEL = (
    "Northbank AWS reference deployment, drawn from the Terraform in infrastructure/aws and not currently "
    "deployed. Clients resolve the API name through Route 53 to CloudFront, which has AWS WAF attached and "
    "terminates TLS with an ACM certificate. CloudFront forwards over HTTPS only to an internet-facing "
    "Application Load Balancer in the public subnets, which redirects HTTP to HTTPS and forwards to the API "
    "gateway task over HTTP. The gateway, Eureka and eleven business services run as ECS Fargate tasks in "
    "private subnets without public IPs. They reach RDS PostgreSQL over JDBC, ElastiCache Redis, and Amazon "
    "MSK, whose client-to-broker traffic is TLS. Images come from ECR; secrets from Secrets Manager; logs go "
    "to CloudWatch; MSK data at rest uses a KMS key; a NAT gateway provides egress."
)


def aws(t):
    W, H = 1400, 1060
    s = Svg(W, H, t, AWS_LABEL)
    rest, data, kafka = t["accent"], t["data"], t["amber"]

    s.zone(30, 16, W - 60, 118, "Internet")
    s.zone(30, 150, W - 60, 168, "AWS edge · global")
    s.zone(30, 336, 980, 664, "VPC · 3 availability zones")
    s.zone(1040, 336, 330, 664, "AWS regional services")

    s.box(60, 46, 360, 72, "Clients", ["browser or API client"], fill=t["box2"])

    s.box(60, 186, 420, 112, "CloudFront", ["ACM certificate · viewer TLS 1.2+",
                                            "HTTP → HTTPS · API not cached"],
          title_color=rest, fill=t["box2"])
    s.box(560, 186, 400, 112, "AWS WAF", ["common · SQLi · known-bad rules",
                                          "/api/auth rate limit · geo block"],
          title_color=t["amber"], fill=t["amber_fill"], stroke=t["amber"])
    s.box(1040, 186, 300, 112, "Route 53", ["api alias → CloudFront", "HTTPS health check"], fill=t["box2"])

    s.arrow([(240, 118), (240, 186)], color=rest, label="HTTPS", lx=254, ly=158, anchor="start")
    s.arrow([(560, 242), (480, 242)], color=t["amber"], label="web ACL", lx=520, ly=232, lsize=13)
    s.arrow([(420, 82), (1190, 82), (1190, 186)], color=t["muted"], dash=True, label="DNS", lx=1176, ly=112,
            anchor="end", lsize=13)

    # Public subnets
    s.zone(50, 376, 940, 150, "Public subnets", color=t["group_stroke"])
    s.box(70, 410, 440, 100, "Application Load Balancer", ["internet-facing · HTTPS listener (TLS 1.2/1.3)",
                                                           "HTTP listener redirects to HTTPS"],
          title_color=rest, fill=t["box2"])
    s.box(560, 410, 410, 100, "NAT gateway", ["outbound egress for private subnets",
                                              "via the internet gateway"], fill=t["box2"])
    s.arrow([(300, 298), (300, 410)], color=rest, label="HTTPS · origin https-only", lx=314, ly=362,
            anchor="start")

    # Private subnets
    s.zone(50, 546, 940, 438, "Private subnets", color=t["group_stroke"])
    s.rect(70, 582, 900, 196, t["group"], rest, rx=14)
    s.text(950, 610, "ECS Fargate cluster · no public IPs", 16, 700, rest, "end")
    s.box(90, 630, 290, 128, "api-gateway", ["ALB target group", "JWT · routing"],
          title_color=rest, tsize=17, lsize=14.5)
    s.box(400, 630, 250, 128, "eureka-server", ["Cloud Map private DNS", "service registry"],
          tsize=17, lsize=14.5)
    s.box(670, 630, 280, 128, "11 business services", ["one task definition each", "images from ECR"],
          tsize=17, lsize=14.5)
    s.arrow([(300, 510), (300, 630)], color=rest, label="HTTP · target group", lx=314, ly=572, anchor="start")

    dy = 840
    s.box(70, dy, 280, 124, "RDS PostgreSQL 16", ["Multi-AZ · storage encrypted", "database per service"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14.5)
    s.box(380, dy, 270, 124, "ElastiCache Redis 7", ["single node", "rate limits · counters · cache"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14.5)
    s.box(680, dy, 290, 124, "Amazon MSK", ["2 brokers · TLS client ↔ broker", "KMS key at rest"],
          title_color=kafka, fill=t["amber_fill"], stroke=kafka, tsize=17, lsize=14.5)
    s.arrow([(210, 778), (210, dy)], color=data, label="JDBC", lx=222, ly=815, anchor="start")
    s.arrow([(515, 778), (515, dy)], color=data, label="Redis", lx=527, ly=815, anchor="start")
    s.arrow([(825, 778), (825, dy)], color=kafka, label="Kafka · TLS", lx=837, ly=815, anchor="start")

    # Regional services
    rx, rw = 1062, 288
    items = [("ECR", "13 repositories · scan on push"),
             ("Secrets Manager", "DB password · JWT secret"),
             ("CloudWatch Logs", "services · WAF · MSK"),
             ("IAM", "task execution + task roles"),
             ("KMS", "MSK encryption key")]
    for i, (name, detail) in enumerate(items):
        s.box(rx, 376 + i * 118, rw, 96, name, [detail], tsize=17, lsize=14.5, fill=t["box2"])
    s.arrow([(970, 680), (1062, 680)], color=t["muted"], dash=True)
    s.text(1016, 652, "VPC", 13, 600, t["muted"])
    s.text(1016, 668, "endpoints", 13, 600, t["muted"])

    s.text(W / 2, 1036, "Terraform-defined reference architecture; not currently deployed.", 15, 600,
           t["muted"], italic=True)
    return s.render()


def main():
    for theme, palette in THEMES.items():
        suffix = "" if theme == "light" else "-dark"
        for name, fn in (("northbank-logical", logical), ("northbank-aws-reference", aws)):
            path = os.path.join(OUT, f"{name}{suffix}.svg")
            with open(path, "w", encoding="utf8", newline="\n") as f:
                f.write(fn(palette))
            print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
