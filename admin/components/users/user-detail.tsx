"use client"

import { useQuery } from "@tanstack/react-query"
import { Dialog } from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { StatusBadge } from "@/components/status-badge"
import { getUserDetail } from "@/lib/resources"
import { formatDate, formatNaira, formatRelative } from "@/lib/format"

export function UserDetailDialog({ userId, onClose }: { userId: string | null; onClose: () => void }) {
  const { data, isLoading } = useQuery({
    queryKey: ["user", userId],
    queryFn: () => getUserDetail(userId as string),
    enabled: !!userId,
  })

  const u = data?.user

  return (
    <Dialog open={!!userId} onClose={onClose} title={u?.name ?? "User"} description={u?.email}>
      {isLoading || !u ? (
        <div className="space-y-2">
          <Skeleton className="h-5 w-40" />
          <Skeleton className="h-20 w-full" />
        </div>
      ) : (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={u.status} />
            {u.verified ? <Badge variant="info">Verified</Badge> : null}
            {(u.roles ?? []).map((r) => (
              <Badge key={r} variant={r === "super_admin" ? "default" : "secondary"} className="capitalize">
                {r.replace(/_/g, " ")}
              </Badge>
            ))}
          </div>

          <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
            <div>
              <dt className="text-muted-foreground text-xs">Phone</dt>
              <dd>{u.phone || "—"}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Country</dt>
              <dd>{u.country}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Joined</dt>
              <dd>{formatDate(u.joinedAt)}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground text-xs">Last active</dt>
              <dd>{formatRelative(u.lastActiveAt)}</dd>
            </div>
          </dl>

          <div>
            <p className="mb-1.5 text-sm font-medium">Recent orders</p>
            {data && data.orders.length ? (
              <ul className="divide-y rounded-lg border">
                {data.orders.map((o, i) => (
                  <li key={i} className="flex items-center gap-2 px-3 py-2 text-sm">
                    <span className="font-medium">{o.reference}</span>
                    <span className="text-muted-foreground truncate">· {o.store}</span>
                    <StatusBadge status={o.status} />
                    <span className="ml-auto tabular-nums">{formatNaira(o.total)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-sm">No orders.</p>
            )}
          </div>
        </div>
      )}
    </Dialog>
  )
}
