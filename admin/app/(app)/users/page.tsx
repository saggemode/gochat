"use client"

import { useEffect, useMemo, useState } from "react"
import { useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query"
import { Ban, Check, Download, Pause, Search, ShieldCheck } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Badge } from "@/components/ui/badge"
import { Skeleton } from "@/components/ui/skeleton"
import { Pagination } from "@/components/ui/pagination"
import { StatusBadge } from "@/components/status-badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { UserDetailDialog } from "@/components/users/user-detail"
import { formatRelative } from "@/lib/format"
import { getUsers, setUserStatus } from "@/lib/resources"
import { usePermissions } from "@/lib/use-permissions"
import { confirm } from "@/lib/confirm"
import { toast } from "@/lib/toast"
import { exportCsv } from "@/lib/csv"
import type { UserStatus } from "@/lib/types"

const PAGE = 25

export default function UsersPage() {
  const qc = useQueryClient()
  const { can } = usePermissions()
  const canWrite = can("admin:users:write")

  const [searchInput, setSearchInput] = useState("")
  const [search, setSearch] = useState("")
  const [status, setStatus] = useState<UserStatus | "all">("all")
  const [offset, setOffset] = useState(0)
  const [detailId, setDetailId] = useState<string | null>(null)

  // Debounce search; reset to first page on any filter change.
  useEffect(() => {
    const t = setTimeout(() => {
      setSearch(searchInput)
      setOffset(0)
    }, 350)
    return () => clearTimeout(t)
  }, [searchInput])

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ["users", { search, status, offset }],
    queryFn: () => getUsers({ search, status, limit: PAGE, offset }),
    placeholderData: keepPreviousData,
  })

  const rows = useMemo(() => data?.rows ?? [], [data])
  const total = data?.total ?? 0

  async function act(id: string, name: string, next: UserStatus) {
    const verb = next === "banned" ? "Ban" : next === "suspended" ? "Suspend" : "Reactivate"
    const { confirmed, reason } = await confirm({
      title: `${verb} ${name}?`,
      message: next === "banned" ? "They will be signed out immediately and unable to log in." : undefined,
      confirmLabel: verb,
      danger: next !== "active",
      withReason: next !== "active",
      reasonLabel: "Reason (recorded in audit log)",
    })
    if (!confirmed) return
    try {
      await setUserStatus(id, next, reason)
      toast.success(`${name} ${next === "active" ? "reactivated" : next}`)
      qc.invalidateQueries({ queryKey: ["users"] })
    } catch {
      toast.error("Failed to update user status")
    }
  }

  return (
    <div className="space-y-4">
      {/* toolbar */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
          <Input placeholder="Search name, phone, email…" className="pl-8" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
        </div>
        <Select value={status} onChange={(e) => { setStatus(e.target.value as UserStatus | "all"); setOffset(0) }}>
          <option value="all">All statuses</option>
          <option value="active">Active</option>
          <option value="suspended">Suspended</option>
          <option value="banned">Banned</option>
          <option value="pending">Pending</option>
        </Select>
        <Button
          variant="outline"
          onClick={() =>
            exportCsv(
              "users.csv",
              rows.map((u) => ({ id: u.id, name: u.name, email: u.email ?? "", phone: u.phone, status: u.status, country: u.country, orders: u.orders, joined: u.joinedAt })),
            )
          }
        >
          <Download /> Export
        </Button>
        <Pagination offset={offset} limit={PAGE} total={total} onChange={setOffset} />
      </div>

      <Card className="py-0">
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>User</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Country</TableHead>
                <TableHead className="text-right">Orders</TableHead>
                <TableHead>Last active</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className={isFetching ? "opacity-60" : ""}>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={6}><Skeleton className="h-8 w-full" /></TableCell>
                  </TableRow>
                ))
              ) : rows.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={6} className="text-muted-foreground py-10 text-center">No users match your filters.</TableCell>
                </TableRow>
              ) : (
                rows.map((u) => (
                  <TableRow key={u.id} className="cursor-pointer" onClick={() => setDetailId(u.id)}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar className="size-8"><AvatarFallback>{u.name.slice(0, 2).toUpperCase()}</AvatarFallback></Avatar>
                        <div className="min-w-0">
                          <div className="flex items-center gap-1.5 font-medium">
                            <span className="truncate">{u.name}</span>
                            {u.verified ? <ShieldCheck className="size-3.5 shrink-0 text-sky-500" /> : null}
                            {u.isSeller ? <Badge variant="outline">Seller</Badge> : null}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            {u.phone}{u.email ? ` · ${u.email}` : ""}
                          </div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell><StatusBadge status={u.status} /></TableCell>
                    <TableCell className="text-muted-foreground">{u.country}</TableCell>
                    <TableCell className="text-right tabular-nums">{u.orders}</TableCell>
                    <TableCell className="text-muted-foreground">{formatRelative(u.lastActiveAt)}</TableCell>
                    <TableCell onClick={(e) => e.stopPropagation()}>
                      <div className="flex justify-end gap-1">
                        {!canWrite ? <span className="text-muted-foreground text-xs">—</span> : null}
                        {canWrite && u.status !== "active" ? (
                          <Button variant="ghost" size="icon-sm" title="Reactivate" onClick={() => act(u.id, u.name, "active")}>
                            <Check className="text-emerald-500" />
                          </Button>
                        ) : null}
                        {canWrite && u.status !== "suspended" && u.status !== "banned" ? (
                          <Button variant="ghost" size="icon-sm" title="Suspend" onClick={() => act(u.id, u.name, "suspended")}>
                            <Pause className="text-amber-500" />
                          </Button>
                        ) : null}
                        {canWrite && u.status !== "banned" ? (
                          <Button variant="ghost" size="icon-sm" title="Ban" onClick={() => act(u.id, u.name, "banned")}>
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

      <UserDetailDialog userId={detailId} onClose={() => setDetailId(null)} />
    </div>
  )
}
