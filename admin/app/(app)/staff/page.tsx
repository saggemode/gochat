"use client"

import { useQuery, useQueryClient } from "@tanstack/react-query"
import { Pause, Check } from "lucide-react"
import { Card, CardContent } from "@/components/ui/card"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
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
import { NewUserButton, EditRolesButton, ResetPasswordButton } from "@/components/users/user-management"
import { usePermissions } from "@/lib/use-permissions"
import { getStaff, setUserStatus } from "@/lib/resources"
import { confirm } from "@/lib/confirm"
import { toast } from "@/lib/toast"
import { formatDate, initials } from "@/lib/format"
import type { AdminUser, UserStatus } from "@/lib/types"

export default function StaffPage() {
  const qc = useQueryClient()
  const { can, ready } = usePermissions()
  const allowed = can("admin:roles:write")
  const { data, isLoading } = useQuery({ queryKey: ["staff"], queryFn: getStaff, enabled: allowed })
  const staff = data ?? []

  async function toggleActive(u: AdminUser) {
    const next: UserStatus = u.status === "active" ? "suspended" : "active"
    const { confirmed, reason } = await confirm({
      title: next === "suspended" ? `Deactivate ${u.name}?` : `Reactivate ${u.name}?`,
      message: next === "suspended" ? "They will lose access to the console." : undefined,
      confirmLabel: next === "suspended" ? "Deactivate" : "Reactivate",
      danger: next === "suspended",
      withReason: next === "suspended",
      reasonLabel: "Reason (recorded in audit log)",
    })
    if (!confirmed) return
    try {
      await setUserStatus(u.id, next, reason)
      toast.success(next === "suspended" ? "Staff deactivated" : "Staff reactivated")
      qc.invalidateQueries({ queryKey: ["staff"] })
    } catch {
      toast.error("Failed to update staff")
    }
  }

  if (ready && !allowed) {
    return (
      <Card>
        <CardContent className="text-muted-foreground py-16 text-center text-sm">
          Only a super admin can manage staff accounts.
        </CardContent>
      </Card>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <p className="text-muted-foreground text-sm">
          Admin accounts and their roles. {staff.length} member{staff.length === 1 ? "" : "s"}.
        </p>
        <div className="ml-auto">
          <NewUserButton />
        </div>
      </div>

      <Card className="py-0">
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Member</TableHead>
                <TableHead>Roles</TableHead>
                <TableHead>Status</TableHead>
                <TableHead>Added</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {isLoading ? (
                Array.from({ length: 4 }).map((_, i) => (
                  <TableRow key={i}>
                    <TableCell colSpan={5}>
                      <Skeleton className="h-8 w-full" />
                    </TableCell>
                  </TableRow>
                ))
              ) : staff.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={5} className="text-muted-foreground py-10 text-center">
                    No staff yet. Use “New user” to add one.
                  </TableCell>
                </TableRow>
              ) : (
                staff.map((u) => (
                  <TableRow key={u.id}>
                    <TableCell>
                      <div className="flex items-center gap-3">
                        <Avatar className="size-8">
                          <AvatarFallback>{initials(u.name)}</AvatarFallback>
                        </Avatar>
                        <div className="min-w-0">
                          <div className="truncate font-medium">{u.name}</div>
                          <div className="text-muted-foreground text-xs">{u.email}</div>
                        </div>
                      </div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {(u.roles ?? []).map((r) => (
                          <Badge key={r} variant={r === "super_admin" ? "default" : "secondary"} className="capitalize">
                            {r.replace(/_/g, " ")}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={u.status} />
                    </TableCell>
                    <TableCell className="text-muted-foreground">{formatDate(u.joinedAt)}</TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-1">
                        <ResetPasswordButton user={u} />
                        <EditRolesButton user={u} />
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          title={u.status === "active" ? "Deactivate" : "Reactivate"}
                          onClick={() => toggleActive(u)}
                        >
                          {u.status === "active" ? <Pause className="text-amber-500" /> : <Check className="text-emerald-500" />}
                        </Button>
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
