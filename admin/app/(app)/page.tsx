"use client"

import { useCallback } from "react"
import { useQuery } from "@tanstack/react-query"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { KpiCard, KpiCardSkeleton } from "@/components/dashboard/kpi-card"
import { ActivityFeed } from "@/components/dashboard/activity-feed"
import { ServiceHealthGrid } from "@/components/dashboard/service-health"
import { EChart, type ChartTokens } from "@/components/charts/echart"
import { Skeleton } from "@/components/ui/skeleton"
import { formatDate, formatNaira } from "@/lib/format"
import {
  getActivity,
  getGrowthSeries,
  getKpis,
  getMessageMix,
  getRevenueSeries,
  getServiceHealth,
} from "@/lib/resources"

export default function OverviewPage() {
  const kpis = useQuery({ queryKey: ["kpis"], queryFn: getKpis })
  const growth = useQuery({ queryKey: ["growth"], queryFn: getGrowthSeries })
  const revenue = useQuery({ queryKey: ["revenue"], queryFn: getRevenueSeries })
  const mix = useQuery({ queryKey: ["messageMix"], queryFn: getMessageMix })
  const activity = useQuery({ queryKey: ["activity"], queryFn: getActivity })
  const health = useQuery({ queryKey: ["health"], queryFn: getServiceHealth })

  const growthBuild = useCallback(
    (t: ChartTokens): echartsOption => {
      const data = growth.data ?? []
      const days = data[0]?.points.map((p) => formatDate(p.date)) ?? []
      return {
        color: [t.foreground, t.chart[1] ?? t.muted],
        grid: { top: 24, right: 16, bottom: 28, left: 48 },
        legend: { data: data.map((s) => s.name), textStyle: { color: t.muted }, top: 0, icon: "roundRect" },
        tooltip: { trigger: "axis", backgroundColor: t.tooltipBg, borderColor: t.border, textStyle: { color: t.foreground } },
        xAxis: {
          type: "category",
          data: days,
          boundaryGap: false,
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted, fontSize: 11, interval: Math.ceil(days.length / 8) },
        },
        yAxis: {
          type: "value",
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted, fontSize: 11 },
        },
        series: data.map((s, i) => ({
          name: s.name,
          type: "line",
          smooth: true,
          symbol: "none",
          lineStyle: { width: 2 },
          areaStyle: i === 0 ? { opacity: 0.1 } : undefined,
          data: s.points.map((p) => p.value),
        })),
      }
    },
    [growth.data],
  )

  const revenueBuild = useCallback(
    (t: ChartTokens): echartsOption => {
      const data = revenue.data ?? []
      return {
        color: [t.foreground],
        grid: { top: 16, right: 16, bottom: 28, left: 56 },
        tooltip: {
          trigger: "axis",
          backgroundColor: t.tooltipBg,
          borderColor: t.border,
          textStyle: { color: t.foreground },
          valueFormatter: (v) => formatNaira(Number(v)),
        },
        xAxis: {
          type: "category",
          data: data.map((p) => formatDate(p.date)),
          axisLine: { lineStyle: { color: t.border } },
          axisLabel: { color: t.muted, fontSize: 11, interval: Math.ceil(data.length / 8) },
        },
        yAxis: {
          type: "value",
          splitLine: { lineStyle: { color: t.grid } },
          axisLabel: { color: t.muted, fontSize: 11, formatter: (v: number) => formatNaira(v, { compact: true }) },
        },
        series: [
          {
            type: "bar",
            data: data.map((p) => p.value),
            itemStyle: { borderRadius: [3, 3, 0, 0] },
            barWidth: "55%",
          },
        ],
      }
    },
    [revenue.data],
  )

  const mixBuild = useCallback(
    (t: ChartTokens): echartsOption => {
      const data = mix.data ?? []
      return {
        color: t.chart.length ? t.chart : [t.foreground, t.muted],
        tooltip: { trigger: "item", backgroundColor: t.tooltipBg, borderColor: t.border, textStyle: { color: t.foreground } },
        legend: { bottom: 0, textStyle: { color: t.muted }, icon: "circle" },
        series: [
          {
            type: "pie",
            radius: ["52%", "74%"],
            center: ["50%", "44%"],
            avoidLabelOverlap: true,
            itemStyle: { borderColor: t.tooltipBg, borderWidth: 2 },
            label: { show: false },
            data: data.map((d) => ({ name: d.name, value: d.value })),
          },
        ],
      }
    },
    [mix.data],
  )

  return (
    <div className="space-y-4">
      {/* KPI row */}
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
        {kpis.isLoading
          ? Array.from({ length: 6 }).map((_, i) => <KpiCardSkeleton key={i} />)
          : kpis.data?.map((m) => <KpiCard key={m.key} metric={m} />)}
      </div>

      {/* Growth + message mix */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>User growth</CardTitle>
            <CardDescription>New vs. active users over the last 30 days</CardDescription>
          </CardHeader>
          <CardContent>
            {growth.isLoading ? <Skeleton className="h-[300px] w-full" /> : <EChart build={growthBuild} height={300} />}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Message mix</CardTitle>
            <CardDescription>By type, last 24h</CardDescription>
          </CardHeader>
          <CardContent>
            {mix.isLoading ? <Skeleton className="h-[300px] w-full" /> : <EChart build={mixBuild} height={300} />}
          </CardContent>
        </Card>
      </div>

      {/* Revenue + activity */}
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader>
            <CardTitle>Marketplace revenue</CardTitle>
            <CardDescription>Daily GMV over the last 30 days</CardDescription>
          </CardHeader>
          <CardContent>
            {revenue.isLoading ? <Skeleton className="h-[260px] w-full" /> : <EChart build={revenueBuild} height={260} />}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Recent activity</CardTitle>
            <CardDescription>Across the platform</CardDescription>
          </CardHeader>
          <CardContent>
            {activity.isLoading ? (
              <div className="space-y-3">
                {Array.from({ length: 6 }).map((_, i) => (
                  <Skeleton key={i} className="h-10 w-full" />
                ))}
              </div>
            ) : (
              <ActivityFeed items={activity.data ?? []} />
            )}
          </CardContent>
        </Card>
      </div>

      {/* Service health */}
      <Card>
        <CardHeader>
          <CardTitle>Service health</CardTitle>
          <CardDescription>gRPC microservices — latency &amp; uptime (from /readyz + /metrics)</CardDescription>
        </CardHeader>
        <CardContent>
          {health.isLoading ? (
            <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
              {Array.from({ length: 6 }).map((_, i) => (
                <Skeleton key={i} className="h-12 w-full" />
              ))}
            </div>
          ) : (
            <ServiceHealthGrid services={health.data ?? []} />
          )}
        </CardContent>
      </Card>
    </div>
  )
}

// Local alias to keep echarts option typing readable without importing the
// full namespace into the page module.
type echartsOption = import("echarts").EChartsOption
