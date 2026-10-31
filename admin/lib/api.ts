// Typed fetch client for the GoChat gateway. All data resources flow through
// this so that swapping a mock resource for a real endpoint is a one-line
// change (see lib/resources.ts). The base URL defaults to the deployed Render
// gateway but is overridable via NEXT_PUBLIC_API_BASE_URL.

import { getToken } from "./auth"

export const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ||
  "https://gochat-kvpj.onrender.com"

export class ApiError extends Error {
  status: number
  constructor(status: number, message: string) {
    super(message)
    this.name = "ApiError"
    this.status = status
  }
}

type FetchOptions = Omit<RequestInit, "body"> & {
  body?: unknown
  /** Skip attaching the Authorization header. */
  anonymous?: boolean
}

export async function apiFetch<T = unknown>(
  path: string,
  opts: FetchOptions = {},
): Promise<T> {
  const { body, anonymous, headers, ...rest } = opts
  const url = path.startsWith("http") ? path : `${API_BASE_URL}${path}`

  const finalHeaders = new Headers(headers)
  if (body !== undefined && !finalHeaders.has("Content-Type")) {
    finalHeaders.set("Content-Type", "application/json")
  }
  if (!anonymous) {
    const token = getToken()
    if (token) finalHeaders.set("Authorization", `Bearer ${token}`)
  }

  const res = await fetch(url, {
    ...rest,
    headers: finalHeaders,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  const text = await res.text()
  const data = text ? safeParse(text) : null

  if (!res.ok) {
    const message =
      (data && typeof data === "object" && "message" in data
        ? String((data as Record<string, unknown>).message)
        : null) || res.statusText || "Request failed"
    throw new ApiError(res.status, message)
  }

  return data as T
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text)
  } catch {
    return text
  }
}
