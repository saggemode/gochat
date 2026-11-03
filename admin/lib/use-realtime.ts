"use client"

import { useEffect, useRef } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { API_BASE_URL } from "./api"
import { getToken } from "./auth"

const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS === "true"

// Query keys refreshed when the backend signals activity over the WebSocket.
const LIVE_KEYS = ["orders", "disputes", "reports", "kpis", "activity"]

/**
 * Live updates: connects to the gateway's WebSocket (the same hub that
 * broadcasts marketplace events) and invalidates the admin's data queries when
 * backend activity arrives — so lists update without waiting on a poll. Falls
 * back silently when the socket can't connect (mock mode, no token, no Redis
 * on the backend — the queries still refetch on their own stale timers).
 */
export function useRealtime() {
  const qc = useQueryClient()
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    if (USE_MOCKS) return
    const token = getToken()
    if (!token) return

    let ws: WebSocket | null = null
    let retry: ReturnType<typeof setTimeout> | null = null
    let closed = false
    let attempts = 0

    const wsBase = API_BASE_URL.replace(/^http/, "ws")

    const connect = () => {
      if (closed) return
      try {
        ws = new WebSocket(`${wsBase}/api/v1/ws?token=${encodeURIComponent(token)}`)
      } catch {
        return
      }
      ws.onopen = () => {
        attempts = 0
      }
      ws.onmessage = () => {
        // Any backend broadcast → refresh the live lists (debounced).
        if (debounce.current) clearTimeout(debounce.current)
        debounce.current = setTimeout(() => {
          LIVE_KEYS.forEach((k) => qc.invalidateQueries({ queryKey: [k] }))
        }, 1500)
      }
      ws.onclose = () => {
        if (closed) return
        attempts += 1
        const delay = Math.min(30000, 1000 * 2 ** attempts)
        retry = setTimeout(connect, delay)
      }
      ws.onerror = () => ws?.close()
    }

    connect()
    return () => {
      closed = true
      if (retry) clearTimeout(retry)
      if (debounce.current) clearTimeout(debounce.current)
      ws?.close()
    }
  }, [qc])
}
