// Data resources: the ONE place mocks are swapped for real gateway calls.
//
// Mock mode (default) returns seeded fixtures (lib/mock.ts). Set
// NEXT_PUBLIC_USE_MOCKS=false to hit the real gateway admin API
// (/api/v1/admin/*), which requires the signed-in account's email to be in the
// gateway's ADMIN_EMAILS allowlist.
//
// Three resources are wired to real endpoints that now exist:
//   getKpis      -> GET /admin/overview
//   getUsers     -> GET /admin/users
//   getDisputes  -> GET /admin/disputes
// The rest (growth/revenue/message-mix/activity/service-health) stay on mock
// until matching endpoints are built, and are annotated SOURCE: mock below.

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
import type { AdminDispute, AdminUser, KpiMetric } from "./types"

const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS !== "false"

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

// ── adapters: API shape -> UI type ─────────────────────────────────────────
function overviewToKpis(o: ApiOverview): KpiMetric[] {
  const signups = o.signups_14d?.map((p) => p.value) ?? []
  const flat = (n: number) => [n]
  const zero = { delta: 0, series: [] as number[] }
  return [
    { key: "users", label: "Total Users", value: o.users.total, format: "number", trend: { delta: 0, series: signups } },
    { key: "active", label: "Online Now", value: o.users.online_now, format: "number", trend: zero },
    { key: "new30", label: "New (30d)", value: o.users.new_30d, format: "number", trend: { delta: 0, series: signups } },
    { key: "gmv", label: "GMV (paid)", value: Math.round(o.orders.gmv_paid), format: "currency", trend: { delta: 0, series: flat(o.orders.gmv_paid) } },
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
    // core.users has no status column yet — everyone reads as active until a
    // moderation-state migration lands. verified maps to phone_verified.
    status: "active",
    verified: u.phone_verified,
    isSeller: false,
    messages: 0,
    orders: 0,
    joinedAt: u.joined_at,
    lastActiveAt: u.last_active_at,
    country: u.country || "—",
  }
}

function apiDisputeToAdmin(d: ApiDispute): AdminDispute {
  return {
    id: d.id,
    orderRef: d.order_ref || "—",
    store: d.store,
    buyer: d.buyer,
    reason: d.reason,
    status: d.status,
    amount: d.amount,
    openedAt: d.opened_at,
  }
}

// ── resources ───────────────────────────────────────────────────────────────

// SOURCE: gateway (/admin/overview) when NEXT_PUBLIC_USE_MOCKS=false.
export async function getKpis(): Promise<KpiMetric[]> {
  if (USE_MOCKS) return delay(mockKpis())
  const o = await apiFetch<ApiOverview>("/api/v1/admin/overview")
  return overviewToKpis(o)
}

// SOURCE: gateway (/admin/users) when NEXT_PUBLIC_USE_MOCKS=false.
export async function getUsers(): Promise<AdminUser[]> {
  if (USE_MOCKS) return delay(mockUsers(60))
  const res = await apiFetch<{ users: ApiUser[] }>("/api/v1/admin/users?limit=200")
  return (res.users ?? []).map(apiUserToAdmin)
}

// SOURCE: gateway (/admin/disputes) when NEXT_PUBLIC_USE_MOCKS=false.
export async function getDisputes(): Promise<AdminDispute[]> {
  if (USE_MOCKS) return delay(mockDisputes(18))
  const res = await apiFetch<{ disputes: ApiDispute[] }>("/api/v1/admin/disputes")
  return (res.disputes ?? []).map(apiDisputeToAdmin)
}

// SOURCE: mock — need /admin/metrics timeseries endpoints.
export const getGrowthSeries = () => delay(mockGrowthSeries())
export const getRevenueSeries = () => delay(mockRevenueSeries())
export const getMessageMix = () => delay(mockMessageMix())
export const getActivity = () => delay(mockActivity(14))

// SOURCE: mock — the business service has order data, but no admin list route.
export const getOrders = () => delay(mockOrders(40))

// SOURCE: mock — need a moderation/reports service + endpoint.
export const getReports = () => delay(mockReports(24))

// SOURCE: mock — each service exposes /readyz + /metrics (pkg/health); needs an
// aggregated admin health endpoint to be real.
export const getServiceHealth = () => delay(mockServiceHealth())
