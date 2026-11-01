"use client"

import { useEffect, useState } from "react"
import { usePathname, useRouter } from "next/navigation"
import { useQuery } from "@tanstack/react-query"
import { Sidebar } from "@/components/layout/sidebar"
import { Topbar } from "@/components/layout/topbar"
import { NAV } from "@/components/layout/nav"
import { useSession } from "@/lib/auth"
import { getDisputes, getReports } from "@/lib/resources"

function titleFor(pathname: string): string {
  const match = [...NAV]
    .sort((a, b) => b.href.length - a.href.length)
    .find((n) => (n.href === "/" ? pathname === "/" : pathname.startsWith(n.href)))
  return match?.label ?? "GoChat Admin"
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const { session, ready } = useSession()
  const router = useRouter()
  const pathname = usePathname()
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (ready && !session) router.replace("/login")
  }, [ready, session, router])

  // Sidebar badge counts (open disputes / pending reports).
  const disputes = useQuery({ queryKey: ["disputes"], queryFn: getDisputes, enabled: !!session })
  const reports = useQuery({ queryKey: ["reports"], queryFn: getReports, enabled: !!session })

  const counts = {
    disputes: disputes.data?.filter((d) => d.status === "open" || d.status === "under_review").length,
    reports: reports.data?.filter((r) => r.status === "pending").length,
  }

  // Always render the shell + page segment (so navigation stays instant and
  // the segment is never "dropped"); overlay a loading state until the client
  // session is known. Unauthenticated visitors are redirected by the effect
  // above.
  const gated = !ready || !session

  return (
    <div className="flex min-h-screen">
      <Sidebar open={open} onClose={() => setOpen(false)} counts={counts} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Topbar title={titleFor(pathname)} onMenu={() => setOpen(true)} />
        <main className="flex-1 p-4 md:p-6">{children}</main>
      </div>
      {gated ? (
        <div className="bg-background fixed inset-0 z-[60] flex items-center justify-center text-muted-foreground text-sm">
          Loading…
        </div>
      ) : null}
    </div>
  )
}
