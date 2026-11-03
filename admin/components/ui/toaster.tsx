"use client"

import { CheckCircle2, XCircle, Info, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { useToasts, dismiss, type ToastKind } from "@/lib/toast"

const ICON = {
  success: CheckCircle2,
  error: XCircle,
  info: Info,
}

const TONE: Record<ToastKind, string> = {
  success: "text-emerald-500",
  error: "text-destructive",
  info: "text-sky-500",
}

export function Toaster() {
  const toasts = useToasts()
  return (
    <div className="fixed bottom-4 right-4 z-[100] flex w-[min(92vw,22rem)] flex-col gap-2">
      {toasts.map((t) => {
        const Icon = ICON[t.kind]
        return (
          <div
            key={t.id}
            className="bg-card text-card-foreground flex items-start gap-2.5 rounded-lg border p-3 shadow-lg"
            role="status"
          >
            <Icon className={cn("mt-0.5 size-4 shrink-0", TONE[t.kind])} />
            <p className="min-w-0 flex-1 text-sm">{t.message}</p>
            <button onClick={() => dismiss(t.id)} className="text-muted-foreground hover:text-foreground" aria-label="Dismiss">
              <X className="size-3.5" />
            </button>
          </div>
        )
      })}
    </div>
  )
}
