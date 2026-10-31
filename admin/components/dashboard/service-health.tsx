"use client"

import { cn } from "@/lib/utils"
import type { ServiceHealth } from "@/lib/types"

const DOT: Record<ServiceHealth["status"], string> = {
  healthy: "bg-emerald-500",
  degraded: "bg-amber-500",
  down: "bg-red-500",
}

const LABEL: Record<ServiceHealth["status"], string> = {
  healthy: "Connected",
  degraded: "Connecting",
  down: "Unreachable",
}

export function ServiceHealthGrid({ services }: { services: ServiceHealth[] }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
      {services.map((s) => (
        <div
          key={s.name}
          className="flex items-center gap-2 rounded-lg border px-3 py-2"
        >
          <span className={cn("size-2 shrink-0 rounded-full", DOT[s.status])} />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium capitalize">{s.name}</p>
            <p className="text-muted-foreground text-xs">{LABEL[s.status]}</p>
          </div>
        </div>
      ))}
    </div>
  )
}
