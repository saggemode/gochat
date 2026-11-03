"use client"

import { useEffect, useMemo, useState } from "react"
import { useQuery, useQueryClient, keepPreviousData } from "@tanstack/react-query"
import { Check, Download, Search, X } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { Pagination } from "@/components/ui/pagination"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { StatusBadge } from "@/components/status-badge"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatDate, formatNaira, formatRelative } from "@/lib/format"
import { getDisputes, getOrders, resolveDispute } from "@/lib/resources"
import { usePermissions } from "@/lib/use-permissions"
import { confirm } from "@/lib/confirm"
import { toast } from "@/lib/toast"
import { exportCsv } from "@/lib/csv"
import type { OrderStatus } from "@/lib/types"

const PAGE = 25

export default function MarketplacePage() {
  const { can } = usePermissions()
  const canFinance = can("admin:finance:read")
  const canResolve = can("admin:disputes:resolve")

  const disputes = useQuery({ queryKey: ["disputes"], queryFn: getDisputes })
  const openDisputes = (disputes.data ?? []).filter((d) => d.status === "open" || d.status === "under_review").length

  return (
    <div className="space-y-4">
      <Tabs defaultValue={canFinance ? "orders" : "disputes"}>
        <TabsList>
          {canFinance ? <TabsTrigger value="orders">Orders</TabsTrigger> : null}
          <TabsTrigger value="disputes">Disputes{openDisputes ? ` (${openDisputes})` : ""}</TabsTrigger>
        </TabsList>

        {canFinance ? (
          <TabsContent value="orders">
            <OrdersTab />
          </TabsContent>
        ) : null}

        <TabsContent value="disputes">
          <DisputesTable
            disputes={disputes.data ?? []}
            loading={disputes.isLoading}
            canResolve={canResolve}
          />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function OrdersTab() {
  const [searchInput, setSearchInput] = useState("")
  const [search, setSearch] = useState("")
  const [status, setStatus] = useState<OrderStatus | "all">("all")
  const [offset, setOffset] = useState(0)

  useEffect(() => {
    const t = setTimeout(() => { setSearch(searchInput); setOffset(0) }, 350)
    return () => clearTimeout(t)
  }, [searchInput])

  const { data, isLoading, isFetching } = useQuery({
    queryKey: ["orders", { search, status, offset }],
    queryFn: () => getOrders({ search, status, limit: PAGE, offset }),
    placeholderData: keepPreviousData,
  })
  const rows = useMemo(() => data?.rows ?? [], [data])
  const total = data?.total ?? 0

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
          <Input placeholder="Search ref, buyer, store…" className="pl-8" value={searchInput} onChange={(e) => setSearchInput(e.target.value)} />
        </div>
        <Select value={status} onChange={(e) => { setStatus(e.target.value as OrderStatus | "all"); setOffset(0) }}>
          <option value="all">All statuses</option>
          <option value="pending">Pending</option>
          <option value="paid">Paid</option>
          <option value="shipped">Shipped</option>
          <option value="delivered">Delivered</option>
          <option value="cancelled">Cancelled</option>
          <option value="refunded">Refunded</option>
        </Select>
        <Button variant="outline" onClick={() => exportCsv("orders.csv", rows.map((o) => ({ reference: o.reference, buyer: o.buyer, store: o.store, items: o.items, total: o.total, status: o.status, created: o.createdAt })))}>
          <Download /> Export
        </Button>
        <Pagination offset={offset} limit={PAGE} total={total} onChange={setOffset} />
      </div>

      <Card className="py-0">
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Reference</TableHead>
                <TableHead>Buyer</TableHead>
                <TableHead>Store</TableHead>
                <TableHead className="text-right">Items</TableHead>
                <TableHead className="text-right">Total</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Created</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody className={isFetching ? "opacity-60" : ""}>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}><TableCell colSpan={7}><Skeleton className="h-8 w-full" /></TableCell></TableRow>
                ))
              ) : rows.length === 0 ? (
                <TableRow><TableCell colSpan={7} className="text-muted-foreground py-10 text-center">No orders match your filters.</TableCell></TableRow>
              ) : (
                rows.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell className="font-medium">{o.reference}</TableCell>
                    <TableCell>{o.buyer}</TableCell>
                    <TableCell className="text-muted-foreground">{o.store}</TableCell>
                    <TableCell className="text-right tabular-nums">{o.items}</TableCell>
                    <TableCell className="text-right font-medium tabular-nums">{formatNaira(o.total)}</TableCell>
                    <TableCell><StatusBadge status={o.status} /></TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(o.createdAt)}</TableCell>
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

function DisputesTable({
  disputes,
  loading,
  canResolve,
}: {
  disputes: import("@/lib/types").AdminDispute[]
  loading: boolean
  canResolve: boolean
}) {
  const qc = useQueryClient()

  async function resolve(id: string, ref: string, action: "resolved" | "rejected") {
    const { confirmed, reason } = await confirm({
      title: action === "resolved" ? `Resolve dispute ${ref} for the buyer?` : `Reject dispute ${ref}?`,
      confirmLabel: action === "resolved" ? "Resolve" : "Reject",
      danger: action === "rejected",
      withReason: true,
      reasonLabel: "Note (sent to buyer, recorded in audit)",
    })
    if (!confirmed) return
    try {
      await resolveDispute(id, action, reason)
      toast.success(`Dispute ${action}`)
      qc.invalidateQueries({ queryKey: ["disputes"] })
    } catch {
      toast.error("Failed to resolve dispute")
    }
  }

  return (
    <Card className="py-0">
      <CardContent className="p-0">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Order</TableHead>
              <TableHead>Store</TableHead>
              <TableHead>Buyer</TableHead>
              <TableHead>Reason</TableHead>
              <TableHead className="text-right">Amount</TableHead>
              <TableHead>Status</TableHead>
              <TableHead>Open for</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 6 }).map((_, i) => (
                <TableRow key={i}><TableCell colSpan={8}><Skeleton className="h-8 w-full" /></TableCell></TableRow>
              ))
            ) : disputes.length === 0 ? (
              <TableRow><TableCell colSpan={8} className="text-muted-foreground py-10 text-center">No disputes.</TableCell></TableRow>
            ) : (
              disputes.map((d) => {
                const openish = d.status === "open" || d.status === "under_review"
                return (
                  <TableRow key={d.id}>
                    <TableCell className="font-medium">{d.orderRef}</TableCell>
                    <TableCell className="text-muted-foreground">{d.store}</TableCell>
                    <TableCell>{d.buyer}</TableCell>
                    <TableCell>{d.reason}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatNaira(d.amount)}</TableCell>
                    <TableCell><StatusBadge status={d.status} /></TableCell>
                    <TableCell className={openish ? "text-amber-600 dark:text-amber-400" : "text-muted-foreground"}>
                      {formatRelative(d.openedAt).replace(" ago", "")}
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        {openish && canResolve ? (
                          <>
                            <Button variant="ghost" size="icon-sm" title="Resolve for buyer" onClick={() => resolve(d.id, d.orderRef, "resolved")}>
                              <Check className="text-emerald-500" />
                            </Button>
                            <Button variant="ghost" size="icon-sm" title="Reject dispute" onClick={() => resolve(d.id, d.orderRef, "rejected")}>
                              <X className="text-destructive" />
                            </Button>
                          </>
                        ) : (
                          <span className="text-muted-foreground text-xs">—</span>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                )
              })
            )}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  )
}
