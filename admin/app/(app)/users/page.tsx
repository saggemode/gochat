"use client"

import { useMemo, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Ban, Check, Pause, Search, ShieldCheck, Store } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { StatusBadge } from "@/components/status-badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatCompact, formatRelative, initials } from "@/lib/format"
import { getUsers } from "@/lib/resources"
import type { AdminUser, UserStatus } from "@/lib/types"

export default function UsersPage() {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ["users"], queryFn: getUsers })
  const [q, setQ] = useState("")
  const [status, setStatus] = useState<UserStatus | "all">("all")
  const [sellersOnly, setSellersOnly] = useState(false)

  const users = useMemo(() => data ?? [], [data])

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return users.filter((u) => {
      if (status !== "all" && u.status !== status) return false
      if (sellersOnly && !u.isSeller) return false
      if (!needle) return true
      return (
        u.name.toLowerCase().includes(needle) ||
        u.phone.includes(needle) ||
        (u.email?.toLowerCase().includes(needle) ?? false)
      )
    })
  }, [users, q, status, sellersOnly])

  const counts = useMemo(() => {
    const c: Record<string, number> = { active: 0, suspended: 0, banned: 0, pending: 0 }
    users.forEach((u) => (c[u.status] = (c[u.status] ?? 0) + 1))
    return c
  }, [users])

  function setUserStatus(id: string, next: UserStatus) {
    qc.setQueryData<AdminUser[]>(["users"], (prev) =>
      (prev ?? []).map((u) => (u.id === id ? { ...u, status: next } : u)),
    )
  }

  return (
    <div className="space-y-4">
      {/* status summary */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {(["active", "suspended", "banned", "pending"] as const).map((s) => (
          <Card key={s} className="py-0">
            <CardContent className="flex items-center justify-between p-4">
              <div>
                <p className="text-muted-foreground text-xs capitalize">{s}</p>
                <p className="text-xl font-semibold">{isLoading ? "—" : formatCompact(counts[s] ?? 0)}</p>
              </div>
              <StatusBadge status={s} />
            </CardContent>
          </Card>
        ))}
      </div>

      {/* toolbar */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
          <Input
            placeholder="Search name, phone, email…"
            className="pl-8"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <Select value={status} onChange={(e) => setStatus(e.target.value as UserStatus | "all")}>
          <option value="all">All statuses</option>
          <option value="active">Active</option>
          <option value="suspended">Suspended</option>
          <option value="banned">Banned</option>
          <option value="pending">Pending</option>
        </Select>
        <Button
          variant={sellersOnly ? "default" : "outline"}
          onClick={() => setSellersOnly((v) => !v)}
        >
          <Store /> Sellers
        </Button>
        <span className="text-muted-foreground ml-auto text-sm">
          {filtered.length} of {users.length}
        </span>
      </div>

      {/* table */}
      <Card className="py-0">
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>User</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Country</TableHead>
                <TableHead className="text-right">Messages</TableHead>
                <TableHead className="text-right">Orders</TableHead>
                <TableHead>Last active</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={7}>
                      <Skeleton className="h-8 w-full" />
                    </TableCell>
                  </TableRow>
                ))
              ) : filtered.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground py-10 text-center">
                    No users match your filters.
                  </TableCell>
                </TableRow>
              ) : (
                filtered.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar className="size-8">
                          <AvatarFallback>{initials(u.name)}</AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5 font-medium">
                            <span className="truncate">{u.name}</span>
                            {u.verified ? (
                              <ShieldCheck className="size-3.5 shrink-0 text-sky-500" />
                            ) : null}
                            {u.isSeller ? <Badge variant="outline">Seller</Badge> : null}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            {u.phone}
                            {u.email ? ` · ${u.email}` : ""}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={u.status} />
                    </TableCell>
                    <TableCell className="text-muted-foreground">{u.country}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatCompact(u.messages)}</TableCell>
                    <TableCell className="text-right tabular-nums">{u.orders}</TableCell>
                    <TableCell className="text-muted-foreground">{formatRelative(u.lastActiveAt)}</TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        {u.status !== "active" ? (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            title="Activate"
                            onClick={() => setUserStatus(u.id, "active")}
                          >
                            <Check className="text-emerald-500" />
                          </Button>
                        ) : null}
                        {u.status !== "suspended" && u.status !== "banned" ? (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            title="Suspend"
                            onClick={() => setUserStatus(u.id, "suspended")}
                          >
                            <Pause className="text-amber-500" />
                          </Button>
                        ) : null}
                        {u.status !== "banned" ? (
                          <Button
                            variant="ghost"
                            size="icon-sm"
                            title="Ban"
                            onClick={() => setUserStatus(u.id, "banned")}
                          >
                            <Ban className="text-destructive" />
                          </Button>
                        ) : null}
                      </div>
                    </TableCell>
                  </TableRow>
                ))
              )}
            </TableBody>
          </Table>
        </CardContent>
      </Card>
    </div>
  )
}
