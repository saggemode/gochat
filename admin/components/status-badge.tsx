import { Badge } from "@/components/ui/badge"

type Variant = React.ComponentProps<typeof Badge>["variant"]

const MAP: Record<string, { variant: Variant; label?: string }> = {
  // user
  active: { variant: "success" },
  suspended: { variant: "warning" },
  banned: { variant: "destructive" },
  pending: { variant: "secondary" },
  // orders
  paid: { variant: "info" },
  shipped: { variant: "info" },
  delivered: { variant: "success" },
  cancelled: { variant: "secondary" },
  refunded: { variant: "warning" },
  // disputes
  open: { variant: "warning" },
  under_review: { variant: "info", label: "Under review" },
  resolved: { variant: "success" },
  rejected: { variant: "secondary" },
  // reports
  actioned: { variant: "success" },
  dismissed: { variant: "secondary" },
  // severity
  low: { variant: "secondary" },
  medium: { variant: "warning" },
  high: { variant: "destructive" },
}

function titleize(s: string) {
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase())
}

export function StatusBadge({ status }: { status: string }) {
  const cfg = MAP[status] ?? { variant: "outline" as Variant }
  return <Badge variant={cfg.variant}>{cfg.label ?? titleize(status)}</Badge>
}
