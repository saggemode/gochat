"use client"

import { Suspense, useState } from "react"
import { useSearchParams } from "next/navigation"
import { useQuery } from "@tanstack/react-query"
import { User, ShoppingCart, Store } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { StatusBadge } from "@/components/status-badge"
import { UserDetailDialog } from "@/components/users/user-detail"
import { search } from "@/lib/resources"
import { formatNaira } from "@/lib/format"

function SearchResults() {
  const params = useSearchParams()
  const q = params.get("q") ?? ""
  const [detailId, setDetailId] = useState<string | null>(null)
  const { data, isLoading } = useQuery({ queryKey: ["search", q], queryFn: () => search(q), enabled: q.length >= 2 })

  if (q.length < 2) {
    return <p className="text-muted-foreground text-sm">Type at least 2 characters to search users, orders and stores.</p>
  }
  if (isLoading) {
    return <div className="space-y-3">{Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-28 w-full rounded-xl" />)}</div>
  }

  const r = data ?? { users: [], orders: [], stores: [] }
  const empty = !r.users.length && !r.orders.length && !r.stores.length

  return (
    <div className="space-y-4">
      <p className="text-muted-foreground text-sm">Results for “{q}”</p>
      {empty ? (
        <Card><CardContent className="text-muted-foreground py-12 text-center text-sm">No matches.</CardContent></Card>
      ) : null}

      {r.users.length ? (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2 text-sm"><User className="size-4" /> Users</CardTitle></CardHeader>
          <CardContent className="space-y-1">
            {r.users.map((u) => (
              <button key={u.id} onClick={() => setDetailId(u.id)} className="hover:bg-muted/50 flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm">
                <span className="font-medium">{u.name}</span>
                <span className="text-muted-foreground truncate">{u.email}</span>
                <span className="ml-auto"><StatusBadge status={u.status} /></span>
              </button>
            ))}
          </CardContent>
        </Card>
      ) : null}

      {r.orders.length ? (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2 text-sm"><ShoppingCart className="size-4" /> Orders</CardTitle></CardHeader>
          <CardContent className="space-y-1">
            {r.orders.map((o) => (
              <div key={o.reference} className="flex items-center gap-2 px-2 py-1.5 text-sm">
                <span className="font-medium">{o.reference}</span>
                <StatusBadge status={o.status} />
                <span className="ml-auto tabular-nums">{formatNaira(o.total)}</span>
              </div>
            ))}
          </CardContent>
        </Card>
      ) : null}

      {r.stores.length ? (
        <Card>
          <CardHeader><CardTitle className="flex items-center gap-2 text-sm"><Store className="size-4" /> Stores</CardTitle></CardHeader>
          <CardContent className="space-y-1">
            {r.stores.map((s) => (
              <div key={s.id} className="px-2 py-1.5 text-sm font-medium">{s.name}</div>
            ))}
          </CardContent>
        </Card>
      ) : null}

      <UserDetailDialog userId={detailId} onClose={() => setDetailId(null)} />
    </div>
  )
}

export default function SearchPage() {
  return (
    <Suspense fallback={<Skeleton className="h-28 w-full rounded-xl" />}>
      <SearchResults />
    </Suspense>
  )
}
