"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { KeyRound, Loader2, RotateCcw, UserPlus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Dialog } from "@/components/ui/dialog"
import { cn } from "@/lib/utils"
import { createUser, getRoles, resetUserPassword, setUserRoles, type CreateUserResult, type RoleInfo } from "@/lib/resources"
import { toast } from "@/lib/toast"
import type { AdminUser } from "@/lib/types"

export function ResetPasswordButton({ user }: { user: AdminUser }) {
  const [open, setOpen] = useState(false)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<{ tempPassword: string; emailSent: boolean } | null>(null)

  async function doReset() {
    setLoading(true)
    try {
      const r = await resetUserPassword(user.id)
      setResult(r)
      toast.success(r.emailSent ? "Temporary password emailed" : "Temporary password generated")
    } catch {
      toast.error("Failed to reset password")
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button variant="ghost" size="icon-sm" title="Reset password" onClick={() => { setResult(null); setOpen(true) }}>
        <RotateCcw />
      </Button>
      <Dialog open={open} onClose={() => setOpen(false)} title="Reset password" description={user.email}>
        {result ? (
          <div className="space-y-3">
            <p className="text-sm">
              {result.emailSent ? "A new temporary password was emailed." : "Email isn’t configured — share this temporary password:"}
            </p>
            <div className="bg-muted flex items-center justify-between gap-2 rounded-lg px-3 py-2 font-mono text-sm">
              <span className="truncate">{result.tempPassword}</span>
              <Button variant="outline" size="sm" onClick={() => navigator.clipboard?.writeText(result.tempPassword)}>Copy</Button>
            </div>
            <p className="text-muted-foreground text-xs">They’ll be required to set a new password on next sign-in.</p>
            <div className="flex justify-end"><Button onClick={() => setOpen(false)}>Done</Button></div>
          </div>
        ) : (
          <div className="space-y-3">
            <p className="text-sm">Generate a new temporary password for <span className="font-medium">{user.email}</span> and force a change on next login?</p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
              <Button onClick={doReset} disabled={loading}>{loading ? <Loader2 className="animate-spin" /> : null} Reset</Button>
            </div>
          </div>
        )}
      </Dialog>
    </>
  )
}

function RolePicker({
  roles,
  selected,
  onToggle,
}: {
  roles: RoleInfo[]
  selected: Set<string>
  onToggle: (name: string) => void
}) {
  return (
    <div className="space-y-1.5">
      {roles.map((r) => {
        const on = selected.has(r.name)
        return (
          <button
            key={r.name}
            type="button"
            onClick={() => onToggle(r.name)}
            className={cn(
              "flex w-full items-center gap-3 rounded-lg border px-3 py-2 text-left transition-colors",
              on ? "border-primary bg-primary/5" : "hover:bg-muted/50",
            )}
          >
            <span
              className={cn(
                "flex size-4 shrink-0 items-center justify-center rounded border",
                on ? "bg-primary border-primary text-primary-foreground" : "border-input",
              )}
            >
              {on ? "✓" : ""}
            </span>
            <span className="min-w-0">
              <span className="block text-sm font-medium capitalize">{r.name.replace(/_/g, " ")}</span>
              <span className="text-muted-foreground block text-xs">{r.description}</span>
            </span>
          </button>
        )
      })}
    </div>
  )
}

export function NewUserButton() {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [name, setName] = useState("")
  const [email, setEmail] = useState("")
  const [roles, setRoles] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [created, setCreated] = useState<CreateUserResult & { email: string } | null>(null)
  const rolesQuery = useQuery({ queryKey: ["roles"], queryFn: getRoles, enabled: open })

  function closeAll() {
    setOpen(false)
    setName("")
    setEmail("")
    setRoles(new Set())
    setError(null)
    setCreated(null)
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      const res = await createUser({ email, name, roles: [...roles] })
      await qc.invalidateQueries({ queryKey: ["users"] })
      await qc.invalidateQueries({ queryKey: ["staff"] })
      setCreated({ ...res, email })
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to create account")
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button onClick={() => setOpen(true)}>
        <UserPlus /> New user
      </Button>
      <Dialog open={open} onClose={closeAll} title="Create staff account" description="A temporary password is generated and emailed to them.">
        {created ? (
          <div className="space-y-3">
            <p className="text-sm">
              Account created for <span className="font-medium">{created.email}</span>.
              {created.emailSent
                ? " A temporary password was emailed to them."
                : " Email isn’t configured — share this temporary password with them:"}
            </p>
            <div className="bg-muted flex items-center justify-between gap-2 rounded-lg px-3 py-2 font-mono text-sm">
              <span className="truncate">{created.tempPassword}</span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => navigator.clipboard?.writeText(created.tempPassword)}
              >
                Copy
              </Button>
            </div>
            <p className="text-muted-foreground text-xs">
              They’ll be required to set their own password on first sign-in.
            </p>
            <div className="flex justify-end">
              <Button onClick={closeAll}>Done</Button>
            </div>
          </div>
        ) : (
          <form onSubmit={submit} className="space-y-3">
            <Input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
            <Input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
            <div>
              <p className="mb-1.5 text-sm font-medium">Roles</p>
              <RolePicker
                roles={rolesQuery.data ?? []}
                selected={roles}
                onToggle={(n) =>
                  setRoles((s) => {
                    const next = new Set(s)
                    if (next.has(n)) next.delete(n)
                    else next.add(n)
                    return next
                  })
                }
              />
            </div>
            {error ? <p className="text-destructive text-sm">{error}</p> : null}
            <div className="flex justify-end gap-2 pt-1">
              <Button type="button" variant="outline" onClick={closeAll}>Cancel</Button>
              <Button type="submit" disabled={loading}>
                {loading ? <Loader2 className="animate-spin" /> : null} Create &amp; email password
              </Button>
            </div>
          </form>
        )}
      </Dialog>
    </>
  )
}

export function EditRolesButton({ user }: { user: AdminUser }) {
  const qc = useQueryClient()
  const [open, setOpen] = useState(false)
  const [roles, setRoles] = useState<Set<string>>(new Set(user.roles ?? []))
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const rolesQuery = useQuery({ queryKey: ["roles"], queryFn: getRoles, enabled: open })

  function openDialog() {
    setRoles(new Set(user.roles ?? []))
    setError(null)
    setOpen(true)
  }

  async function save() {
    setError(null)
    setLoading(true)
    try {
      await setUserRoles(user.id, [...roles])
      await qc.invalidateQueries({ queryKey: ["users"] })
      await qc.invalidateQueries({ queryKey: ["staff"] })
      setOpen(false)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to update roles")
    } finally {
      setLoading(false)
    }
  }

  return (
    <>
      <Button variant="ghost" size="icon-sm" title="Edit roles" onClick={openDialog}>
        <KeyRound />
      </Button>
      <Dialog open={open} onClose={() => setOpen(false)} title="Edit roles" description={user.name}>
        <div className="space-y-3">
          <RolePicker
            roles={rolesQuery.data ?? []}
            selected={roles}
            onToggle={(n) =>
              setRoles((s) => {
                const next = new Set(s)
                if (next.has(n)) next.delete(n)
                else next.add(n)
                return next
              })
            }
          />
          {error ? <p className="text-destructive text-sm">{error}</p> : null}
          <div className="flex justify-end gap-2">
            <Button variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
            <Button onClick={save} disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : null} Save
            </Button>
          </div>
        </div>
      </Dialog>
    </>
  )
}
