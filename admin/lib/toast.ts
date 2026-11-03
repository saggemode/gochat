"use client"

// Tiny global toast store (no provider needed). Components call toast.success/
// toast.error(...); <Toaster/> (mounted once) subscribes and renders.
import { useSyncExternalStore } from "react"

export type ToastKind = "success" | "error" | "info"
export type Toast = { id: number; kind: ToastKind; message: string }

let toasts: Toast[] = []
const listeners = new Set<() => void>()
let seq = 1

function emit() {
  listeners.forEach((l) => l())
}

function push(kind: ToastKind, message: string) {
  const id = seq++
  toasts = [...toasts, { id, kind, message }]
  emit()
  setTimeout(() => dismiss(id), 4000)
}

export function dismiss(id: number) {
  toasts = toasts.filter((t) => t.id !== id)
  emit()
}

export const toast = {
  success: (m: string) => push("success", m),
  error: (m: string) => push("error", m),
  info: (m: string) => push("info", m),
}

export function useToasts(): Toast[] {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => toasts,
    () => toasts,
  )
}
