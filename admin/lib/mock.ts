// Deterministic mock data for the admin panel. Seeded so values are stable
// across renders and reloads (no hydration mismatch, no flicker). Every
// generator here is consumed via lib/resources.ts, which is the single seam
// where a mock is swapped for a real gateway call.

import type {
  ActivityItem,
  ActivityKind,
  AdminDispute,
  AdminOrder,
  AdminReport,
  AdminUser,
  KpiMetric,
  MultiSeries,
  ServiceHealth,
  TimeseriesPoint,
} from "./types"

// --- seeded RNG (mulberry32) ---
function rng(seed: number) {
  let a = seed
  return () => {
    a |= 0
    a = (a + 0x6d2b79f5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

function pick<T>(r: () => number, arr: T[]): T {
  return arr[Math.floor(r() * arr.length)]
}

function daysAgoISO(days: number, r?: () => number): string {
  const d = new Date()
  d.setDate(d.getDate() - days)
  if (r) d.setHours(Math.floor(r() * 24), Math.floor(r() * 60))
  return d.toISOString()
}

const FIRST = ["Ada", "Chidi", "Zara", "Tunde", "Ngozi", "Emeka", "Fatima", "Kemi", "Bola", "Ibrahim", "Amara", "Yusuf", "Chioma", "Musa", "Grace", "David", "Blessing", "Samuel", "Halima", "Peter"]
const LAST = ["Okafor", "Bello", "Eze", "Abubakar", "Nwosu", "Adeyemi", "Okonkwo", "Sani", "Obi", "Lawal", "Chukwu", "Mohammed", "Balogun", "Ike", "Danjuma"]
const STORES = ["Lagos Threads", "Naija Gadgets", "Abuja Kitchen", "Kano Styles", "PH Electronics", "Ankara House", "Jollof Spot", "Zuma Beauty"]
const COUNTRIES = ["NG", "GH", "KE", "ZA", "US", "GB"]

function fullName(r: () => number): string {
  return `${pick(r, FIRST)} ${pick(r, LAST)}`
}

function phone(r: () => number): string {
  return "+234" + String(Math.floor(700000000 + r() * 99999999))
}

// --- KPIs ---
export function mockKpis(): KpiMetric[] {
  const r = rng(42)
  const spark = (base: number, vol: number) =>
    Array.from({ length: 14 }, (_, i) => Math.round(base + Math.sin(i / 2) * vol + r() * vol))
  return [
    { key: "users", label: "Total Users", value: 48213, format: "number", trend: { delta: 0.082, series: spark(1200, 120) } },
    { key: "active", label: "Active Today", value: 12874, format: "number", trend: { delta: 0.041, series: spark(900, 90) } },
    { key: "messages", label: "Messages (24h)", value: 1842990, format: "number", trend: { delta: 0.123, series: spark(60000, 8000) } },
    { key: "gmv", label: "GMV (30d)", value: 28470000, format: "currency", trend: { delta: 0.187, series: spark(900000, 120000) } },
    { key: "orders", label: "Orders (30d)", value: 9243, format: "number", trend: { delta: -0.021, series: spark(300, 40) } },
    { key: "disputes", label: "Open Disputes", value: 37, format: "number", trend: { delta: -0.14, series: spark(5, 2) } },
  ]
}

// --- time series for the main chart ---
export function mockGrowthSeries(): MultiSeries[] {
  const r = rng(7)
  const days = 30
  const mk = (base: number, growth: number, vol: number): TimeseriesPoint[] =>
    Array.from({ length: days }, (_, i) => ({
      date: daysAgoISO(days - 1 - i),
      value: Math.round(base + i * growth + (r() - 0.5) * vol),
    }))
  return [
    { name: "New Users", points: mk(820, 14, 160) },
    { name: "Active Users", points: mk(9200, 120, 900) },
  ]
}

export function mockRevenueSeries(): TimeseriesPoint[] {
  const r = rng(13)
  const days = 30
  return Array.from({ length: days }, (_, i) => ({
    date: daysAgoISO(days - 1 - i),
    value: Math.round(620000 + i * 9000 + (r() - 0.4) * 180000),
  }))
}

export function mockMessageMix(): { name: string; value: number }[] {
  return [
    { name: "Text", value: 1420000 },
    { name: "Media", value: 286000 },
    { name: "Voice", value: 92000 },
    { name: "Calls", value: 44990 },
  ]
}

// --- recent activity feed ---
export function mockActivity(n = 12): ActivityItem[] {
  const r = rng(99)
  const kinds: ActivityKind[] = ["signup", "order", "dispute", "report", "payout", "call"]
  const items: ActivityItem[] = []
  for (let i = 0; i < n; i++) {
    const kind = pick(r, kinds)
    const who = fullName(r)
    const minutesAgo = Math.floor(r() * 600)
    const when = new Date(Date.now() - minutesAgo * 60000).toISOString()
    switch (kind) {
      case "signup":
        items.push({ id: `a${i}`, kind, title: `${who} joined`, subtitle: pick(r, COUNTRIES), at: when })
        break
      case "order":
        items.push({ id: `a${i}`, kind, title: `New order from ${who}`, subtitle: pick(r, STORES), at: when, amount: Math.floor(5000 + r() * 90000) })
        break
      case "dispute":
        items.push({ id: `a${i}`, kind, title: `Dispute opened by ${who}`, subtitle: pick(r, STORES), at: when })
        break
      case "report":
        items.push({ id: `a${i}`, kind, title: `Content reported`, subtitle: `by ${who}`, at: when })
        break
      case "payout":
        items.push({ id: `a${i}`, kind, title: `Payout to ${pick(r, STORES)}`, subtitle: "Paystack", at: when, amount: Math.floor(20000 + r() * 400000) })
        break
      default:
        items.push({ id: `a${i}`, kind, title: `Call completed`, subtitle: `${who}`, at: when })
    }
  }
  return items.sort((a, b) => +new Date(b.at) - +new Date(a.at))
}

// --- users ---
export function mockUsers(n = 60): AdminUser[] {
  const r = rng(2024)
  const statuses: AdminUser["status"][] = ["active", "active", "active", "active", "suspended", "banned", "pending"]
  return Array.from({ length: n }, (_, i) => {
    const name = fullName(r)
    const joined = Math.floor(r() * 400)
    return {
      id: `u_${1000 + i}`,
      name,
      phone: phone(r),
      email: r() > 0.4 ? `${name.toLowerCase().replace(/\s+/g, ".")}@mail.com` : undefined,
      status: pick(r, statuses),
      verified: r() > 0.3,
      isSeller: r() > 0.7,
      messages: Math.floor(r() * 24000),
      orders: Math.floor(r() * 120),
      joinedAt: daysAgoISO(joined),
      lastActiveAt: daysAgoISO(Math.floor(r() * joined)),
      country: pick(r, COUNTRIES),
    }
  })
}

// --- orders ---
export function mockOrders(n = 40): AdminOrder[] {
  const r = rng(555)
  const statuses: AdminOrder["status"][] = ["pending", "paid", "paid", "shipped", "delivered", "delivered", "cancelled", "refunded"]
  return Array.from({ length: n }, (_, i) => ({
    id: `o_${5000 + i}`,
    reference: "GC-" + String(100000 + Math.floor(r() * 899999)),
    buyer: fullName(r),
    store: pick(r, STORES),
    total: Math.floor(3000 + r() * 180000),
    status: pick(r, statuses),
    items: 1 + Math.floor(r() * 6),
    createdAt: daysAgoISO(Math.floor(r() * 30), r),
  }))
}

// --- disputes ---
export function mockDisputes(n = 18): AdminDispute[] {
  const r = rng(321)
  const reasons = ["Item not received", "Wrong item", "Damaged on arrival", "Not as described", "Refund not processed"]
  const statuses: AdminDispute["status"][] = ["open", "open", "under_review", "resolved", "rejected"]
  return Array.from({ length: n }, (_, i) => ({
    id: `d_${700 + i}`,
    orderRef: "GC-" + String(100000 + Math.floor(r() * 899999)),
    store: pick(r, STORES),
    buyer: fullName(r),
    reason: pick(r, reasons),
    status: pick(r, statuses),
    amount: Math.floor(4000 + r() * 120000),
    openedAt: daysAgoISO(Math.floor(r() * 20), r),
  }))
}

// --- reports / moderation ---
export function mockReports(n = 24): AdminReport[] {
  const r = rng(808)
  const targets: AdminReport["target"][] = ["message", "user", "story", "channel", "product"]
  const reasons = ["Spam", "Harassment", "Scam / fraud", "Nudity", "Hate speech", "Counterfeit goods", "Misinformation"]
  const statuses: AdminReport["status"][] = ["pending", "pending", "pending", "actioned", "dismissed"]
  const sev: AdminReport["severity"][] = ["low", "medium", "medium", "high"]
  const excerpts = ["Click this link to win ₦500,000 now!!!", "You idiot stop messaging me", "Send ₦ to this account for cheap iPhones", "Original Gucci bag — 100% authentic", ""]
  return Array.from({ length: n }, (_, i) => ({
    id: `r_${300 + i}`,
    target: pick(r, targets),
    reportedName: fullName(r),
    reason: pick(r, reasons),
    reporter: fullName(r),
    status: pick(r, statuses),
    severity: pick(r, sev),
    createdAt: daysAgoISO(Math.floor(r() * 14), r),
    excerpt: pick(r, excerpts) || undefined,
  }))
}

// --- service health (maps to the real /metrics + /readyz you built) ---
export function mockServiceHealth(): ServiceHealth[] {
  const r = rng(11)
  const names = ["gateway", "auth", "chat", "group", "call", "business", "media", "social", "story", "channel", "miniapp", "ai"]
  return names.map((name) => {
    const roll = r()
    const status: ServiceHealth["status"] = roll > 0.93 ? "degraded" : roll > 0.98 ? "down" : "healthy"
    return {
      name,
      status,
      latencyMs: Math.floor(8 + r() * 120),
      uptime: 0.99 + r() * 0.0099,
    }
  })
}
