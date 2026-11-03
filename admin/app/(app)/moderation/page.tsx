"use client"

import { useMemo, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import {
  Check,
  Flag,
  MessageSquare,
  User,
  Clapperboard,
  Radio,
  Package,
  X,
  Trash2,
  type LucideIcon,
} from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Select } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { StatusBadge } from "@/components/status-badge"
import { formatRelative } from "@/lib/format"
import { getReports, setReportStatus, takedownReport } from "@/lib/resources"
import { usePermissions } from "@/lib/use-permissions"
import { confirm } from "@/lib/confirm"
import { toast } from "@/lib/toast"
import type { AdminReport, ReportStatus, ReportTarget } from "@/lib/types"

const TARGET_ICON: Record<ReportTarget, LucideIcon> = {
  message: MessageSquare,
  user: User,
  story: Clapperboard,
  channel: Radio,
  product: Package,
}

export default function ModerationPage() {
  const qc = useQueryClient()
  const { can } = usePermissions()
  const canAction = can("admin:reports:action")
  const { data, isLoading } = useQuery({ queryKey: ["reports"], queryFn: getReports })
  const [status, setStatus] = useState<ReportStatus | "all">("pending")

  const reports = useMemo(() => data ?? [], [data])
  const filtered = useMemo(
    () => reports.filter((r) => status === "all" || r.status === status),
    [reports, status],
  )

  const pending = reports.filter((r) => r.status === "pending").length

  async function act(id: string, next: "actioned" | "dismissed") {
    const { confirmed, reason } = await confirm({
      title: next === "actioned" ? "Take action on this report?" : "Dismiss this report?",
      confirmLabel: next === "actioned" ? "Take action" : "Dismiss",
      danger: next === "actioned",
      withReason: true,
      reasonLabel: "Note (recorded in audit log)",
    })
    if (!confirmed) return
    const prev = qc.getQueryData<AdminReport[]>(["reports"])
    qc.setQueryData<AdminReport[]>(["reports"], (cur) =>
      (cur ?? []).map((r) => (r.id === id ? { ...r, status: next } : r)),
    )
    try {
      await setReportStatus(id, next, reason)
      toast.success(`Report ${next}`)
    } catch {
      if (prev) qc.setQueryData(["reports"], prev)
      toast.error("Failed to update report")
    }
  }

  async function takedown(r: AdminReport) {
    const { confirmed, reason } = await confirm({
      title: `Take down this ${r.target}?`,
      message: "The reported content will be removed and the report marked actioned. This can't be undone.",
      confirmLabel: "Take down",
      danger: true,
      withReason: true,
      reasonLabel: "Reason (recorded in audit log)",
    })
    if (!confirmed) return
    try {
      const res = await takedownReport(r.id, reason)
      toast.success(`Taken down — ${res.action}`)
      qc.invalidateQueries({ queryKey: ["reports"] })
    } catch {
      toast.error("Takedown failed")
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Badge variant={pending ? "destructive" : "secondary"} className="h-9 px-3">
          <Flag className="mr-1" /> {pending} pending
        </Badge>
        <Select value={status} onChange={(e) => setStatus(e.target.value as ReportStatus | "all")}>
          <option value="all">All statuses</option>
          <option value="pending">Pending</option>
          <option value="actioned">Actioned</option>
          <option value="dismissed">Dismissed</option>
        </Select>
        <span className="text-muted-foreground ml-auto text-sm">{filtered.length} items</span>
      </div>

      {isLoading ? (
        <div className="grid gap-3 md:grid-cols-2">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-36 w-full rounded-xl" />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <Card>
          <CardContent className="text-muted-foreground py-16 text-center text-sm">
            Nothing in the queue. 🎉
          </CardContent>
        </Card>
      ) : (
        <div className="grid gap-3 md:grid-cols-2">
          {filtered.map((r) => {
            const Icon = TARGET_ICON[r.target]
            return (
              <Card key={r.id} className="gap-0 py-0">
                <CardContent className="space-y-3 p-4">
                  <div className="flex items-start gap-3">
                    <span className="bg-muted text-muted-foreground flex size-9 shrink-0 items-center justify-center rounded-lg">
                      <Icon className="size-4" />
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="font-medium capitalize">{r.target}</span>
                        <span className="text-muted-foreground truncate text-sm">· {r.reportedName}</span>
                      </div>
                      <p className="text-muted-foreground text-xs">
                        Reported by {r.reporter} · {formatRelative(r.createdAt)}
                      </p>
                    </div>
                    <StatusBadge status={r.status} />
                  </div>

                  <div className="flex items-center gap-2">
                    <Badge variant="outline">{r.reason}</Badge>
                  </div>

                  {r.excerpt ? (
                    <p className="bg-muted/50 text-muted-foreground rounded-md px-3 py-2 text-sm italic">
                      “{r.excerpt}”
                    </p>
                  ) : null}

                  {r.status === "pending" && canAction ? (
                    <div className="flex gap-2 pt-1">
                      <Button size="sm" variant="destructive" className="flex-1" onClick={() => takedown(r)}>
                        <Trash2 /> Take down
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => act(r.id, "actioned")} title="Mark handled without removing">
                        <Check />
                      </Button>
                      <Button size="sm" variant="outline" className="flex-1" onClick={() => act(r.id, "dismissed")}>
                        <X /> Dismiss
                      </Button>
                    </div>
                  ) : null}
                </CardContent>
              </Card>
            )
          })}
        </div>
      )}
    </div>
  )
}
