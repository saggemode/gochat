"use client"

import { useEffect, useRef, useState } from "react"
import * as echarts from "echarts"

export type ChartTokens = {
  foreground: string
  muted: string
  border: string
  grid: string
  chart: string[]
  tooltipBg: string
  dark: boolean
}

function resolve(el: HTMLElement): ChartTokens {
  const s = getComputedStyle(el)
  const v = (name: string) => s.getPropertyValue(name).trim()
  const dark = document.documentElement.classList.contains("dark")
  return {
    foreground: v("--color-foreground") || (dark ? "#fafafa" : "#111"),
    muted: v("--color-muted-foreground") || "#888",
    border: v("--color-border") || "rgba(128,128,128,0.2)",
    grid: dark ? "rgba(255,255,255,0.08)" : "rgba(0,0,0,0.06)",
    chart: [
      v("--color-chart-1"),
      v("--color-chart-2"),
      v("--color-chart-3"),
      v("--color-chart-4"),
      v("--color-chart-5"),
    ].filter(Boolean),
    tooltipBg: v("--color-popover") || (dark ? "#1c1c1c" : "#fff"),
    dark,
  }
}

/**
 * Thin ECharts wrapper. `build` receives resolved theme tokens (from CSS
 * variables) and returns an ECharts option, so charts restyle automatically
 * when the user toggles dark mode.
 */
export function EChart({
  build,
  className,
  height = 300,
}: {
  build: (t: ChartTokens) => echarts.EChartsOption
  className?: string
  height?: number
}) {
  const ref = useRef<HTMLDivElement>(null)
  const chartRef = useRef<echarts.ECharts | null>(null)
  const [themeTick, setThemeTick] = useState(0)

  // Re-render when the <html> dark class changes.
  useEffect(() => {
    const obs = new MutationObserver(() => setThemeTick((t) => t + 1))
    obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] })
    return () => obs.disconnect()
  }, [])

  useEffect(() => {
    if (!ref.current) return
    const chart = chartRef.current ?? echarts.init(ref.current)
    chartRef.current = chart
    chart.setOption(build(resolve(ref.current)), true)

    const ro = new ResizeObserver(() => chart.resize())
    ro.observe(ref.current)
    return () => ro.disconnect()
  }, [build, themeTick])

  useEffect(() => {
    return () => {
      chartRef.current?.dispose()
      chartRef.current = null
    }
  }, [])

  return <div ref={ref} className={className} style={{ width: "100%", height }} />
}
