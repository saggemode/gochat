"use client"

import { ArrowDownRight, ArrowUpRight } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { EChart } from "@/components/charts/echart"
import { cn } from "@/lib/utils"
import { formatCompact, formatDelta, formatNaira } from "@/lib/format"
import type { KpiMetric } from "@/lib/types"

function formatValue(m: KpiMetric): string {
  if (m.format === "currency") return formatNaira(m.value, { compact: true })
  if (m.format === "percent") return `${m.value}%`
  return formatCompact(m.value)
}

export function KpiCard({ metric }: { metric: KpiMetric }) {
  const up = metric.trend.delta >= 0
  // A downward trend in disputes is good; treat that KPI inverted.
  const positive = metric.key === "disputes" ? !up : up

  return (
    <Card className="gap-0 overflow-hidden py-0">
      <CardContent className="p-4">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <p className="text-muted-foreground truncate text-sm">{metric.label}</p>
            <p className="mt-1 text-2xl font-semibold tracking-tight">{formatValue(metric)}</p>
          </div>
          <span
            className={cn(
              "inline-flex items-center gap-0.5 rounded-md px-1.5 py-0.5 text-xs font-medium",
              positive
                ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                : "bg-destructive/15 text-destructive dark:text-red-400",
            )}
          >
            {up ? <ArrowUpRight className="size-3" /> : <ArrowDownRight className="size-3" />}
            {formatDelta(metric.trend.delta)}
          </span>
        </div>
        <div className="mt-2 -mb-1">
          <EChart
            height={40}
            build={() => ({
              grid: { top: 2, bottom: 2, left: 0, right: 0 },
              xAxis: { type: "category", show: false, data: metric.trend.series.map((_, i) => i) },
              yAxis: { type: "value", show: false, scale: true },
              tooltip: { show: false },
              series: [
                {
                  type: "line",
                  data: metric.trend.series,
                  smooth: true,
                  symbol: "none",
                  lineStyle: { width: 2, color: positive ? "#10b981" : "#ef4444" },
                  areaStyle: {
                    opacity: 0.12,
                    color: positive ? "#10b981" : "#ef4444",
                  },
                },
              ],
            })}
          />
        </div>
      </CardContent>
    </Card>
  )
}

export function KpiCardSkeleton() {
  return (
    <Card className="py-0">
      <CardContent className="space-y-3 p-4">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-7 w-20" />
        <Skeleton className="h-10 w-full" />
      </CardContent>
    </Card>
  )
}
