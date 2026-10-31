"use client"

import { useQuery } from "@tanstack/react-query"
import { Card, CardContent } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
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

export default function AuditPage() {
  const { data, isLoading } = useQuery({ queryKey: ["audit"], queryFn: getAudit })
  const entries = data ?? []

  return (
    <div className="space-y-4">
      <p className="text-muted-foreground text-sm">
        Every admin action — who did what, to which record, and when.
      </p>
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
