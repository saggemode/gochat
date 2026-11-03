"use client"

// Global promise-based confirm dialog. Call `confirm({...})` anywhere; a single
// <ConfirmHost/> renders it and resolves with {confirmed, reason}.
import { useSyncExternalStore } from "react"

export type ConfirmOptions = {
  title: string
  message?: string
  confirmLabel?: string
  danger?: boolean
  withReason?: boolean
  reasonLabel?: string
  reasonRequired?: boolean
}

export type ConfirmState = (ConfirmOptions & { id: number; resolve: (r: { confirmed: boolean; reason: string }) => void }) | null

let state: ConfirmState = null
const listeners = new Set<() => void>()
let seq = 1

function emit() {
  listeners.forEach((l) => l())
}

export function confirm(opts: ConfirmOptions): Promise<{ confirmed: boolean; reason: string }> {
  return new Promise((resolve) => {
    state = { ...opts, id: seq++, resolve }
    emit()
  })
}

export function resolveConfirm(confirmed: boolean, reason: string) {
  const s = state
  state = null
  emit()
  s?.resolve({ confirmed, reason })
}

export function useConfirmState(): ConfirmState {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb)
      return () => listeners.delete(cb)
    },
    () => state,
    () => state,
  )
}
