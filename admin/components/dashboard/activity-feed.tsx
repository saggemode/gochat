"use client"

import {
  UserPlus,
  ShoppingCart,
  AlertTriangle,
  Flag,
  Banknote,
  Phone,
  MessageSquare,
  type LucideIcon,
} from "lucide-react"
import { formatNaira, formatRelative } from "@/lib/format"
import type { ActivityItem, ActivityKind } from "@/lib/types"

const ICON: Record<ActivityKind, LucideIcon> = {
  signup: UserPlus,
  order: ShoppingCart,
  dispute: AlertTriangle,
  report: Flag,
  payout: Banknote,
  call: Phone,
  message: MessageSquare,
}

const TONE: Record<ActivityKind, string> = {
  signup: "bg-sky-500/15 text-sky-600 dark:text-sky-400",
  order: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400",
  dispute: "bg-amber-500/15 text-amber-600 dark:text-amber-400",
  report: "bg-destructive/15 text-destructive dark:text-red-400",
  payout: "bg-violet-500/15 text-violet-600 dark:text-violet-400",
  call: "bg-muted text-muted-foreground",
  message: "bg-muted text-muted-foreground",
}

export function ActivityFeed({ items }: { items: ActivityItem[] }) {
  return (
    <ul className="divide-y">
      {items.map((it) => {
        const Icon = ICON[it.kind]
        return (
          <li key={it.id} className="flex items-center gap-3 py-2.5">
            <span className={`flex size-8 shrink-0 items-center justify-center rounded-full ${TONE[it.kind]}`}>
              <Icon className="size-4" />
            </span>
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{it.title}</p>
              <p className="text-muted-foreground truncate text-xs">{it.subtitle}</p>
            </div>
            <div className="text-right">
              {it.amount != null ? (
                <p className="text-sm font-medium tabular-nums">{formatNaira(it.amount)}</p>
              ) : null}
              <p className="text-muted-foreground text-xs">{formatRelative(it.at)}</p>
            </div>
          </li>
        )
      })}
    </ul>
  )
}
