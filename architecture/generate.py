#!/usr/bin/env python
"""
Generates architecture/architecture.png — the EventFlow system diagram.

Design contract (v4, senior pass):
* HARMONIC GRID — one spacing scale (M=48 outer, G=40 gutters, 24px inner pad),
  three shared column edges used by BOTH content rows, equal card heights per row.
* BUS LANE — all three flow labels sit on ONE baseline in the dedicated lane
  between the namespaces; no label ever touches a line or a border.
* MECHANICAL OVERLAP CHECKING — every text, chip and pill records its measured
  bounding box; every polyline records its segments. After drawing, the build
  FAILS (exit 1) if: text∩text, text∩line, or chip/pill∩chip/pill overlap.
  Layout clunk cannot ship.

Regenerate:  python architecture/generate.py
Requires:    python -m pip install --user Pillow
"""

from PIL import Image, ImageDraw, ImageFont, ImageFilter
from pathlib import Path
import math
import sys

HERE = Path(__file__).resolve().parent
OUT = HERE / "architecture.png"

SS = 2                      # supersample
W, H = 1560, 1000

# ---------------- spacing scale ----------------
M      = 48     # outer margin
PAD    = 24     # card inner padding
G      = 40     # gutter between columns
COL1   = (M + 44, 532)        # 92..532
COL2   = (572, 1012)
COL3   = (1052, 1468)

# ---------------- tokens ----------------
BG, DOT   = "#0B0F17", "#141B28"
CARD, BORDER = "#121926", "#243044"
TXT, SOFT = "#E7ECF5", "#B9C2D4"
MUTED, FAINT = "#8A94A8", "#5E6980"
BLUE, GREEN, AMBER, RED, INDIGO = "#5B9CFF", "#43D08A", "#F0A63C", "#F26D78", "#7C87E8"

VIOLATIONS = []
TEXTS  = []   # (x1,y1,x2,y2, label)
LINES  = []   # ((x1,y1),(x2,y2))
RECTS  = []   # (x1,y1,x2,y2, label)  — chips & pills

# ---------------- fonts ----------------
F = {"reg": {}, "bold": {}}
def load_fonts():
    fams = [
        ("C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/segoeuib.ttf"),
        ("C:/Windows/Fonts/arial.ttf",   "C:/Windows/Fonts/arialbd.ttf"),
        ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
         "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    ]
    sizes = [11, 12, 13, 14, 15, 16, 18, 34]
    for reg, bold in fams:
        try:
            for s in sizes:
                F["reg"][s]  = ImageFont.truetype(reg,  s * SS)
                F["bold"][s] = ImageFont.truetype(bold, s * SS)
            return
        except OSError:
            continue
    for s in sizes:
        F["reg"][s] = F["bold"][s] = ImageFont.load_default()
load_fonts()

img = Image.new("RGB", (W * SS, H * SS), BG)
gd = ImageDraw.Draw(img)
for gx in range(24, W, 32):
    for gy in range(24, H, 32):
        gd.point((gx * SS, gy * SS), fill=DOT)
shadow = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
sdraw  = ImageDraw.Draw(shadow)
d = ImageDraw.Draw(img)

_S = lambda v: v * SS

def measure(s, size, bold=False):
    return d.textlength(s, font=F["bold" if bold else "reg"][size]) / SS

def register_text(x1, y1, x2, y2, label):
    TEXTS.append((x1, y1, x2, y2, label))

def text(xy, s, size, color=TXT, bold=False, anchor="la", box=None):
    """Draw + register. If `box`, assert horizontal fit with 8px inner margin."""
    x, y = xy
    tw = measure(s, size, bold)
    th = size * 1.35
    if anchor == "mm":   x1, x2 = x - tw / 2, x + tw / 2
    elif anchor in ("rm", "ra"): x1, x2 = x - tw, x
    else:                x1, x2 = x, x + tw
    y1, y2 = y - th / 2 if anchor in ("mm", "lm", "rm") else y, (y - th / 2 if anchor in ("mm", "lm", "rm") else y) + th
    if box:
        if x1 < box[0] + 8 or x2 > box[2] - 8:
            VIOLATIONS.append(f"fit: '{s[:44]}' x=[{x1:.0f},{x2:.0f}] escapes [{box[0]},{box[2]}]")
    register_text(x1, y1, x2, y2, s)
    d.text((_S(x), _S(y)), s, font=F["bold" if bold else "reg"][size], fill=color, anchor=anchor)
    return tw

def tracked(xy, s, size, color, box, tracking=2.2, anchor_right=False):
    s = s.upper()
    f = F["bold"][size]
    total = sum(d.textlength(c, font=f) + tracking * SS for c in s) - tracking * SS
    x, y = xy
    x = x - total / SS if anchor_right else x
    if x < box[0] + 8 or x + total / SS > box[2] - 8:
        VIOLATIONS.append(f"tracked: '{s[:40]}' escapes box")
    cx = _S(x)
    for c in s:
        d.text((cx, _S(y)), c, font=f, fill=color)
        cx += d.textlength(c, font=f) + tracking * SS
    register_text(x, y, x + total / SS, y + size * 1.35, s)

def card(box, stripe=None):
    x1, y1, x2, y2 = [_S(v) for v in box]
    r = 12 * SS
    sdraw.rounded_rectangle([x1, y1 + 6 * SS, x2, y2 + 10 * SS], radius=r, fill=(0, 0, 0, 100))
    img.paste(Image.new("RGB", img.size, 0), (0, 0), shadow.filter(ImageFilter.GaussianBlur(9 * SS)))
    sdraw.rounded_rectangle([x1, y1 + 6 * SS, x2, y2 + 10 * SS], radius=r, fill=(0, 0, 0, 0))
    d.rounded_rectangle([x1, y1, x2, y2], radius=r, fill=CARD, outline=BORDER, width=SS)
    if stripe:
        d.rounded_rectangle([x1 + SS, y1 + 10 * SS, x1 + 4 * SS, y2 - 10 * SS], radius=2 * SS, fill=stripe)

def chip(box, label, color, size=12):
    x1, y1, x2, y2 = [_S(v) for v in box]
    tw = measure(label, size)
    if tw > (x2 - x1) / SS - 20:
        VIOLATIONS.append(f"chip: '{label}' overflows")
    d.rounded_rectangle([x1, y1, x2, y2], radius=8 * SS, fill=CARD, outline=color, width=SS)
    cx, cy = (x1 + x2) / (2 * SS), (y1 + y2) / (2 * SS)
    d.text((_S(cx), _S(cy) - SS), label, font=F["reg"][size], fill=color, anchor="mm")
    RECTS.append((box[0], box[1], box[2], box[3], f"chip:{label}"))
    register_text(cx - tw / 2, cy - size * .7, cx + tw / 2, cy + size * .7, label)

def arrow_head(tip, frm, color, size=9):
    ang = math.atan2(tip[1] - frm[1], tip[0] - frm[0])
    t = (_S(tip[0]), _S(tip[1]))
    for da in (0.45, -0.45):
        p = (t[0] - size * SS * math.cos(ang + da), t[1] - size * SS * math.sin(ang + da))
        d.line([t, p], fill=color, width=2 * SS)
    d.ellipse([t[0] - 1.6 * SS, t[1] - 1.6 * SS, t[0] + 1.6 * SS, t[1] + 1.6 * SS], fill=color)

def polyline(points, color, width=2):
    pts = [(_S(x), _S(y)) for x, y in points]
    for i in range(len(pts) - 1):
        d.line([pts[i], pts[i + 1]], fill=color, width=width * SS)
    for p in pts[1:-1]:
        d.ellipse([p[0] - width * SS, p[1] - width * SS, p[0] + width * SS, p[1] + width * SS], fill=color)
    for i in range(len(points) - 1):
        LINES.append((points[i], points[i + 1]))
    arrow_head(points[-1], points[-2], color)

def flow_pill(cx, cy, s, color, size=12):
    w = measure(s, size) + 22
    box = (cx - w / 2, cy - 11, cx + w / 2, cy + 11)
    d.rounded_rectangle([_S(v) for v in box], radius=10 * SS, fill=CARD, outline=color, width=SS)
    text((cx, cy), s, size, color, anchor="mm")
    RECTS.append((*box, f"pill:{s}"))
    return box

# ============================================================
# HEADER
# ============================================================
text((M, 38), "EventFlow", 34, TXT, bold=True)
text((M, 88), "Two Spring Boot services · asynchronous Kafka events · failure modes verified end-to-end", 15, MUTED)

px = M
for p in ["Java 21", "Spring Boot 3.3", "Apache Kafka 4.x (KRaft)", "Docker", "Kubernetes", "GitHub Actions"]:
    w = measure(p, 12) + 26
    d.rounded_rectangle([_S(px), _S(118), _S(px + w), _S(146)], radius=14 * SS, outline=BORDER, width=SS)
    text((px + w / 2, 132), p, 12, "#C6CFDF", anchor="mm")
    px += w + 10

vb = "verified live — docs/evidence.md"
vw = measure(vb, 12) + 34
d.rounded_rectangle([_S(1512 - vw), _S(118), _S(1512), _S(146)], radius=14 * SS, outline=GREEN, width=SS)
d.ellipse([_S(1512 - vw + 14), _S(128), _S(1512 - vw + 20), _S(134)], fill=GREEN)
text((1512 - vw + 28, 132), vb, 12, GREEN, anchor="lm")

# ============================================================
# K8S BOUNDARY
# ============================================================
K8S = (M, 184, 1512, 828)
d.rounded_rectangle([_S(v) for v in K8S], radius=18 * SS, outline=INDIGO, width=SS)
tracked((80, 198), "kubernetes cluster · minikube v1.37", 11, INDIGO, K8S, 2.4)
tracked((1466, 198), "verified live — pods · probes · scaling · failover", 11, FAINT, K8S, 1.8, anchor_right=True)

# ---------- namespace kafka (224..372) ----------
KNS = (72, 224, 1488, 372)
d.rounded_rectangle([_S(v) for v in KNS], radius=14 * SS, outline=AMBER, width=SS)
tracked((94, 237), "namespace · kafka", 11, AMBER, KNS, 2.4)
tracked((1466, 237), "strimzi operator 1.2.0", 11, FAINT, KNS, 1.8, anchor_right=True)

for x1, x2, title, l1, l2, stripe in [
    (COL1[0], COL1[1], "Apache Kafka 4.3.1", "single node · KRaft mode — no ZooKeeper",
     "my-cluster-kafka-bootstrap.kafka.svc:9092", AMBER),
    (COL2[0], COL2[1], "topic · orders", "1 partition · RF 1 · retention 7d",
     "key = orderId → per-order ordering", AMBER),
    (COL3[0], COL3[1], "topic · orders.DLT", "dead-letter parking lot",
     "original payload + kafka_original* headers", RED),
]:
    card((x1, 264, x2, 356), stripe=stripe)
    text((x1 + PAD, 280), title, 16, TXT, bold=True, box=(x1, 264, x2, 356))
    text((x1 + PAD, 310), l1, 13, MUTED, box=(x1, 264, x2, 356))
    text((x1 + PAD, 332), l2, 13, MUTED, box=(x1, 264, x2, 356))

# ---------- BUS LANE (372..496): three flows, three clear pockets ----------
# Symmetry: blue enters the topic card at 742, green drops at 842 — ±50 around the
# 792 card center, so the pair reads as composed, not accidental.
# publish (blue): up from Order Service center, along y=420, up into topic·orders
polyline([(312, 524), (312, 420), (742, 420), (742, 356)], BLUE)
flow_pill(500, 394, "publish · OrderCreated", BLUE)        # pocket above blue line

# consume (green): straight down from topic·orders into Fulfillment
polyline([(842, 356), (842, 524)], GREEN)
flow_pill(650, 446, "consume · group=fulfillment", GREEN)  # pocket left of green line

# dead-letter (red): leaves the Fulfillment CARD TOP (x=962), along y=448, up into orders.DLT center
polyline([(962, 524), (962, 448), (1260, 448), (1260, 356)], RED)
flow_pill(1120, 472, "retries exhausted", RED)             # pocket below red line

# ---------- namespace eventflow (496..760) ----------
ENS = (72, 496, 1488, 760)
d.rounded_rectangle([_S(v) for v in ENS], radius=14 * SS, outline=INDIGO, width=SS)
tracked((94, 509), "namespace · eventflow", 11, INDIGO, ENS, 2.4)
tracked((1466, 509), "deployments · services · configmap · probes", 11, FAINT, ENS, 1.8, anchor_right=True)

# Order Service (col1)
card((COL1[0], 524, COL1[1], 716), stripe=BLUE)
text((116, 540), "Order Service", 18, TXT, bold=True, box=(COL1[0], 524, COL1[1], 716))
chip((436, 542, 508, 566), ":8080", BLUE)
text((116, 578), "POST /orders → 201 + Location", 14, SOFT, box=(COL1[0], 524, COL1[1], 716))
text((116, 602), "GET /orders/{id} → 200 | 404", 14, SOFT, box=(COL1[0], 524, COL1[1], 716))
text((116, 630), "validated: customerId UUID · amount ≥ 0.01", 13, MUTED, box=(COL1[0], 524, COL1[1], 716))
chip((116, 654, 470, 678), "producer  acks=all · idempotent · key=orderId", BLUE)
text((116, 694), "state: in-memory (scope decision — no database)", 12, FAINT, box=(COL1[0], 524, COL1[1], 716))

# Fulfillment Service (col2)
card((COL2[0], 524, COL2[1], 716), stripe=GREEN)
text((596, 540), "Fulfillment Service", 18, TXT, bold=True, box=(COL2[0], 524, COL2[1], 716))
chip((868, 542, 988, 566), ":8081 · ×2", GREEN)
text((596, 578), "idempotent consumer — eventId dedup (atomic)", 14, SOFT, box=(COL2[0], 524, COL2[1], 716))
text((596, 602), "markFulfilled — safe-to-repeat state transition", 14, SOFT, box=(COL2[0], 524, COL2[1], 716))
text((596, 630), "retry: 3 attempts · backoff 1s → 2s → 4s", 13, MUTED, box=(COL2[0], 524, COL2[1], 716))
text((596, 652), "exhausted → orders.DLT — partition never blocks", 13, MUTED, box=(COL2[0], 524, COL2[1], 716))
chip((596, 676, 716, 700), "Deployment ×2", GREEN)
chip((728, 676, 866, 700), "Service (ClusterIP)", GREEN)

# Operational API (col3)
card((COL3[0], 524, COL3[1], 716), stripe=INDIGO)
text((1076, 540), "Operational API", 18, TXT, bold=True, box=(COL3[0], 524, COL3[1], 716))
text((1076, 578), "GET  /__admin/stats", 13, SOFT, box=(COL3[0], 524, COL3[1], 716))
text((1076, 602), "GET  /__admin/orders/{id}/status", 13, SOFT, box=(COL3[0], 524, COL3[1], 716))
text((1076, 626), "POST /__admin/failure", 13, SOFT, box=(COL3[0], 524, COL3[1], 716))
text((1076, 654), "fault modes for the demos:", 12, MUTED, box=(COL3[0], 524, COL3[1], 716))
text((1076, 674), "NONE · ALWAYS · ONCE_PER_EVENT", 12, MUTED, box=(COL3[0], 524, COL3[1], 716))
text((1076, 694), "per-replica in-memory state", 12, FAINT, box=(COL3[0], 524, COL3[1], 716))

# ============================================================
# BOTTOM ROW (864..936)
# ============================================================
card((M, 864, 400, 936))
text((72, 880), "Client (curl)", 16, TXT, bold=True, box=(M, 864, 400, 936))
text((72, 908), "POST /orders · GET /orders/{id}", 13, MUTED, box=(M, 864, 400, 936))
polyline([(224, 864), (224, 716)], "#AEB8CC")   # lands on the Order Service card edge
text((242, 800), "HTTP", 12, MUTED)

card((440, 864, 1512, 936))
tracked((464, 877), "run it", 11, MUTED, (440, 864, 1512, 936), 2.4)
text((464, 902), "docker compose up -d --build", 12, SOFT, box=(440, 864, 1512, 936))
text((464, 922), "curl -X POST localhost:8080/orders -d '{…}'", 12, SOFT, box=(440, 864, 1512, 936))
text((960, 902), "kubectl apply -f k8s/namespace.yaml && kubectl apply -f k8s/", 12, SOFT, box=(440, 864, 1512, 936))
text((960, 922), "kubectl scale deploy fulfillment-service --replicas=3 -n eventflow", 12, SOFT, box=(440, 864, 1512, 936))

# ============================================================
# FOOTER
# ============================================================
text((M, 966), "▍", 13, AMBER, bold=True)
text((66, 966),
     "honest limitations — Kafka outage does not flip K8s probes · /__admin reads per-replica state · "
     "in-memory state lost on restart (Kafka offsets are durable) · AWS: documented workflow only",
     11, FAINT)
text((1512, 966), "generated by architecture/generate.py", 11, FAINT, anchor="ra")

# ============================================================
# MECHANICAL OVERLAP CHECKS
# ============================================================
def inter(a, b, pad=2.0):
    return not (a[2] + pad < b[0] or b[2] + pad < a[0] or a[3] + pad < b[1] or b[3] + pad < a[1])

def seg_rect(p1, p2, r, pad=1.0):
    (x1, y1), (x2, y2) = p1, p2
    rx1, ry1, rx2, ry2 = r[0] - pad, r[1] - pad, r[2] + pad, r[3] + pad
    if max(x1, x2) < rx1 or min(x1, x2) > rx2: return False
    if max(y1, y2) < ry1 or min(y1, y2) > ry2: return False
    if abs(x1 - x2) < 0.01:                     # vertical
        return min(y1, y2) <= ry2 and max(y1, y2) >= ry1 and rx1 <= x1 <= rx2
    if abs(y1 - y2) < 0.01:                     # horizontal
        return min(x1, x2) <= rx2 and max(x1, x2) >= rx1 and ry1 <= y1 <= ry2
    return inter((min(x1,x2), min(y1,y2), max(x1,x2), max(y1,y2)), r, 0)

for i in range(len(TEXTS)):
    for j in range(i + 1, len(TEXTS)):
        if inter(TEXTS[i][:4], TEXTS[j][:4]):
            VIOLATIONS.append(f"text∩text: '{TEXTS[i][4][:28]}' vs '{TEXTS[j][4][:28]}'")
for t in TEXTS:
    for seg in LINES:
        if seg_rect(seg[0], seg[1], t[:4]):
            VIOLATIONS.append(f"text∩line: '{t[4][:28]}' vs seg {seg}")
for i in range(len(RECTS)):
    for j in range(i + 1, len(RECTS)):
        if inter(RECTS[i][:4], RECTS[j][:4], pad=0):
            VIOLATIONS.append(f"rect∩rect: {RECTS[i][4]} vs {RECTS[j][4]}")

# ---------------- save ----------------
img = img.resize((W, H), Image.LANCZOS)
img.save(OUT, "PNG", optimize=True)

if VIOLATIONS:
    print("LAYOUT VIOLATIONS:", file=sys.stderr)
    for v in VIOLATIONS:
        print("  -", v, file=sys.stderr)
    sys.exit(1)
print(f"wrote {OUT} ({OUT.stat().st_size} bytes, {W}x{H}) — overlap checks passed "
      f"({len(TEXTS)} texts, {len(LINES)} segments, {len(RECTS)} rects)")
