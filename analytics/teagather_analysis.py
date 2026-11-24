# -*- coding: utf-8 -*-
"""
Teagather 분석 (PyTorch)

Spring 의 AnalysisService 가 주문·메뉴·고객을 input.json 으로 내보내면 이 스크립트가
학습·예측을 하고 output.json 을 쓴다. 결과 모양은 com.teagather.analysis.dto.AnalysisResult 와 같다.

  python analytics/teagather_analysis.py --input analytics/work/input.json --output analytics/work/output.json

모델
  1. 고객 군집      : 7개 특징값(표준화) → 오토인코더(7→16→3) 임베딩 → K-평균(k=5)
  2. 이탈 위험      : 21일 전 시점 특징값 → 그 뒤 21일 안 재주문 여부를 로지스틱 회귀로 학습
  3. 개인 추천      : 고객×메뉴 구매 행렬(log1p) 행렬 분해 (메뉴 id 임베딩 + 속성 선형층, id dropout)
  4. 수요 예측      : 요일·전날·7일 전·7일/28일 평균·할인 여부·카테고리 → MLP (전 메뉴 공유)
  5. 신메뉴 후보    : 추천 모델의 속성 경로(콜드 스타트)로 후보 메뉴의 고객 선호도 예측
"""
import argparse
import datetime as dt
import json
import math
import sys
from collections import defaultdict

import torch
import torch.nn as nn

SEED = 42
DOW_KO = ["월", "화", "수", "목", "금", "토", "일"]
SEGMENT_INFO = {
    "VIP": ("VIP", "신메뉴 먼저 알림 · 감사 쿠폰", "결제 금액이 가장 큰 고객"),
    "LOYAL": ("단골", "스탬프 2배 이벤트", "자주, 꾸준히 오는 고객"),
    "NEW": ("신규", "두 번째 주문 할인 쿠폰", "가입한 지 얼마 안 된 고객"),
    "OCCASIONAL": ("가끔 방문", "오늘의 할인 메뉴 알림", "가끔 들르는 고객"),
    "AT_RISK": ("이탈 위험", "재방문 쿠폰 (7일 안 사용)", "한동안 주문이 없는 고객"),
}


def log(*a):
    print(*a, flush=True)


def d(s):
    return dt.date.fromisoformat(s)


def standardize(x, mean=None, std=None):
    if mean is None:
        mean = x.mean(dim=0)
        std = x.std(dim=0)
    std = torch.where(std < 1e-6, torch.ones_like(std), std)
    return (x - mean) / std, mean, std


def minmax(values):
    if not values:
        return []
    lo, hi = min(values), max(values)
    if hi - lo < 1e-9:
        return [0.5 for _ in values]
    return [(v - lo) / (hi - lo) for v in values]


# ---------------------------------------------------------------------------
# 1. 고객 특징값
# ---------------------------------------------------------------------------
def customer_features(customers, orders, products, ref):
    """ref 날짜 전(ref 미포함)까지의 주문으로 고객 특징값을 만든다. ref 전에 가입한 고객만."""
    cat_of = {p["id"]: p["categoryId"] for p in products}
    n_cat = max(1, len({p["categoryId"] for p in products}))
    by_c = defaultdict(list)
    for o in orders:
        od = d(o["date"])
        if od < ref:
            by_c[o["customerId"]].append((od, o))
    ids, rows = [], []
    for c in customers:
        created = d(c["created"])
        if created >= ref:
            continue
        hist = by_c.get(c["id"], [])
        tenure = (ref - created).days
        last = max((od for od, _ in hist), default=None)
        recency = (ref - last).days if last else min(tenure + 30, 150)
        recent = [(od, o) for od, o in hist if od >= ref - dt.timedelta(days=90)]
        freq = len(recent)
        money = sum(i["amount"] for _, o in recent for i in o["items"])
        basket = money / freq if freq else 0.0
        cats = {cat_of.get(i["productId"]) for _, o in recent for i in o["items"]}
        morning = sum(1 for _, o in recent if o["hour"] < 11) / freq if freq else 0.0
        ids.append(c["id"])
        rows.append([min(recency, 150), freq, math.log1p(money), basket / 1000.0, min(tenure, 365),
                     len(cats) / n_cat, morning])
    return ids, torch.tensor(rows, dtype=torch.float32) if rows else torch.zeros((0, 7))


class AutoEncoder(nn.Module):
    def __init__(self, n_in, n_z=3):
        super().__init__()
        self.enc = nn.Sequential(nn.Linear(n_in, 16), nn.Tanh(), nn.Linear(16, n_z))
        self.dec = nn.Sequential(nn.Linear(n_z, 16), nn.Tanh(), nn.Linear(16, n_in))

    def forward(self, x):
        z = self.enc(x)
        return z, self.dec(z)


def kmeans(z, k, iters=60):
    g = torch.Generator().manual_seed(SEED)
    n = z.shape[0]
    # k-means++ 초기화
    centers = [z[torch.randint(n, (1,), generator=g)].squeeze(0)]
    for _ in range(1, k):
        dist = torch.cdist(z, torch.stack(centers)).min(dim=1).values ** 2
        probs = dist / dist.sum() if dist.sum() > 0 else torch.full((n,), 1.0 / n)
        centers.append(z[torch.multinomial(probs, 1, generator=g)].squeeze(0))
    c = torch.stack(centers)
    for _ in range(iters):
        assign = torch.cdist(z, c).argmin(dim=1)
        new_c = torch.stack([z[assign == j].mean(dim=0) if (assign == j).any() else c[j] for j in range(k)])
        if torch.allclose(new_c, c, atol=1e-5):
            break
        c = new_c
    return torch.cdist(z, c).argmin(dim=1)


def label_clusters(raw, assign, k):
    """군집 중심(원래 단위)을 보고 VIP·단골·신규·가끔 방문·이탈 위험을 하나씩 붙인다."""
    stats = {}
    for j in range(k):
        m = assign == j
        if m.any():
            stats[j] = raw[m].mean(dim=0)
    left = set(stats)
    labels = {}

    def take(code, key):
        if not left:
            return
        j = max(left, key=key)
        labels[j] = code
        left.discard(j)

    take("NEW", lambda j: -stats[j][4])                     # 가입 경과가 가장 짧다
    take("AT_RISK", lambda j: stats[j][0])                  # 마지막 주문이 가장 오래됐다
    take("VIP", lambda j: stats[j][2])                      # 금액(log)이 가장 크다
    take("LOYAL", lambda j: stats[j][1])                    # 빈도가 가장 높다
    for j in left:
        labels[j] = "OCCASIONAL"
    return labels


def segment_customers(data, today):
    customers, orders, products = data["customers"], data["orders"], data["products"]
    ids, raw = customer_features(customers, orders, products, today + dt.timedelta(days=1))
    if raw.shape[0] < 6:
        return ids, {i: "OCCASIONAL" for i in ids}, 0.0
    x, _, _ = standardize(raw)
    torch.manual_seed(SEED)
    ae = AutoEncoder(x.shape[1])
    opt = torch.optim.Adam(ae.parameters(), lr=0.01)
    loss = torch.tensor(0.0)
    for _ in range(500):
        opt.zero_grad()
        _, rec = ae(x)
        loss = nn.functional.mse_loss(rec, x)
        loss.backward()
        opt.step()
    with torch.no_grad():
        z, _ = ae(x)
    k = 5
    assign = kmeans(z, k)
    labels = label_clusters(raw, assign, k)
    seg = {cid: labels[int(assign[i])] for i, cid in enumerate(ids)}
    log("고객 군집 : AE loss %.4f, 군집 %s" % (loss.item(), {c: list(seg.values()).count(c) for c in set(seg.values())}))
    return ids, seg, loss.item()


# ---------------------------------------------------------------------------
# 2. 이탈 위험 (로지스틱 회귀)
# ---------------------------------------------------------------------------
def churn_model(data, today, horizon=21):
    customers, orders, products = data["customers"], data["orders"], data["products"]
    cutoff = today - dt.timedelta(days=horizon - 1)
    ids0, raw0 = customer_features(customers, orders, products, cutoff)
    if raw0.shape[0] < 10:
        return {}, 0.0, 0.0
    after = defaultdict(int)
    before = defaultdict(int)
    for o in orders:
        od = d(o["date"])
        if od >= cutoff:
            after[o["customerId"]] += 1
        else:
            before[o["customerId"]] += 1
    keep = [i for i, cid in enumerate(ids0) if before[cid] > 0]
    if len(keep) < 10:
        return {}, 0.0, 0.0
    x0 = raw0[keep]
    y = torch.tensor([1.0 if after[ids0[i]] == 0 else 0.0 for i in keep]).unsqueeze(1)
    xs, mean, std = standardize(x0)
    torch.manual_seed(SEED)
    model = nn.Linear(xs.shape[1], 1)
    opt = torch.optim.Adam(model.parameters(), lr=0.05, weight_decay=1e-3)
    pos = y.mean().clamp(0.05, 0.95)
    lossf = nn.BCEWithLogitsLoss(pos_weight=((1 - pos) / pos))
    loss = torch.tensor(0.0)
    for _ in range(600):
        opt.zero_grad()
        loss = lossf(model(xs), y)
        loss.backward()
        opt.step()
    with torch.no_grad():
        acc = ((torch.sigmoid(model(xs)) > 0.5).float() == y).float().mean().item()
        ids1, raw1 = customer_features(customers, orders, products, today + dt.timedelta(days=1))
        x1, _, _ = standardize(raw1, mean, std)
        prob = torch.sigmoid(model(x1)).squeeze(1)
    log("이탈 위험 : BCE %.4f, 학습 정확도 %.0f%%, 학습 고객 %d명" % (loss.item(), acc * 100, len(keep)))
    return {cid: round(float(prob[i]), 3) for i, cid in enumerate(ids1)}, loss.item(), acc


# ---------------------------------------------------------------------------
# 3. 행렬 분해 추천 (+ 콜드 스타트)
# ---------------------------------------------------------------------------
class MF(nn.Module):
    def __init__(self, n_users, n_items, n_attr, dim=16):
        super().__init__()
        self.u = nn.Embedding(n_users, dim)
        self.v = nn.Embedding(n_items, dim)
        self.w = nn.Linear(n_attr, dim, bias=False)
        self.ub = nn.Embedding(n_users, 1)
        self.ab = nn.Linear(n_attr, 1)
        nn.init.normal_(self.u.weight, std=0.1)
        nn.init.zeros_(self.v.weight)
        nn.init.zeros_(self.ub.weight)

    def item_vec(self, attr, use_id=None):
        vec = self.w(attr)
        if use_id is not None:
            vec = vec + self.v.weight * use_id
        return vec

    def forward(self, attr, use_id):
        iv = self.item_vec(attr, use_id)                           # (I, d)
        return self.u.weight @ iv.T + self.ub.weight + self.ab(attr).T   # (U, I)


def attributes(products, categories):
    tags = sorted({t for p in products for t in p["tags"]})
    cat_ids = [c["id"] for c in categories]
    prices = [p["price"] for p in products]
    lo, hi = min(prices), max(prices)
    rows = []
    for p in products:
        r = [1.0 if p["categoryId"] == c else 0.0 for c in cat_ids]
        r += [1.0 if t in p["tags"] else 0.0 for t in tags]
        r.append((p["price"] - lo) / max(1, hi - lo))
        rows.append(r)
    return torch.tensor(rows, dtype=torch.float32)


def recommend(data, today):
    products, customers, orders = data["products"], data["customers"], data["orders"]
    sold = [p for p in products if p["status"] != "CANDIDATE"]
    cands = [p for p in products if p["status"] == "CANDIDATE"]
    allp = sold + cands
    pidx = {p["id"]: i for i, p in enumerate(allp)}
    active = sorted({o["customerId"] for o in orders})
    uidx = {c: i for i, c in enumerate(active)}
    if not active or not sold:
        return [], {}, {}, 0.0
    counts = torch.zeros((len(active), len(allp)))
    since = today - dt.timedelta(days=90)
    for o in orders:
        if d(o["date"]) < since:
            continue
        for it in o["items"]:
            if it["productId"] in pidx:
                counts[uidx[o["customerId"]], pidx[it["productId"]]] += it["qty"]
    target = torch.log1p(counts)
    n_sold = len(sold)
    attr = attributes(allp, data["categories"])
    weight = torch.where(target > 0, 1.0 + 2.0 * target, torch.full_like(target, 0.3))
    weight[:, n_sold:] = 0.0                                   # 후보 메뉴는 학습에 쓰지 않는다

    torch.manual_seed(SEED)
    model = MF(len(active), len(allp), attr.shape[1])
    opt = torch.optim.Adam(model.parameters(), lr=0.03, weight_decay=1e-4)
    loss = torch.tensor(0.0)
    for _ in range(700):
        opt.zero_grad()
        keep = (torch.rand(len(allp), 1) > 0.3).float()           # id dropout → 속성 경로도 배운다
        keep[n_sold:] = 0.0
        pred = model(attr, keep)
        loss = (weight * (pred - target) ** 2).sum() / weight.sum()
        loss.backward()
        opt.step()
    with torch.no_grad():
        use_id = torch.ones(len(allp), 1)
        use_id[n_sold:] = 0.0
        scores = model(attr, use_id)                              # (U, I)
        item_vec = model.item_vec(attr, use_id)
    log("추천 행렬 분해 : 가중 MSE %.4f, 고객 %d명 × 메뉴 %d개" % (loss.item(), len(active), len(allp)))

    # 고객별 추천 : 판매 중 메뉴, 가장 많이 산 2개는 빼고 상위 4개
    on_sale = [i for i, p in enumerate(sold) if p["status"] == "ON_SALE"]
    recs = []
    for c, ui in uidx.items():
        top_bought = set(torch.topk(counts[ui, :n_sold], k=min(2, n_sold)).indices.tolist())
        cand = [i for i in on_sale if i not in top_bought]
        cand.sort(key=lambda i: -float(scores[ui, i]))
        best = cand[:4]
        if not best:
            continue
        hi = float(scores[ui, best[0]])
        for r, i in enumerate(best):
            p = allp[i]
            reason = "자주 고르는 %s 취향과 잘 맞아요" % ("·".join(p["tags"][:2]) if p["tags"] else p["category"])
            recs.append({"customerId": c, "productId": p["id"], "rank": r + 1,
                         "score": round(max(0.0, float(scores[ui, i])) / max(1e-6, hi), 3), "reason": reason})

    # 메뉴별 고객 선호도 : 고객 상위 25% 예측 점수 평균 (후보 포함)
    q = max(1, len(active) // 4)
    raw_aff = {}
    fits = {}
    med = scores[:, :n_sold].median(dim=1).values
    for i, p in enumerate(allp):
        col = scores[:, i]
        raw_aff[p["id"]] = float(torch.topk(col, q).values.mean())
        # 잘 맞는 고객 : 이 메뉴 점수가 그 고객의 판매 메뉴 점수 중앙값보다 높다
        fits[p["id"]] = int((col > med).sum())
    vals = list(raw_aff.values())
    lo, hi = min(vals), max(vals)
    affinity = {pid: round((v - lo) / max(1e-6, hi - lo), 3) for pid, v in raw_aff.items()}

    # 후보와 가장 가까운 판매 메뉴 (메뉴 벡터 코사인)
    nearest = {}
    if cands:
        norm = nn.functional.normalize(item_vec, dim=1)
        sim = norm[n_sold:] @ norm[:n_sold].T
        for k, p in enumerate(cands):
            nearest[p["id"]] = sold[int(sim[k].argmax())]["name"]
    return recs, affinity, {"fits": fits, "nearest": nearest, "n": len(active)}, loss.item()


# ---------------------------------------------------------------------------
# 4. 수요 예측 (MLP)
# ---------------------------------------------------------------------------
class DemandMLP(nn.Module):
    def __init__(self, n_in):
        super().__init__()
        self.net = nn.Sequential(nn.Linear(n_in, 32), nn.ReLU(), nn.Linear(32, 16), nn.ReLU(), nn.Linear(16, 1))

    def forward(self, x):
        return self.net(x).squeeze(1)


def forecast(data, today, days=90):
    products = [p for p in data["products"] if p["status"] != "CANDIDATE"]
    start = today - dt.timedelta(days=days)
    pidx = {p["id"]: i for i, p in enumerate(products)}
    q = torch.zeros((len(products), days))                      # 0 = 90일 전, days-1 = 어제
    rev30 = defaultdict(float)
    for o in data["orders"]:
        od = d(o["date"])
        t = (od - start).days
        for it in o["items"]:
            i = pidx.get(it["productId"])
            if i is None:
                continue
            if 0 <= t < days:
                q[i, t] += it["qty"]
            if today - dt.timedelta(days=30) <= od < today:
                rev30[it["productId"]] += it["amount"]
    disc = torch.zeros((len(products), days + 1))
    for x in data["discounts"]:
        i = pidx.get(x["productId"])
        if i is None:
            continue
        for t in range(days + 1):
            day = start + dt.timedelta(days=t)
            if d(x["start"]) <= day <= d(x["end"]):
                disc[i, t] = 1.0
    cats = sorted({p["categoryId"] for p in products})
    scale = q.mean(dim=1) + 1.0

    def feat(i, t):
        day = start + dt.timedelta(days=t)
        dow = [1.0 if day.weekday() == k else 0.0 for k in range(7)]
        s = scale[i]
        lag1 = q[i, t - 1] / s
        lag7 = q[i, t - 7] / s
        m7 = q[i, t - 7:t].mean() / s
        m28 = q[i, t - 28:t].mean() / s
        cat = [1.0 if products[i]["categoryId"] == c else 0.0 for c in cats]
        return dow + [float(lag1), float(lag7), float(m7), float(m28), float(disc[i, t])] + cat

    xs, ys, ts = [], [], []
    for i in range(len(products)):
        for t in range(28, days):
            xs.append(feat(i, t))
            ys.append(float(q[i, t] / scale[i]))
            ts.append(t)
    x = torch.tensor(xs)
    y = torch.tensor(ys)
    tt = torch.tensor(ts)
    train = tt < days - 7
    test = ~train
    torch.manual_seed(SEED)
    model = DemandMLP(x.shape[1])
    opt = torch.optim.Adam(model.parameters(), lr=0.01, weight_decay=1e-4)
    for _ in range(400):
        opt.zero_grad()
        loss = nn.functional.mse_loss(model(x[train]), y[train])
        loss.backward()
        opt.step()
    with torch.no_grad():
        sc_rows = torch.tensor([float(scale[i]) for i in range(len(products)) for _ in range(28, days)])
        mae = ((model(x[test]) - y[test]).abs() * sc_rows[test]).mean().item()
        # 오늘 예측 : 어제까지의 판매로
        x_today = torch.tensor([feat(i, days) for i in range(len(products))])
        pred = (model(x_today).clamp(min=0) * scale).tolist()
    log("수요 예측 MLP : 최근 7일 MAE %.2f개 (메뉴 %d개)" % (mae, len(products)))

    out = {}
    uplift = {}
    for i, p in enumerate(products):
        last14 = float(q[i, days - 14:].sum())
        prev14 = float(q[i, days - 28:days - 14].sum())
        trend = (last14 - prev14) / prev14 * 100 if prev14 > 0 else (100.0 if last14 > 0 else 0.0)
        on = disc[i, :days] > 0
        if on.any() and (~on).any():
            a, b = float(q[i, :days][on].mean()), float(q[i, :days][~on].mean())
            if b > 0:
                uplift[p["id"]] = (a - b) / b * 100
        out[p["id"]] = {"qty30": int(q[i, days - 30:].sum()), "revenue30": int(rev30[p["id"]]),
                        "trendPct": round(trend, 1), "forecast": round(pred[i], 1)}
    return out, uplift, mae


# ---------------------------------------------------------------------------
# 5. 고르기
# ---------------------------------------------------------------------------
def pick_menus(data, today, metric, affinity, uplift, extra):
    prods = {p["id"]: p for p in data["products"]}
    on_sale = [p for p in data["products"] if p["status"] == "ON_SALE" and p["id"] in metric]
    dow = DOW_KO[today.weekday()]

    def margin(p):
        return (p["price"] - p["cost"]) / p["price"] if p["price"] else 0.0

    # 오늘의 메뉴
    fc = minmax([metric[p["id"]]["forecast"] for p in on_sale])
    tr = minmax([max(-60.0, min(60.0, metric[p["id"]]["trendPct"])) for p in on_sale])
    mg = minmax([margin(p) for p in on_sale])
    today_scores = sorted(((0.6 * fc[k] + 0.2 * tr[k] + 0.2 * mg[k], p) for k, p in enumerate(on_sale)),
                          key=lambda x: -x[0])
    today_pick = []
    for r, (s, p) in enumerate(today_scores[:3]):
        m = metric[p["id"]]
        today_pick.append({"productId": p["id"], "rank": r + 1, "score": round(s, 3), "forecast": m["forecast"],
                           "suggestedRate": None,
                           "reason": "오늘(%s) 예상 %.0f개 · 2주 추세 %+.0f%%" % (dow, m["forecast"], m["trendPct"])})
    today_ids = {x["productId"] for x in today_pick}

    # 할인 추천
    cat_fc = defaultdict(list)
    for p in on_sale:
        cat_fc[p["categoryId"]].append(metric[p["id"]]["forecast"])
    pool = [p for p in on_sale if p["id"] not in today_ids and not p["discountedNow"] and margin(p) >= 0.55
            and (metric[p["id"]]["trendPct"] < 0
                 or metric[p["id"]]["forecast"] < sum(cat_fc[p["categoryId"]]) / len(cat_fc[p["categoryId"]]))]
    disc_pick = []
    if pool:
        mg2 = minmax([margin(p) for p in pool])
        dec = minmax([max(0.0, -metric[p["id"]]["trendPct"]) for p in pool])
        low = minmax([metric[p["id"]]["forecast"] for p in pool])
        ranked = sorted(((0.4 * mg2[k] + 0.4 * dec[k] + 0.2 * (1 - low[k]), p) for k, p in enumerate(pool)),
                        key=lambda x: -x[0])
        for r, (s, p) in enumerate(ranked[:3]):
            m = metric[p["id"]]
            mr = margin(p)
            rate = 20 if mr >= 0.7 else (15 if mr >= 0.62 else 10)
            reason = "2주 판매 %+.0f%% · 마진 %.0f%% → %d%% 할인 여유" % (m["trendPct"], mr * 100, rate)
            if p["id"] in uplift and uplift[p["id"]] > 0:
                reason += " · 지난 할인 때 판매 +%.0f%%" % uplift[p["id"]]
            disc_pick.append({"productId": p["id"], "rank": r + 1, "score": round(s, 3), "forecast": m["forecast"],
                              "suggestedRate": rate, "reason": reason})

    # 신메뉴 후보
    cands = [p for p in data["products"] if p["status"] == "CANDIDATE" and p["id"] in affinity]
    cands.sort(key=lambda p: -affinity[p["id"]])
    new_pick = []
    for r, p in enumerate(cands[:3]):
        fits = extra.get("fits", {}).get(p["id"], 0)
        near = extra.get("nearest", {}).get(p["id"], "-")
        new_pick.append({"productId": p["id"], "rank": r + 1, "score": affinity[p["id"]], "forecast": 0.0,
                         "suggestedRate": None,
                         "reason": "잘 맞는 고객 %d명 (전체 %d명) · 비슷한 인기 메뉴 %s" % (fits, extra.get("n", 0), near)})
    return disc_pick, today_pick, new_pick


# ---------------------------------------------------------------------------
def segment_summary(data, today, seg):
    prods = {p["id"]: p for p in data["products"]}
    by_c = defaultdict(list)
    for o in data["orders"]:
        by_c[o["customerId"]].append(o)
    since = today - dt.timedelta(days=90)
    out = []
    for code in ["VIP", "LOYAL", "NEW", "OCCASIONAL", "AT_RISK"]:
        members = [c for c, s in seg.items() if s == code]
        if not members:
            continue
        orders_n, spent, rec = [], [], []
        cat_count = defaultdict(int)
        for c in members:
            os_ = [o for o in by_c.get(c, []) if d(o["date"]) >= since]
            orders_n.append(len(os_))
            spent.append(sum(i["amount"] for o in os_ for i in o["items"]))
            last = max((d(o["date"]) for o in by_c.get(c, [])), default=None)
            rec.append((today - last).days if last else 120)
            for o in by_c.get(c, []):
                for i in o["items"]:
                    if i["productId"] in prods:
                        cat_count[prods[i["productId"]]["category"]] += i["qty"]
        label, action, desc = SEGMENT_INFO[code]
        out.append({"code": code, "label": label, "count": len(members),
                    "avgOrders": round(sum(orders_n) / len(members), 1),
                    "avgSpent": round(sum(spent) / len(members)),
                    "avgRecency": round(sum(rec) / len(members), 1),
                    "topCategory": max(cat_count, key=cat_count.get) if cat_count else "-",
                    "action": action, "description": desc})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True)
    ap.add_argument("--output", required=True)
    args = ap.parse_args()
    torch.manual_seed(SEED)
    torch.set_num_threads(max(1, min(4, torch.get_num_threads())))

    with open(args.input, encoding="utf-8") as f:
        data = json.load(f)
    today = d(data["today"])
    log("입력 : 고객 %d명, 주문 %d건, 메뉴 %d개 (기준일 %s)" % (
        len(data["customers"]), len(data["orders"]), len(data["products"]), today))

    ids, seg, ae_loss = segment_customers(data, today)
    churn, bce, acc = churn_model(data, today)
    recs, affinity, extra, mf_loss = recommend(data, today)
    metric, uplift, mae = forecast(data, today)
    disc_pick, today_pick, new_pick = pick_menus(data, today, metric, affinity, uplift, extra)

    metrics = []
    for p in data["products"]:
        if p["id"] not in metric:
            continue
        m = metric[p["id"]]
        metrics.append({"productId": p["id"], "qty30": m["qty30"], "revenue30": m["revenue30"],
                        "trendPct": m["trendPct"], "forecast": m["forecast"],
                        "marginRate": round((p["price"] - p["cost"]) / p["price"], 3) if p["price"] else 0.0,
                        "affinity": affinity.get(p["id"], 0.0)})

    customers_out = [{"id": c, "segment": s, "label": SEGMENT_INFO[s][0], "churnRisk": churn.get(c)}
                     for c, s in seg.items()]
    result = {
        "engine": "PyTorch " + torch.__version__,
        "lossText": "오토인코더 MSE %.4f · 이탈 BCE %.4f (정확도 %.0f%%) · 추천 MSE %.4f · 수요 MAE %.2f개(최근 7일)"
                    % (ae_loss, bce, acc * 100, mf_loss, mae),
        "segments": segment_summary(data, today, seg),
        "customers": customers_out,
        "recommendations": recs,
        "metrics": metrics,
        "discount": disc_pick,
        "today": today_pick,
        "newMenu": new_pick,
    }
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False)
    log("완료 : 할인 %d · 오늘의 메뉴 %d · 신메뉴 후보 %d · 추천 %d건" % (
        len(disc_pick), len(today_pick), len(new_pick), len(recs)))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # Spring 이 마지막 줄을 실패 이유로 보여 준다
        import traceback
        traceback.print_exc()
        print("ERROR %s: %s" % (type(e).__name__, e), flush=True)
        sys.exit(1)
