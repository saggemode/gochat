"use client"

// Minimal client-side auth for the admin panel. The JWT is kept in
// localStorage (read by the API client) and mirrored to a cookie so a future
// server-side guard / middleware can see it too. There is no admin-specific
// backend yet, so login currently accepts any credentials in mock mode; the
// seam in `login()` is where a real gateway `/auth/login` call will go.

import { useSyncExternalStore } from "react"

const TOKEN_KEY = "gochat_admin_token"
const USER_KEY = "gochat_admin_user"

export type AdminSession = {
  token: string
  name: string
  email: string
}

export function getToken(): string | null {
  if (typeof window === "undefined") return null
  try {
    return window.localStorage.getItem(TOKEN_KEY)
  } catch {
    return null
  }
}

export function getSession(): AdminSession | null {
  if (typeof window === "undefined") return null
  try {
    const raw = window.localStorage.getItem(USER_KEY)
    const token = window.localStorage.getItem(TOKEN_KEY)
    if (!raw || !token) return null
    return { token, ...(JSON.parse(raw) as { name: string; email: string }) }
  } catch {
    return null
  }
}

function persist(session: AdminSession) {
  try {
    window.localStorage.setItem(TOKEN_KEY, session.token)
    window.localStorage.setItem(
      USER_KEY,
      JSON.stringify({ name: session.name, email: session.email }),
    )
    // Mirror to a cookie for any future server-side middleware guard.
    document.cookie = `${TOKEN_KEY}=${session.token}; path=/; max-age=${60 * 60 * 24 * 7}; samesite=lax`
  } catch {
    /* storage unavailable (private mode) — session stays in-memory for the tab */
  }
}

/**
 * Sign in. Mock mode: accepts a non-empty email + password and mints a fake
 * token. Replace the body with a real gateway call when an admin login exists:
 *   const res = await apiFetch("/auth/login", { method: "POST", body: ... })
 */
export async function login(email: string, password: string): Promise<AdminSession> {
  await new Promise((r) => setTimeout(r, 450)) // simulate network
  if (!email.trim() || !password.trim()) {
    throw new Error("Email and password are required")
  }
  const session: AdminSession = {
    token: "mock." + btoa(email) + "." + Date.now(),
    name: email.split("@")[0].replace(/[._]/g, " ") || "Admin",
    email,
  }
  persist(session)
  notify()
  return session
}

export function logout() {
  try {
    window.localStorage.removeItem(TOKEN_KEY)
    window.localStorage.removeItem(USER_KEY)
    document.cookie = `${TOKEN_KEY}=; path=/; max-age=0`
  } catch {
    /* ignore */
  }
  notify()
}

// Tiny pub/sub so React components re-render on login/logout.
const listeners = new Set<() => void>()
function notify() {
  listeners.forEach((l) => l())
}

// Cached snapshot — useSyncExternalStore requires a stable reference between
// calls when nothing changed, so we only mint a new object when the identity
// (token + email) actually differs.
let snapshot: AdminSession | null = null
let snapshotKey = "\0"
function getSnapshot(): AdminSession | null {
  const s = getSession()
  const key = s ? `${s.token}|${s.email}` : ""
  if (key !== snapshotKey) {
    snapshotKey = key
    snapshot = s
  }
  return snapshot
}

function subscribe(cb: () => void) {
  listeners.add(cb)
  window.addEventListener("storage", cb)
  return () => {
    listeners.delete(cb)
    window.removeEventListener("storage", cb)
  }
}

/**
 * React hook exposing the current session reactively. `ready` flips to true
 * once hydrated, so guards don't redirect before localStorage has been read.
 */
export function useSession() {
  const session = useSyncExternalStore(subscribe, getSnapshot, () => null)
  const ready = useSyncExternalStore(
    () => () => {},
    () => true,
    () => false,
  )
  return { session, ready }
}
