"use client"

import { useMemo, useState } from "react"
import { useQuery } from "@tanstack/react-query"
import { Download, Search } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table"
import { formatDateTime } from "@/lib/format"
import { getAudit } from "@/lib/resources"
import { exportCsv } from "@/lib/csv"

export default function AuditPage() {
  const { data, isLoading } = useQuery({ queryKey: ["audit"], queryFn: getAudit })
  const [q, setQ] = useState("")

  const entries = useMemo(() => {
    const all = data ?? []
    const needle = q.trim().toLowerCase()
    if (!needle) return all
    return all.filter(
      (e) =>
        e.action.toLowerCase().includes(needle) ||
        e.actorEmail.toLowerCase().includes(needle) ||
        e.targetType.toLowerCase().includes(needle) ||
        e.detail.toLowerCase().includes(needle),
    )
  }, [data, q])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <p className="text-muted-foreground text-sm">Every admin action — who did what, to which record, and when.</p>
        <div className="relative ml-auto w-48">
          <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
          <Input placeholder="Filter…" className="pl-8" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <Button
          variant="outline"
          onClick={() =>
            exportCsv("audit.csv", entries.map((e) => ({ when: e.createdAt, admin: e.actorEmail, action: e.action, target: `${e.targetType}:${e.targetId}`, detail: e.detail })))
          }
        >
          <Download /> Export
        </Button>
      </div>
      <Card className="py-0">
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>When</TableHead>
                <TableHead>Admin</TableHead>
                <TableHead>Action</TableHead>
                <TableHead>Target</TableHead>
                <TableHead>Detail</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 8 }).map((_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={5}>
                      <Skeleton className="h-8 w-full" />
                    </TableCell>
                  </TableRow>
                ))
              ) : entries.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground py-10 text-center">
                    No admin actions recorded yet.
                  </TableCell>
                </TableRow>
              ) : (
                entries.map((e) => (
                  <TableRow key={e.id}>
                    <TableCell className="text-muted-foreground whitespace-nowrap">
                      {formatDateTime(e.createdAt)}
                    </TableCell>
                    <TableCell className="font-medium">{e.actorEmail || "—"}</TableCell>
                    <TableCell>
                      <Badge variant="secondary">{e.action}</Badge>
                    </TableCell>
                    <TableCell className="text-muted-foreground">
                      {e.targetType}
                      {e.targetId ? ` · ${e.targetId.slice(0, 8)}` : ""}
                    </TableCell>
                    <TableCell>{e.detail || "—"}</TableCell>
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
