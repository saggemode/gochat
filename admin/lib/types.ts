// Shared domain types for the GoChat admin panel.

export type Trend = {
  /** Period-over-period change as a ratio, e.g. 0.123 for +12.3%. */
  delta: number
  /** Sparkline / recent series for the metric. */
  series: number[]
}

export type KpiMetric = {
  key: string
  label: string
  value: number
  /** Formatting hint for the UI. */
  format: "number" | "currency" | "percent"
  trend: Trend
}

export type TimeseriesPoint = {
  date: string // ISO day
  value: number
}

export type MultiSeries = {
  name: string
  points: TimeseriesPoint[]
}

export type ActivityKind =
  | "signup"
  | "order"
  | "dispute"
  | "report"
  | "payout"
  | "call"
  | "message"

export type ActivityItem = {
  id: string
  kind: ActivityKind
  title: string
  subtitle: string
  at: string // ISO timestamp
  amount?: number
}

export type UserStatus = "active" | "suspended" | "banned" | "pending"

export type AdminUser = {
  id: string
  name: string
  phone: string
  email?: string
  avatarUrl?: string
  status: UserStatus
  verified: boolean
  isSeller: boolean
  messages: number
  orders: number
  joinedAt: string
  lastActiveAt: string
  country: string
  roles?: string[]
}

export type OrderStatus =
  | "pending"
  | "paid"
  | "shipped"
  | "delivered"
  | "cancelled"
  | "refunded"

export type AdminOrder = {
  id: string
  reference: string
  buyer: string
  store: string
  total: number
  status: OrderStatus
  items: number
  createdAt: string
}

export type DisputeStatus = "open" | "under_review" | "resolved" | "rejected"

export type AdminDispute = {
  id: string
  orderRef: string
  store: string
  buyer: string
  reason: string
  status: DisputeStatus
  amount: number
  openedAt: string
}

export type ReportStatus = "pending" | "actioned" | "dismissed"
export type ReportTarget = "message" | "user" | "story" | "channel" | "product"

export type AdminReport = {
  id: string
  target: ReportTarget
  reportedName: string
  reason: string
  reporter: string
  status: ReportStatus
  severity?: "low" | "medium" | "high"
  createdAt: string
  excerpt?: string
}

export type ServiceHealth = {
  name: string
  status: "healthy" | "degraded" | "down"
  latencyMs: number
  uptime: number // ratio
}
