"use client"

import { useQuery } from "@tanstack/react-query"
import { getMe, type AdminMe } from "./resources"

/**
 * Reactive admin capabilities, fed by GET /admin/me. `can(perm)` returns false
 * while loading (so controls a user lacks are never briefly shown), and true
 * for a granted permission. Cached for 5 minutes.
 */
export function usePermissions() {
  const { data, isLoading } = useQuery<AdminMe>({
    queryKey: ["me"],
    queryFn: getMe,
    staleTime: 5 * 60_000,
  })

  const can = (perm: string): boolean => !!data?.permissions?.[perm]

  return {
    me: data,
    roles: data?.roles ?? [],
    can,
    ready: !isLoading && !!data,
  }
}
