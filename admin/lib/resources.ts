// Data resources. REAL by default — every resource calls the gateway admin API
// (/api/v1/admin/*). Set NEXT_PUBLIC_USE_MOCKS=true to fall back to seeded
// fixtures (lib/mock.ts) for offline/local UI work.
//
// Real mode requires the signed-in account's email to be in the gateway's
// ADMIN_EMAILS allowlist, and NEXT_PUBLIC_API_BASE_URL pointing at the gateway.

import { apiFetch } from "./api"
import {
  mockActivity,
  mockDisputes,
  mockGrowthSeries,
  mockKpis,
  mockMessageMix,
  mockOrders,
  mockReports,
  mockRevenueSeries,
  mockServiceHealth,
  mockUsers,
} from "./mock"
import type {
  ActivityItem,
  AdminDispute,
  AdminOrder,
  AdminReport,
  AdminUser,
  KpiMetric,
  MultiSeries,
  ReportStatus,
  ServiceHealth,
  TimeseriesPoint,
  UserStatus,
} from "./types"

const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS === "true"

function delay<T>(value: T, ms = 300): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

// ── real API response shapes ──────────────────────────────────────────────
type ApiOverview = {
  users: { total: number; online_now: number; new_7d: number; new_30d: number }
  orders: { total: number; paid: number; gmv_paid: number }
  disputes: { open: number }
  signups_14d: { date: string; value: number }[]
}
type ApiUser = {
  id: string
  email: string
  name: string
  avatar_url: string
  phone: string
  phone_verified: boolean
  online: boolean
  last_active_at: string
  country: string
  joined_at: string
  status: UserStatus
  is_seller: boolean
  orders: number
  roles: string[] | null
}
type ApiDispute = {
  id: string
  order_ref: string
  reason: string
  status: AdminDispute["status"]
  store: string
  buyer: string
  amount: number
  opened_at: string
}
type ApiOrder = {
  id: string
  reference: string
  buyer: string
  store: string
  total: number
  status: AdminOrder["status"]
  items: number
  created_at: string
}
type ApiReport = {
  id: string
  target: AdminReport["target"]
  reason: string
  status: string
  created_at: string
  reporter: string
  reported_name: string
}
type ApiActivity = {
  id: string
  kind: ActivityItem["kind"]
  title: string
  subtitle: string
  at: string
  amount: number
}
type ApiServiceHealth = { name: string; status: ServiceHealth["status"] }

// ── adapters: API shape -> UI type ─────────────────────────────────────────
function overviewToKpis(o: ApiOverview): KpiMetric[] {
  const signups = o.signups_14d?.map((p) => p.value) ?? []
  const zero = { delta: 0, series: [] as number[] }
  return [
    { key: "users", label: "Total Users", value: o.users.total, format: "number", trend: { delta: 0, series: signups } },
    { key: "active", label: "Online Now", value: o.users.online_now, format: "number", trend: zero },
    { key: "new30", label: "New (30d)", value: o.users.new_30d, format: "number", trend: { delta: 0, series: signups } },
    { key: "gmv", label: "GMV (paid)", value: Math.round(o.orders.gmv_paid), format: "currency", trend: { delta: 0, series: [o.orders.gmv_paid] } },
    { key: "orders", label: "Paid Orders", value: o.orders.paid, format: "number", trend: zero },
    { key: "disputes", label: "Open Disputes", value: o.disputes.open, format: "number", trend: zero },
  ]
}

function apiUserToAdmin(u: ApiUser): AdminUser {
  return {
    id: u.id,
    name: u.name || u.email,
    phone: u.phone,
    email: u.email || undefined,
    avatarUrl: u.avatar_url || undefined,
    status: u.status ?? "active",
    verified: u.phone_verified,
    isSeller: u.is_seller,
    messages: 0, // not tracked per-user in the admin API
    orders: u.orders,
    joinedAt: u.joined_at,
    lastActiveAt: u.last_active_at,
    country: u.country || "—",
    roles: u.roles ?? [],
  }
}

function apiReportToAdmin(r: ApiReport): AdminReport {
  // The API has no 'reviewed' state in the UI union — fold it into pending.
  const status: ReportStatus = r.status === "actioned" || r.status === "dismissed" ? r.status : "pending"
  return {
    id: r.id,
    target: r.target,
    reportedName: r.reported_name,
    reason: r.reason || "Reported",
    reporter: r.reporter,
    status,
    createdAt: r.created_at,
  }
}

// ── reads ─────────────────────────────────────────────────────────────────
export async function getKpis(): Promise<KpiMetric[]> {
  if (USE_MOCKS) return delay(mockKpis())
  return overviewToKpis(await apiFetch<ApiOverview>("/api/v1/admin/overview"))
}

export async function getGrowthSeries(days = 30): Promise<MultiSeries[]> {
  if (USE_MOCKS) return delay(mockGrowthSeries())
  const res = await apiFetch<{ series: MultiSeries[] }>(`/api/v1/admin/metrics/growth?days=${days}`)
  return res.series ?? []
}

export async function getRevenueSeries(days = 30): Promise<TimeseriesPoint[]> {
  if (USE_MOCKS) return delay(mockRevenueSeries())
  const res = await apiFetch<{ points: TimeseriesPoint[] }>(`/api/v1/admin/metrics/revenue?days=${days}`)
  return res.points ?? []
}

export async function getMessageMix(): Promise<{
  items: { name: string; value: number }[]
  disabled: boolean
  reason?: string
}> {
  if (USE_MOCKS) return delay({ items: mockMessageMix(), disabled: false })
  const res = await apiFetch<{
    mix: { name: string; value: number }[]
    disabled?: boolean
    reason?: string
  }>("/api/v1/admin/metrics/messages")
  // Under always-on E2EE the server returns { disabled: true } — content
  // analytics can't be computed from ciphertext that's purged after delivery.
  return { items: res.mix ?? [], disabled: !!res.disabled, reason: res.reason }
}

export async function getActivity(): Promise<ActivityItem[]> {
  if (USE_MOCKS) return delay(mockActivity(14))
  const res = await apiFetch<{ items: ApiActivity[] }>("/api/v1/admin/activity")
  return (res.items ?? []).map((a) => ({
    id: a.id,
    kind: a.kind,
    title: a.title,
    subtitle: a.subtitle,
    at: a.at,
    amount: a.amount > 0 ? a.amount : undefined,
  }))
}

export type PageOpts = { search?: string; status?: string; limit?: number; offset?: number }
export type Paged<T> = { rows: T[]; total: number }

function pageQuery(opts: PageOpts): string {
  const p = new URLSearchParams()
  if (opts.search) p.set("search", opts.search)
  if (opts.status && opts.status !== "all") p.set("status", opts.status)
  p.set("limit", String(opts.limit ?? 25))
  p.set("offset", String(opts.offset ?? 0))
  return p.toString()
}

export async function getUsers(opts: PageOpts = {}): Promise<Paged<AdminUser>> {
  if (USE_MOCKS) {
    const all = mockUsers(60)
    return delay({ rows: all.slice(opts.offset ?? 0, (opts.offset ?? 0) + (opts.limit ?? 25)), total: all.length })
  }
  const res = await apiFetch<{ users: ApiUser[]; total: number }>(`/api/v1/admin/users?${pageQuery(opts)}`)
  return { rows: (res.users ?? []).map(apiUserToAdmin), total: res.total ?? 0 }
}

export async function getOrders(opts: PageOpts = {}): Promise<Paged<AdminOrder>> {
  const map = (o: ApiOrder): AdminOrder => ({
    id: o.id,
    reference: o.reference,
    buyer: o.buyer,
    store: o.store,
    total: o.total,
    status: o.status,
    items: o.items,
    createdAt: o.created_at,
  })
  if (USE_MOCKS) {
    const all = mockOrders(40)
    return delay({ rows: all.slice(opts.offset ?? 0, (opts.offset ?? 0) + (opts.limit ?? 25)), total: all.length })
  }
  const res = await apiFetch<{ orders: ApiOrder[]; total: number }>(`/api/v1/admin/orders?${pageQuery(opts)}`)
  return { rows: (res.orders ?? []).map(map), total: res.total ?? 0 }
}

export async function getDisputes(): Promise<AdminDispute[]> {
  if (USE_MOCKS) return delay(mockDisputes(18))
  const res = await apiFetch<{ disputes: ApiDispute[] }>("/api/v1/admin/disputes")
  return (res.disputes ?? []).map((d) => ({
    id: d.id,
    orderRef: d.order_ref || "—",
    store: d.store,
    buyer: d.buyer,
    reason: d.reason,
    status: d.status,
    amount: d.amount,
    openedAt: d.opened_at,
  }))
}

export async function getReports(): Promise<AdminReport[]> {
  if (USE_MOCKS) return delay(mockReports(24))
  const res = await apiFetch<{ reports: ApiReport[] }>("/api/v1/admin/reports")
  return (res.reports ?? []).map(apiReportToAdmin)
}

export async function getServiceHealth(): Promise<ServiceHealth[]> {
  if (USE_MOCKS) return delay(mockServiceHealth())
  const res = await apiFetch<{ services: ApiServiceHealth[] }>("/api/v1/admin/health")
  return (res.services ?? []).map((s) => ({ name: s.name, status: s.status, latencyMs: 0, uptime: 0 }))
}

// ── mutations ───────────────────────────────────────────────────────────────
export async function setUserStatus(id: string, status: UserStatus, reason = ""): Promise<void> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return
  }
  await apiFetch(`/api/v1/admin/users/${id}/status`, { method: "POST", body: { status, reason } })
}

// User detail (profile + roles + recent orders).
export type UserDetail = {
  user: AdminUser & { phoneVerified: boolean }
  orders: { reference: string; store: string; total: number; status: string; createdAt: string }[]
}

export async function getUserDetail(id: string): Promise<UserDetail> {
  if (USE_MOCKS) {
    const u = mockUsers(1)[0]
    return delay({ user: { ...u, phoneVerified: u.verified }, orders: [] })
  }
  const r = await apiFetch<{
    user: {
      id: string; email: string; name: string; avatar_url: string; phone: string
      phone_verified: boolean; online: boolean; last_active_at: string; country: string
      joined_at: string; status: UserStatus; roles: string[] | null
    }
    orders: { reference: string; store: string; total: number; status: string; created_at: string }[]
  }>(`/api/v1/admin/users/${id}`)
  const u = r.user
  return {
    user: {
      id: u.id, name: u.name || u.email, phone: u.phone, email: u.email || undefined,
      avatarUrl: u.avatar_url || undefined, status: u.status ?? "active", verified: u.phone_verified,
      phoneVerified: u.phone_verified, isSeller: false, messages: 0, orders: 0,
      joinedAt: u.joined_at, lastActiveAt: u.last_active_at, country: u.country || "—", roles: u.roles ?? [],
    },
    orders: (r.orders ?? []).map((o) => ({
      reference: o.reference, store: o.store, total: o.total, status: o.status, createdAt: o.created_at,
    })),
  }
}

export async function resetUserPassword(id: string): Promise<{ tempPassword: string; emailSent: boolean; email: string }> {
  if (USE_MOCKS) {
    await delay(null, 300)
    return { tempPassword: "Mock-temp-" + Math.random().toString(36).slice(2, 8), emailSent: false, email: "" }
  }
  const r = await apiFetch<{ temp_password?: string; email_sent?: boolean; email?: string }>(
    `/api/v1/admin/users/${id}/reset-password`,
    { method: "POST" },
  )
  return { tempPassword: r.temp_password ?? "", emailSent: !!r.email_sent, email: r.email ?? "" }
}

export async function resolveDispute(
  id: string,
  action: "resolved" | "rejected",
  note = "",
): Promise<void> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return
  }
  await apiFetch(`/api/v1/admin/disputes/${id}/resolve`, { method: "POST", body: { action, note } })
}

export async function setReportStatus(id: string, status: "actioned" | "dismissed", reason = ""): Promise<void> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return
  }
  await apiFetch(`/api/v1/admin/reports/${id}/status`, { method: "POST", body: { status, reason } })
}

// Removes the reported content (hide/unpublish/expire/ban/delete) and marks the
// report actioned.
export async function takedownReport(id: string, reason = ""): Promise<{ action: string }> {
  if (USE_MOCKS) {
    await delay(null, 300)
    return { action: "removed (mock)" }
  }
  return apiFetch<{ action: string }>(`/api/v1/admin/reports/${id}/takedown`, { method: "POST", body: { reason } })
}

// ── system metrics (gateway runtime, in-process) ──────────────────────────
export type SystemMetrics = {
  requestsTotal: number
  requests5xx: number
  errorRate: number
  avgLatencyMs: number
  goroutines: number
  memoryMb: number
  uptimeSeconds: number
}

export async function getSystemMetrics(): Promise<SystemMetrics> {
  if (USE_MOCKS) {
    return delay({ requestsTotal: 1234, requests5xx: 2, errorRate: 0.0016, avgLatencyMs: 42, goroutines: 48, memoryMb: 120, uptimeSeconds: 36000 })
  }
  const r = await apiFetch<{
    requests_total: number; requests_5xx: number; error_rate: number
    avg_latency_ms: number; goroutines: number; memory_mb: number; uptime_seconds: number
  }>("/api/v1/admin/metrics/system")
  return {
    requestsTotal: r.requests_total, requests5xx: r.requests_5xx, errorRate: r.error_rate,
    avgLatencyMs: r.avg_latency_ms, goroutines: r.goroutines, memoryMb: r.memory_mb, uptimeSeconds: r.uptime_seconds,
  }
}

// ── global search + session management ─────────────────────────────────────
export type SearchResults = {
  users: { id: string; name: string; email: string; status: string }[]
  orders: { reference: string; status: string; total: number }[]
  stores: { id: string; name: string }[]
}

export async function search(q: string): Promise<SearchResults> {
  const empty: SearchResults = { users: [], orders: [], stores: [] }
  if (q.trim().length < 2) return empty
  if (USE_MOCKS) return delay(empty)
  const r = await apiFetch<SearchResults>(`/api/v1/admin/search?q=${encodeURIComponent(q)}`)
  return { users: r.users ?? [], orders: r.orders ?? [], stores: r.stores ?? [] }
}

export async function forceLogout(id: string): Promise<{ sessionsRevoked: number }> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return { sessionsRevoked: 0 }
  }
  const r = await apiFetch<{ sessions_revoked: number }>(`/api/v1/admin/users/${id}/logout`, { method: "POST" })
  return { sessionsRevoked: r.sessions_revoked ?? 0 }
}

// ── payouts / coupons / broadcast ──────────────────────────────────────────
export type Payout = { store: string; orders: number; gross: number; commission: number; net: number }

export async function getPayouts(): Promise<{ payouts: Payout[]; commissionPercent: number }> {
  if (USE_MOCKS) {
    return delay({
      payouts: [{ store: "Lagos Threads", orders: 12, gross: 240000, commission: 24000, net: 216000 }],
      commissionPercent: 10,
    })
  }
  const r = await apiFetch<{ payouts: Payout[]; commission_percent: number }>("/api/v1/admin/payouts")
  return { payouts: r.payouts ?? [], commissionPercent: r.commission_percent ?? 0 }
}

export type Coupon = {
  id: string; code: string; store: string; discountType: string; discountValue: number
  minSpend: number; maxUses: number; usedCount: number; expiresAt?: string; isActive: boolean
}

export async function getCoupons(): Promise<Coupon[]> {
  if (USE_MOCKS) {
    return delay([
      { id: "c1", code: "WELCOME10", store: "Lagos Threads", discountType: "percentage", discountValue: 10, minSpend: 0, maxUses: 100, usedCount: 23, isActive: true },
    ])
  }
  const r = await apiFetch<{
    coupons: {
      id: string; code: string; store: string; discount_type: string; discount_value: number
      min_spend: number; max_uses: number; used_count: number; expires_at?: string; is_active: boolean
    }[]
  }>("/api/v1/admin/coupons")
  return (r.coupons ?? []).map((c) => ({
    id: c.id, code: c.code, store: c.store, discountType: c.discount_type, discountValue: c.discount_value,
    minSpend: c.min_spend, maxUses: c.max_uses, usedCount: c.used_count, expiresAt: c.expires_at, isActive: c.is_active,
  }))
}

export async function broadcast(title: string, body: string): Promise<{ recipients: number; sent: number }> {
  if (USE_MOCKS) {
    await delay(null, 400)
    return { recipients: 0, sent: 0 }
  }
  return apiFetch<{ recipients: number; sent: number }>("/api/v1/admin/broadcast", {
    method: "POST",
    body: { title, body },
  })
}

// ── identity / RBAC ───────────────────────────────────────────────────────
export type AdminMe = {
  userId: string
  email: string
  roles: string[]
  permissions: Record<string, boolean>
  bootstrap: boolean
  mustChangePassword: boolean
}

export async function getMe(): Promise<AdminMe> {
  if (USE_MOCKS) {
    return delay({
      userId: "mock",
      email: "admin@gochat.app",
      roles: ["super_admin"],
      permissions: {
        "admin:users:write": true,
        "admin:disputes:resolve": true,
        "admin:reports:action": true,
        "admin:finance:read": true,
        "admin:roles:write": true,
      },
      bootstrap: true,
      mustChangePassword: false,
    })
  }
  const r = await apiFetch<{
    user_id: string
    email: string
    roles: string[]
    permissions: Record<string, boolean>
    bootstrap: boolean
    must_change_password: boolean
  }>("/api/v1/admin/me")
  return {
    userId: r.user_id,
    email: r.email,
    roles: r.roles ?? [],
    permissions: r.permissions ?? {},
    bootstrap: !!r.bootstrap,
    mustChangePassword: !!r.must_change_password,
  }
}

export type AuditEntry = {
  id: string
  actorEmail: string
  action: string
  targetType: string
  targetId: string
  detail: string
  createdAt: string
}

type ApiAuditEntry = {
  id: string
  actor_email: string
  action: string
  target_type: string
  target_id: string
  detail: string
  created_at: string
}

type ApiStaff = {
  id: string
  email: string
  name: string
  avatar_url: string
  online: boolean
  last_active_at: string
  joined_at: string
  status: UserStatus
  roles: string[] | null
}

// SOURCE: gateway (/admin/staff) — accounts holding an admin role.
export async function getStaff(): Promise<AdminUser[]> {
  if (USE_MOCKS) {
    return delay([
      { id: "s1", name: "You (super admin)", phone: "", email: "admin@gochat.app", status: "active", verified: true, isSeller: false, messages: 0, orders: 0, joinedAt: new Date().toISOString(), lastActiveAt: new Date().toISOString(), country: "—", roles: ["super_admin"] },
    ])
  }
  const res = await apiFetch<{ staff: ApiStaff[] }>("/api/v1/admin/staff")
  return (res.staff ?? []).map((s) => ({
    id: s.id,
    name: s.name || s.email,
    phone: "",
    email: s.email || undefined,
    avatarUrl: s.avatar_url || undefined,
    status: s.status ?? "active",
    verified: true,
    isSeller: false,
    messages: 0,
    orders: 0,
    joinedAt: s.joined_at,
    lastActiveAt: s.last_active_at,
    country: "—",
    roles: s.roles ?? [],
  }))
}

// ── first-run setup + account/role management ──────────────────────────────
export type RoleInfo = { name: string; description: string }

export async function getSetupStatus(): Promise<{ needsSetup: boolean }> {
  if (USE_MOCKS) return delay({ needsSetup: false })
  const r = await apiFetch<{ needs_setup: boolean }>("/api/v1/admin/setup/status", { anonymous: true })
  return { needsSetup: !!r.needs_setup }
}

export type SetupResult = { token: string; name: string; email: string }

export async function setupSuperAdmin(email: string, password: string, name: string): Promise<SetupResult> {
  const r = await apiFetch<{ access_token?: string; user?: { display_name?: string; email?: string } }>(
    "/api/v1/admin/setup",
    { method: "POST", anonymous: true, body: { email, password, name } },
  )
  if (!r.access_token) throw new Error("Setup did not return a token")
  return { token: r.access_token, name: r.user?.display_name || name || email, email: r.user?.email || email }
}

export async function getRoles(): Promise<RoleInfo[]> {
  if (USE_MOCKS) {
    return delay([
      { name: "super_admin", description: "Full platform control" },
      { name: "admin", description: "All admin permissions" },
      { name: "moderator", description: "Ban users, resolve disputes, action reports" },
      { name: "support", description: "View console, action reports" },
      { name: "finance", description: "View revenue, orders, payouts" },
    ])
  }
  const r = await apiFetch<{ roles: RoleInfo[] }>("/api/v1/admin/roles")
  return r.roles ?? []
}

export type CreateUserResult = { tempPassword: string; emailSent: boolean }

export async function createUser(input: {
  email: string
  name: string
  roles: string[]
}): Promise<CreateUserResult> {
  if (USE_MOCKS) {
    await delay(null, 300)
    return { tempPassword: "Mock-temp-" + Math.random().toString(36).slice(2, 8), emailSent: false }
  }
  const r = await apiFetch<{ temp_password?: string; email_sent?: boolean }>("/api/v1/admin/users", {
    method: "POST",
    body: input,
  })
  return { tempPassword: r.temp_password ?? "", emailSent: !!r.email_sent }
}

export async function setUserRoles(id: string, roles: string[]): Promise<void> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return
  }
  await apiFetch(`/api/v1/admin/users/${id}/roles`, { method: "POST", body: { roles } })
}

export async function changePassword(newPassword: string): Promise<void> {
  if (USE_MOCKS) {
    await delay(null, 200)
    return
  }
  await apiFetch("/api/v1/admin/password", { method: "POST", body: { new_password: newPassword } })
}

export async function getAudit(): Promise<AuditEntry[]> {
  if (USE_MOCKS) return delay([] as AuditEntry[])
  const r = await apiFetch<{ entries: ApiAuditEntry[] }>("/api/v1/admin/audit")
  return (r.entries ?? []).map((e) => ({
    id: e.id,
    actorEmail: e.actor_email,
    action: e.action,
    targetType: e.target_type,
    targetId: e.target_id,
    detail: e.detail,
    createdAt: e.created_at,
  }))
}
