// Formatting helpers shared across the admin UI.

const compact = new Intl.NumberFormat("en-US", {
  notation: "compact",
  maximumFractionDigits: 1,
})

const plain = new Intl.NumberFormat("en-US")

/** 12_400 -> "12.4K" */
export function formatCompact(n: number): string {
  return compact.format(n)
}

/** 12400 -> "12,400" */
export function formatNumber(n: number): string {
  return plain.format(n)
}

/** Nigerian Naira money formatting; amounts are in whole currency units. */
export function formatNaira(n: number, opts?: { compact?: boolean }): string {
  if (opts?.compact) return "₦" + compact.format(n)
  return new Intl.NumberFormat("en-NG", {
    style: "currency",
    currency: "NGN",
    maximumFractionDigits: 0,
  }).format(n)
}

/** 0.123 -> "+12.3%" (input is a ratio, not already a percent). */
export function formatDelta(ratio: number): string {
  const pct = ratio * 100
  const sign = pct > 0 ? "+" : ""
  return `${sign}${pct.toFixed(1)}%`
}

export function formatPercent(ratio: number, digits = 1): string {
  return `${(ratio * 100).toFixed(digits)}%`
}

export function formatDate(iso: string | number | Date): string {
  const d = new Date(iso)
  return d.toLocaleDateString("en-US", {
    month: "short",
    day: "numeric",
    year: "numeric",
  })
}

export function formatDateTime(iso: string | number | Date): string {
  const d = new Date(iso)
  return d.toLocaleString("en-US", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  })
}

/** "3m ago", "2h ago", "4d ago" */
export function formatRelative(iso: string | number | Date): string {
  const then = new Date(iso).getTime()
  const diff = Date.now() - then
  const sec = Math.round(diff / 1000)
  if (sec < 60) return `${sec}s ago`
  const min = Math.round(sec / 60)
  if (min < 60) return `${min}m ago`
  const hr = Math.round(min / 60)
  if (hr < 24) return `${hr}h ago`
  const day = Math.round(hr / 24)
  if (day < 30) return `${day}d ago`
  const mo = Math.round(day / 30)
  if (mo < 12) return `${mo}mo ago`
  return `${Math.round(mo / 12)}y ago`
}

/** Initials for an avatar fallback. */
export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? "")
    .join("")
}
