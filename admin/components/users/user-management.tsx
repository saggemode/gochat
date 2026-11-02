"use client"

import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { KeyRound, Loader2, UserPlus } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Dialog } from "@/components/ui/dialog"
import { cn } from "@/lib/utils"
import { createUser, getRoles, setUserRoles, type RoleInfo } from "@/lib/resources"
import type { AdminUser } from "@/lib/types"

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
  const [password, setPassword] = useState("")
  const [roles, setRoles] = useState<Set<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const rolesQuery = useQuery({ queryKey: ["roles"], queryFn: getRoles, enabled: open })

  function reset() {
    setName("")
    setEmail("")
    setPassword("")
    setRoles(new Set())
    setError(null)
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 8) {
      setError("Password must be at least 8 characters")
      return
    }
    setLoading(true)
    try {
      await createUser({ email, password, name, roles: [...roles] })
      await qc.invalidateQueries({ queryKey: ["users"] })
      setOpen(false)
      reset()
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
      <Dialog open={open} onClose={() => setOpen(false)} title="Create account" description="Create a user and assign roles.">
        <form onSubmit={submit} className="space-y-3">
          <Input placeholder="Name" value={name} onChange={(e) => setName(e.target.value)} />
          <Input type="email" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} />
          <Input type="password" placeholder="Temporary password (min 8 chars)" value={password} onChange={(e) => setPassword(e.target.value)} />
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
            <Button type="button" variant="outline" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : null} Create
            </Button>
          </div>
        </form>
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
