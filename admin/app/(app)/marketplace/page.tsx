"use client"

import { useMemo, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Check, Search, X } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Select } from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
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
import { formatDate, formatNaira } from "@/lib/format"
import { getDisputes, getOrders, resolveDispute } from "@/lib/resources"
import type { AdminDispute, AdminOrder, OrderStatus } from "@/lib/types"

export default function MarketplacePage() {
  const orders = useQuery({ queryKey: ["orders"], queryFn: getOrders })
  const disputes = useQuery({ queryKey: ["disputes"], queryFn: getDisputes })

  const gmv = useMemo(
    () => (orders.data ?? []).filter((o) => o.status !== "cancelled").reduce((s, o) => s + o.total, 0),
    [orders.data],
  )
  const paidCount = (orders.data ?? []).filter((o) => o.status !== "cancelled" && o.status !== "pending").length
  const openDisputes = (disputes.data ?? []).filter((d) => d.status === "open" || d.status === "under_review").length

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Stat label="GMV (sample)" value={orders.isLoading ? "—" : formatNaira(gmv, { compact: true })} />
        <Stat label="Orders" value={orders.isLoading ? "—" : String(orders.data?.length ?? 0)} />
        <Stat
          label="Avg. order value"
          value={orders.isLoading || !paidCount ? "—" : formatNaira(Math.round(gmv / Math.max(paidCount, 1)), { compact: true })}
        />
        <Stat label="Open disputes" value={disputes.isLoading ? "—" : String(openDisputes)} />
      </div>

      <Tabs defaultValue="orders">
        <TabsList>
          <TabsTrigger value="orders">Orders</TabsTrigger>
          <TabsTrigger value="disputes">
            Disputes{openDisputes ? ` (${openDisputes})` : ""}
          </TabsTrigger>
        </TabsList>

        <TabsContent value="orders">
          <OrdersTable orders={orders.data ?? []} loading={orders.isLoading} />
        </TabsContent>

        <TabsContent value="disputes">
          <DisputesTable disputes={disputes.data ?? []} loading={disputes.isLoading} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <Card className="py-0">
      <CardContent className="p-4">
        <p className="text-muted-foreground text-xs">{label}</p>
        <p className="mt-1 text-xl font-semibold tracking-tight">{value}</p>
      </CardContent>
    </Card>
  )
}

function OrdersTable({ orders, loading }: { orders: AdminOrder[]; loading: boolean }) {
  const [q, setQ] = useState("")
  const [status, setStatus] = useState<OrderStatus | "all">("all")

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return orders.filter((o) => {
      if (status !== "all" && o.status !== status) return false
      if (!needle) return true
      return (
        o.reference.toLowerCase().includes(needle) ||
        o.buyer.toLowerCase().includes(needle) ||
        o.store.toLowerCase().includes(needle)
      )
    })
  }, [orders, q, status])

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <div className="relative min-w-0 flex-1 sm:max-w-xs">
          <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
          <Input placeholder="Search ref, buyer, store…" className="pl-8" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <Select value={status} onChange={(e) => setStatus(e.target.value as OrderStatus | "all")}>
          <option value="all">All statuses</option>
          <option value="pending">Pending</option>
          <option value="paid">Paid</option>
          <option value="shipped">Shipped</option>
          <option value="delivered">Delivered</option>
          <option value="cancelled">Cancelled</option>
          <option value="refunded">Refunded</option>
        </Select>
        <span className="text-muted-foreground ml-auto text-sm">{filtered.length} of {orders.length}</span>
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
            <TableBody>
              {loading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={7}><Skeleton className="h-8 w-full" /></TableCell>
                  </TableRow>
                ))
              ) : filtered.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} className="text-muted-foreground py-10 text-center">No orders match your filters.</TableCell>
                </TableRow>
              ) : (
                filtered.map((o) => (
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

function DisputesTable({ disputes, loading }: { disputes: AdminDispute[]; loading: boolean }) {
  const qc = useQueryClient()

  async function resolve(id: string, action: "resolved" | "rejected") {
    const prev = qc.getQueryData<AdminDispute[]>(["disputes"])
    qc.setQueryData<AdminDispute[]>(["disputes"], (cur) =>
      (cur ?? []).map((d) => (d.id === id ? { ...d, status: action } : d)),
    )
    try {
      await resolveDispute(id, action)
    } catch {
      if (prev) qc.setQueryData(["disputes"], prev)
      alert("Failed to resolve dispute")
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
              <TableHead>Opened</TableHead>
              <TableHead className="text-right">Actions</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {loading ? (
              Array.from({ length: 6 }).map((_, i) => (
                <TableRow key={i}>
                  <TableCell colSpan={8}><Skeleton className="h-8 w-full" /></TableCell>
                </TableRow>
              ))
            ) : disputes.length === 0 ? (
              <TableRow>
                <TableCell colSpan={8} className="text-muted-foreground py-10 text-center">No disputes.</TableCell>
              </TableRow>
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
                    <TableCell className="text-muted-foreground">{formatDate(d.openedAt)}</TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        {openish ? (
                          <>
                            <Button variant="ghost" size="icon-sm" title="Resolve for buyer" onClick={() => resolve(d.id, "resolved")}>
                              <Check className="text-emerald-500" />
                            </Button>
                            <Button variant="ghost" size="icon-sm" title="Reject dispute" onClick={() => resolve(d.id, "rejected")}>
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
