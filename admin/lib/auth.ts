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

const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS !== "false"

type LoginResponse = {
  access_token?: string
  refresh_token?: string
  user?: { id?: string; email?: string; display_name?: string }
}

/**
 * Sign in.
 * - Mock mode (default): accepts any non-empty email + password, mints a fake
 *   token.
 * - Real mode (NEXT_PUBLIC_USE_MOCKS=false): POSTs to the gateway
 *   /api/v1/auth/login, then verifies the account is an admin by probing
 *   /api/v1/admin/overview. A valid-but-non-admin account is rejected here so
 *   the user sees a clear message instead of empty pages.
 */
export async function login(email: string, password: string): Promise<AdminSession> {
  if (!email.trim() || !password.trim()) {
    throw new Error("Email and password are required")
  }

  if (USE_MOCKS) {
    await new Promise((r) => setTimeout(r, 450)) // simulate network
    const session: AdminSession = {
      token: "mock." + btoa(email) + "." + Date.now(),
      name: email.split("@")[0].replace(/[._]/g, " ") || "Admin",
      email,
    }
    persist(session)
    notify()
    return session
  }

  // Real login against the gateway. Imported lazily to avoid a static import
  // cycle (api.ts imports getToken from this module).
  const { apiFetch, ApiError } = await import("./api")
  const res = await apiFetch<LoginResponse>("/api/v1/auth/login", {
    method: "POST",
    anonymous: true,
    body: { email, password },
  })
  if (!res.access_token) {
    throw new Error("Login did not return a token")
  }

  const session: AdminSession = {
    token: res.access_token,
    name: res.user?.display_name || res.user?.email || email,
    email: res.user?.email || email,
  }
  persist(session) // persist first so the admin probe is authenticated
  notify()

  // Verify admin access. 403 => not an admin; 503 => DB not configured (allow,
  // can't verify); other/network errors => allow, surfaced later on data load.
  try {
    await apiFetch("/api/v1/admin/overview")
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) {
      logout()
      throw new Error("This account does not have admin access")
    }
  }

  return session
}

/** Persist a session obtained outside the normal login (e.g. first-run setup). */
export function setSession(session: AdminSession) {
  persist(session)
  notify()
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
