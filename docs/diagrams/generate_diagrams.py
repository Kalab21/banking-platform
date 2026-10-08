"""Generate the Northbank architecture diagrams (light and dark SVG pairs).

Standard library only. Run from the repository root:

    python docs/diagrams/generate_diagrams.py

Writes into docs/architecture/:

    northbank-end-to-end.svg / northbank-end-to-end-dark.svg
        End-to-end architecture for the README: users, the AWS edge, the
        application edge (BFF + gateway), domain-grouped services, data and
        messaging, and a runtime and operations rail.
    northbank-architecture.svg / northbank-architecture-dark.svg
        Detailed service-level view for docs/ARCHITECTURE.md: every service
        with its port and database, the OpenFeign calls, the Kafka topics and
        the shared libraries.

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
                  data_fill="#eef6fb", data="#0b6f8a", zone="#8a93ad", route="#6a3fb8",
                  route_fill="#f4f0fd", aws_fill="#f6f7f9", aws_stroke="#b4bac7"),
    "dark": dict(bg="#0d1117", box="#161b22", box2="#11161d", group="#11161d", group_stroke="#4a5280",
                 stroke="#8b93e8", title="#e6edf3", text="#9aa4b2", accent="#8b93e8", muted="#7d8796",
                 green="#56c88a", green_fill="#132a1d", amber="#e3b341", amber_fill="#2b2410",
                 data_fill="#0f2430", data="#4fb3cf", zone="#6b7487", route="#b39af0",
                 route_fill="#1c1630", aws_fill="#141920", aws_stroke="#3b4350"),
}

MONO = 'style="font-family:ui-monospace,SFMono-Regular,Consolas,Menlo,monospace"'


class Svg:
    def __init__(self, w, h, t, label):
        self.w, self.h, self.t, self.label, self.parts = w, h, t, label, []

    def rect(self, x, y, w, h, fill, stroke, dash=False, rx=12, sw=2):
        d = ' stroke-dasharray="7 6"' if dash else ""
        self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}" fill="{fill}" '
                          f'stroke="{stroke}" stroke-width="{sw}"{d}/>')

    def text(self, x, y, s, size=15, weight=400, color=None, anchor="middle", italic=False, mono=False):
        st = ' font-style="italic"' if italic else ""
        mf = f" {MONO}" if mono else ""
        self.parts.append(f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" '
                          f'fill="{color or self.t["text"]}" text-anchor="{anchor}"{st}{mf}>{escape(s)}</text>')

    def box(self, x, y, w, h, title, lines=(), title_color=None, fill=None, stroke=None, dash=False,
            tsize=18, lsize=15, gap=22, sw=2):
        self.rect(x, y, w, h, fill or self.t["box"], stroke or self.t["stroke"], dash, sw=sw)
        n = 1 + len(lines)
        top = y + h / 2 - (n - 1) * gap / 2 + tsize * 0.35
        self.text(x + w / 2, top, title, tsize, 700, title_color or self.t["title"])
        for i, ln in enumerate(lines):
            self.text(x + w / 2, top + gap * (i + 1), ln, lsize)

    def card(self, x, y, w, h, title, lines=(), mono_line=None, bar=None, fill=None, stroke=None,
             tsize=15.5, lsize=13.5):
        """Left-aligned card with an optional coloured top bar and a monospace footer."""
        self.rect(x, y, w, h, fill or self.t["bg"], stroke or self.t["group_stroke"], rx=6, sw=1.4)
        if bar:
            self.parts.append(f'<rect x="{x}" y="{y}" width="{w}" height="4" rx="2" fill="{bar}"/>')
        self.text(x + 14, y + 24, title, tsize, 700, self.t["title"], "start")
        for i, ln in enumerate(lines):
            self.text(x + 14, y + 45 + i * 18, ln, lsize, 400, self.t["text"], "start")
        if mono_line:
            self.text(x + 14, y + h - 12, mono_line, 12, 400, self.t["data"], "start", mono=True)

    def tag(self, x, y, s, size=12.5, color=None):
        """A label on a background, so it reads cleanly where it sits on a line."""
        w = len(s) * size * 0.56 + 12
        self.rect(x - w / 2, y - size, w, size + 7, self.t["bg"], "none", rx=4, sw=0)
        self.text(x, y, s, size, 600, color)

    def zone(self, x, y, w, h, label, color=None, size=13):
        c = color or self.t["zone"]
        self.rect(x, y, w, h, "none", c, dash=True, rx=16, sw=1.6)
        self.text(x + 16, y + 24, label.upper(), size, 700, c, "start")

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
              lsize=14, width=2.5):
        c = color or self.t["accent"]
        pts = list(pts)
        end = self.head(pts[-2], pts[-1], c)
        line = pts[:-1] + [end]
        if both:
            start = self.head(pts[1], pts[0], c)
            line = [start] + line[1:]
        self.line(line, c, dash, width)
        if label:
            self.text(lx, ly, label, lsize, 600, c, anchor)

    def render(self):
        return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" '
                f'height="{self.h}" role="img" aria-label="{escape(self.label)}">\n'
                '<style>text{font-family:-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}</style>\n'
                f'<rect width="{self.w}" height="{self.h}" fill="{self.t["bg"]}"/>\n'
                + "\n".join(self.parts) + "\n</svg>\n")


# ── End-to-end architecture (README) ─────────────────────────────────────────

END_TO_END_LABEL = (
    "Northbank end-to-end architecture. Customers and staff use a web browser that holds no bearer token and "
    "talks only to the Next.js backend-for-frontend, which keeps a server-side session and calls the API "
    "gateway over REST. In the AWS model, public API traffic arrives through Route 53, CloudFront with AWS WAF "
    "and an ACM certificate, and an Application Load Balancer. The Spring Cloud Gateway validates the JWT, "
    "forwards trusted identity headers, rate-limits and discovers services through Eureka. Eleven Spring Boot "
    "services are grouped by domain: identity (user-service); accounts and money movement (account-service, "
    "the only writer of balances, transaction-service, payment-service, integration-service); lending and cards "
    "(application-service, loan-service, credit-card-service); and risk and insight (fraud-detection, "
    "statistics-service, notification-service). REST and OpenFeign carry immediate authoritative operations; "
    "Kafka events carry derived, asynchronous workflows. Each service owns its PostgreSQL 16 database (RDS in "
    "the AWS model); Redis holds rate limits, counters and cache (ElastiCache); Kafka uses a transactional "
    "outbox, idempotent consumers, retry and dead-letter topics (Amazon MSK). A runtime and operations rail "
    "shows ECS Fargate in private subnets, ECR, Secrets Manager, CloudWatch Logs, and Prometheus, Grafana and "
    "Zipkin."
)


def end_to_end(t):
    W, H = 1440, 1000
    s = Svg(W, H, t, END_TO_END_LABEL)
    rest, kafka, data = t["accent"], t["amber"], t["data"]
    aws_fill, aws_stroke = t["aws_fill"], t["aws_stroke"]

    # ── Users and AWS edge ──
    s.box(60, 32, 360, 84, "Customers · Staff", ["web browser · holds no bearer token"], fill=t["box2"],
          tsize=18, lsize=14.5)
    s.zone(490, 14, 590, 124, "AWS edge · public API ingress", color=t["muted"], size=12)
    s.box(508, 50, 116, 72, "Route 53", ["DNS"], fill=aws_fill, stroke=aws_stroke, tsize=15, lsize=13,
          gap=19, sw=1.5)
    s.box(652, 50, 246, 72, "CloudFront + WAF", ["ACM certificate · HTTPS"], fill=aws_fill, stroke=aws_stroke,
          tsize=15, lsize=13, gap=19, sw=1.5)
    s.box(926, 50, 136, 72, "Load Balancer", ["ALB · HTTPS"], fill=aws_fill, stroke=aws_stroke, tsize=15,
          lsize=13, gap=19, sw=1.5)
    s.arrow([(624, 86), (652, 86)], color=t["muted"], width=2)
    s.arrow([(898, 86), (926, 86)], color=t["muted"], width=2)

    # ── Application edge ──
    s.zone(30, 156, 1060, 150, "Application edge")
    s.box(60, 188, 360, 100, "Next.js BFF", ["server-side session", "browser holds no bearer token"],
          title_color=rest, fill=t["box2"], tsize=18, lsize=14.5)
    s.box(500, 188, 380, 100, "API Gateway", ["Spring Cloud Gateway · JWT validation",
                                             "trusted identity headers · rate limiting"],
          title_color=rest, fill=t["box2"], tsize=18, lsize=14.5)
    s.box(910, 200, 160, 76, "Eureka", ["service discovery"], fill=t["box2"], tsize=16, lsize=13.5, gap=20)

    s.arrow([(240, 116), (240, 188)])
    s.tag(240, 141, "session cookie", 13, t["accent"])
    s.arrow([(420, 238), (500, 238)], label="REST", lx=460, ly=228, lsize=13.5)
    s.text(460, 256, "server-side", 12.5, 400, t["muted"])
    s.arrow([(994, 122), (994, 146), (800, 146), (800, 188)], color=t["muted"], width=2)
    s.arrow([(880, 238), (910, 238)], dash=True, color=t["muted"], width=2)

    # ── Business services ──
    s.zone(30, 326, 1060, 392, "Private service network · 11 Spring Boot services")
    gy, gh = 388, 306
    cols = [(50, 200, "Identity"), (270, 290, "Accounts & Money Movement"),
            (580, 250, "Lending & Cards"), (850, 220, "Risk & Insight")]
    bus = 366
    s.line([(690, 288), (690, bus)], rest)
    s.line([(150, bus), (960, bus)], rest)
    for x, w, _ in cols:
        s.arrow([(x + w / 2, bus), (x + w / 2, gy)], color=rest)
    s.text(704, 344, "routes /api/** · identity in headers", 13, 600, rest, "start")
    for x, w, title in cols:
        s.rect(x, gy, w, gh, t["group"], t["group_stroke"], rx=14)
        s.text(x + w / 2, gy + 26, title, 15.5, 700, t["accent"])

    def svc(col, dy, name, detail=None, **kw):
        x, w, _ = cols[col]
        s.box(x + 12, gy + dy, w - 24, 50 if detail else 40, name, [detail] if detail else [],
              tsize=15, lsize=12.5, gap=18, **kw)

    svc(0, 44, "user-service", "sign-in · JWT · TOTP")
    svc(1, 44, "account-service", "only writer of balances", fill=t["box2"], stroke=rest, title_color=rest)
    svc(1, 132, "transaction-service")
    svc(1, 182, "payment-service")
    svc(1, 232, "integration-service")
    svc(2, 44, "application-service", "underwriting · offers")
    svc(2, 132, "loan-service")
    svc(2, 182, "credit-card-service")
    svc(3, 44, "fraud-detection")
    svc(3, 94, "statistics-service")
    svc(3, 144, "notification-service")
    s.text(960, gy + 222, "consume events;", 13, 400, t["muted"], italic=True)
    s.text(960, gy + 240, "never block a transfer", 13, 400, t["muted"], italic=True)

    ax = cols[1][0]
    s.arrow([(ax + 52, gy + 132), (ax + 52, gy + 94)], color=rest)
    s.text(ax + 64, gy + 118, "OpenFeign", 13, 600, rest, "start")
    lx = cols[2][0]
    s.arrow([(lx + 12, gy + 69), (ax + cols[1][1] - 12, gy + 69)], color=rest)
    s.text(lx + cols[2][1] / 2, gy + 118, "offer accepted → provisioning", 12.5, 400, t["muted"], italic=True)

    # ── Data and messaging ──
    s.zone(30, 738, 1060, 178, "Data & messaging")
    dy, dh = 772, 128
    s.box(50, dy, 300, dh, "PostgreSQL 16", ["database per service", "service-owned data", "AWS: RDS"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14, gap=21)
    s.box(370, dy, 250, dh, "Redis 7", ["rate limiting · cache", "velocity state", "AWS: ElastiCache"],
          title_color=data, fill=t["data_fill"], stroke=data, tsize=17, lsize=14, gap=21)
    s.box(640, dy, 430, dh, "Apache Kafka", ["transactional outbox → idempotent consumers",
                                             "bounded retry → dead-letter topic",
                                             "AWS: Amazon MSK"],
          title_color=kafka, fill=t["amber_fill"], stroke=kafka, tsize=17, lsize=14, gap=21)
    s.arrow([(210, gy + gh), (210, dy)], color=data)
    s.text(222, 760, "JDBC · own database", 12.5, 600, data, "start")
    s.arrow([(470, gy + gh), (470, dy)], color=data)
    s.arrow([(530, gy + gh), (530, 728), (680, 728), (680, dy)], color=kafka, dash=True)
    s.arrow([(760, gy + gh), (760, dy)], color=kafka, dash=True, both=True)
    s.tag(760, 758, "publish · consume", 12.5, kafka)
    s.arrow([(960, dy), (960, gy + gh)], color=kafka, dash=True)
    s.tag(960, 758, "consume", 12.5, kafka)

    # ── Runtime and operations rail ──
    rx, rw = 1112, 300
    s.zone(1100, 14, 324, 902, "AWS runtime & operations", color=t["muted"], size=12)
    rail = [("ECS Fargate", ["gateway · Eureka · 11 services", "private subnets"]),
            ("ECR", ["container images"]),
            ("Secrets Manager", ["DB password · JWT secret"]),
            ("CloudWatch Logs", ["service · WAF · MSK logs"]),
            ("Prometheus · Grafana · Zipkin", ["metrics · dashboards · traces", "application observability"]),
            ("Delivery", ["GitHub Actions · CodeQL · Trivy", "Docker Compose · Terraform"])]
    y = 56
    for name, lines in rail:
        h = 72 + 20 * (len(lines) - 1)
        s.box(rx, y, rw - 12, h, name, lines, fill=aws_fill, stroke=aws_stroke, tsize=15.5, lsize=13.5,
              gap=20, sw=1.5)
        y += h + 24

    # ── Legend ──
    ly = 958
    s.line([(50, ly), (100, ly)], rest)
    s.text(110, ly + 5, "REST / OpenFeign · immediate, authoritative", 14, 400, t["text"], "start")
    s.line([(450, ly), (500, ly)], kafka, dash=True)
    s.text(510, ly + 5, "Kafka event · derived, asynchronous", 14, 400, t["text"], "start")
    s.line([(800, ly), (850, ly)], data)
    s.text(860, ly + 5, "data access", 14, 400, t["text"], "start")
    s.rect(1000, ly - 9, 40, 18, aws_fill, aws_stroke, rx=5, sw=1.5)
    s.text(1050, ly + 5, "AWS infrastructure model", 14, 400, t["text"], "start")
    return s.render()


# ── Detailed service-level architecture (docs/ARCHITECTURE.md) ──────────────

DETAILED_LABEL = (
    "Northbank architecture: browser, Next.js console, API gateway and Eureka; eleven Spring Boot services "
    "with their ports and databases, the OpenFeign calls into account-service and user-service, and the shared "
    "libraries; PostgreSQL with one database per service, Apache Kafka with its nine topics, Redis, "
    "observability, and build and delivery"
)


def detailed(t):
    W, H = 1400, 1010
    s = Svg(W, H, t, DETAILED_LABEL)
    rest, kafka, data, route = t["accent"], t["amber"], t["data"], t["route"]

    def node(x, y, w, h, title, lines, mono=None, fill=None, stroke=None):
        s.rect(x, y, w, h, fill or t["bg"], stroke or t["group_stroke"], rx=10, sw=1.6)
        s.text(x + w / 2, y + 28, title, 16, 700, t["title"])
        for i, ln in enumerate(lines):
            s.text(x + w / 2, y + 50 + i * 17, ln, 12.5)
        if mono:
            s.text(x + w / 2, y + h - 14, mono, 11.5, 400, t["data"], mono=True)

    # Edge row
    node(30, 30, 190, 84, "Browser", ["no token, no full", "account number"])
    node(300, 30, 330, 84, "Next.js console", ["backend-for-frontend · Server Components"],
         "Next.js 16 · React 19 · TypeScript · :3000", fill=t["box"], stroke=rest)
    node(710, 30, 360, 84, "API Gateway", ["validates JWT · overwrites X-User-* · rate limit"],
         "Spring Cloud Gateway · WebFlux · :8080", fill=t["route_fill"], stroke=route)
    node(1150, 30, 220, 84, "Eureka", ["service discovery"], ":8761")
    s.arrow([(220, 72), (300, 72)], color=rest, label="cookie", lx=260, ly=62, lsize=12)
    s.arrow([(630, 72), (710, 72)], color=rest, label="JWT", lx=670, ly=62, lsize=12)
    s.arrow([(1070, 72), (1150, 72)], color=route, dash=True, both=True, label="lookup", lx=1110, ly=62,
            lsize=12)
    s.arrow([(890, 114), (890, 168)], color=route)
    s.tag(890, 146, "REST routes /api/** · caller identity in X-User-* headers", 12.5, route)

    # Service container
    s.rect(30, 170, 1340, 470, t["group"], t["group_stroke"], dash=True, rx=14, sw=1.4)
    s.text(1352, 196, "11 business services · Spring Boot 3.3 · Java 21 · one PostgreSQL database each", 13, 600,
           t["muted"], "end")

    top = [("transaction-service", ["Deposits, withdrawals,", "transfers · idempotent"], ":8084 · transaction_db"),
           ("payment-service", ["Payees and payments,", "scheduled + recurring"], ":8085 · payment_db"),
           ("loan-service", ["Loans, amortization,", "repayment, payoff"], ":8090 · loan_db"),
           ("credit-card-service", ["Cards, statements,", "card payments"], ":8089 · credit_card_db"),
           ("integration-service", ["Wire, ACH, SWIFT", "(simulated) · FX"], ":8091 · integration_db"),
           ("fraud-detection-service", ["Rules, velocity counters,", "alerts, freezes"], ":8088 · fraud_db"),
           ("application-service", ["Applications, underwriting,", "offers, provisioning"], ":8082 · application_db")]
    cw, cy, ch = 172, 212, 106
    xs = [48 + i * (cw + 13) for i in range(7)]
    for x, (name, lines, mono) in zip(xs, top):
        s.card(x, cy, cw, ch, name, lines, mono, bar=rest, tsize=13.5, lsize=12.5)
    # payment -> transaction transfer arc
    s.parts.append(f'<path d="M{xs[1] + 40},{cy} C{xs[1] + 40},{cy - 34} {xs[0] + 140},{cy - 34} '
                   f'{xs[0] + 132},{cy - 6}" fill="none" stroke="{rest}" stroke-width="2"/>')
    s.head((xs[0] + 136, cy - 20), (xs[0] + 132, cy - 2), rest)
    s.tag(xs[0] + 186, cy - 32, "transfer", 12, rest)

    # account-service and user-service
    ay = 420
    s.card(48, ay, 1060, 96, "account-service",
           ["Accounts and balances. The only service that changes a balance: SELECT ... FOR UPDATE on every change,",
            "keyed balance legs, and /internal money endpoints for loans and cards."],
           ":8083 · account_db", bar=rest, fill=t["box"], stroke=rest, tsize=16)
    s.card(1130, ay, 222, 96, "user-service", ["Sign-in, JWT, TOTP 2FA,", "KYC, credit score"],
           ":8081 · user_db · Redis", bar=rest, tsize=14.5)
    for x in xs[:6]:
        s.arrow([(x + 86, cy + ch), (x + 86, ay)], color=rest, width=2)
    s.arrow([(xs[6] + 30, cy + ch), (1072, ay)], color=rest, width=2)
    s.arrow([(xs[6] + 106, cy + ch), (xs[6] + 106, ay)], color=rest, width=2)
    s.arrow([(1108, 468), (1130, 468)], color=rest, width=2)
    s.rect(436, 378, 286, 20, t["group"], "none", rx=4)
    s.text(579, 393, "OpenFeign · ownership check, debit, credit", 12.5, 600, rest)

    # Lower row
    ly = 536
    s.card(48, ly, 330, 88, "statistics-service", ["Read models built from six topics"],
           ":8086 · statistics_db · Redis", bar=kafka, tsize=14.5)
    s.card(394, ly, 330, 88, "notification-service", ["Customer alerts from seven topics"],
           ":8087 · notification_db", bar=kafka, tsize=14.5)
    s.rect(740, ly, 612, 88, t["bg"], t["group_stroke"], dash=True, rx=6, sw=1.4)
    s.text(754, ly + 24, "Shared libraries in every service", 14.5, 700, t["title"], "start")
    s.text(754, ly + 45, "common-security · common-idempotency · common-kafka", 12.5, 400, t["text"], "start")
    s.text(754, ly + 62, "common-events · common-observability", 12.5, 400, t["text"], "start")
    s.text(754, ly + 78, "AccessGuard · Idempotency-Key · outbox · DLT · X-Request-Id", 11.5, 400, t["data"],
           "start", mono=True)

    # Data row
    dy, dh = 690, 150
    s.arrow([(180, 640), (180, dy)], color=data)
    s.tag(180, 670, "each owns its DB", 12, data)
    s.arrow([(620, 640), (620, dy)], color=kafka, dash=True, both=True)
    s.tag(620, 670, "publish · consume", 12, kafka)
    s.arrow([(1220, 640), (1220, dy)], color=data)
    s.tag(1220, 670, "counters · cache", 12, data)

    s.rect(30, dy, 300, dh, t["data_fill"], data, rx=10, sw=1.6)
    s.text(46, dy + 30, "PostgreSQL 16", 16, 700, t["title"], "start")
    for i, ln in enumerate(["One database per service", "Flyway migrations, schema validated",
                            "Row locks on balances"]):
        s.text(46, dy + 54 + i * 18, ln, 12.5, 400, t["text"], "start")
    s.text(46, dy + dh - 16, "11 databases", 11.5, 400, t["data"], "start", mono=True)

    s.rect(350, dy, 700, dh, t["amber_fill"], kafka, rx=10, sw=1.6)
    s.text(366, dy + 30, "Apache Kafka", 16, 700, t["title"], "start")
    s.text(482, dy + 30, "transactional outbox → topic → idempotent consumer · retry → <topic>.DLT", 12.5, 400,
           t["text"], "start")
    topics = ["user-events", "account-events", "application-events", "transaction-events", "payment-events",
              "credit-card-events", "loan-events", "integration-events", "fraud-alert-events"]
    px, py = 366, dy + 50
    for name in topics:
        w = 18 + len(name) * 7.0
        if px + w > 1036:
            px, py = 366, py + 30
        s.rect(px, py, w, 22, "none", kafka, dash=True, rx=5, sw=1.2)
        s.text(px + w / 2, py + 15, name, 11.5, 400, t["title"], mono=True)
        px += w + 10
    s.text(366, dy + dh - 16, "9 topics · each has one producer · statistics and notification read most of them",
           11.5, 400, kafka, "start", mono=True)

    s.rect(1070, dy, 300, dh, t["data_fill"], data, rx=10, sw=1.6)
    s.text(1086, dy + 30, "Redis 7", 16, 700, t["title"], "start")
    for i, ln in enumerate(["Gateway rate limiting", "Sign-in throttling", "Fraud velocity counters",
                            "Statistics cache"]):
        s.text(1086, dy + 54 + i * 18, ln, 12.5, 400, t["text"], "start")

    # Operations row
    oy = 870
    s.card(30, oy, 660, 80, "Observability",
           ["Micrometer → Prometheus → Grafana · traces in Zipkin · X-Request-Id on every REST hop"],
           "Resilience4j circuit breaker on transaction → account", tsize=15)
    s.card(710, oy, 660, 80, "Build and delivery",
           ["Docker Compose for the whole stack · GitHub Actions with CodeQL and Trivy"],
           "Terraform AWS reference model (ECS Fargate, RDS, MSK, ElastiCache)", tsize=15)

    # Legend
    gy = 980
    for i, (label, color, dash) in enumerate([("REST / OpenFeign call", rest, False),
                                              ("Kafka event", kafka, True),
                                              ("gateway route", route, False),
                                              ("data store", data, False)]):
        x = 30 + i * 200
        s.arrow([(x, gy), (x + 40, gy)], color=color, dash=dash, width=2)
        s.text(x + 50, gy + 5, label, 13, 400, t["text"], "start")
    return s.render()


def main():
    for theme, palette in THEMES.items():
        suffix = "" if theme == "light" else "-dark"
        for name, fn in (("northbank-end-to-end", end_to_end), ("northbank-architecture", detailed)):
            path = os.path.join(OUT, f"{name}{suffix}.svg")
            with open(path, "w", encoding="utf8", newline="\n") as f:
                f.write(fn(palette))
            print("wrote", os.path.normpath(path))


if __name__ == "__main__":
    main()
