"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { useQueryClient } from "@tanstack/react-query"
import { KeyRound, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { changePassword } from "@/lib/resources"
import { usePermissions } from "@/lib/use-permissions"

export default function ChangePasswordPage() {
  const router = useRouter()
  const qc = useQueryClient()
  const { me } = usePermissions()
  const forced = !!me?.mustChangePassword
  const [pw, setPw] = useState("")
  const [confirm, setConfirm] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (pw.length < 8) {
      setError("Password must be at least 8 characters")
      return
    }
    if (pw !== confirm) {
      setError("Passwords do not match")
      return
    }
    setLoading(true)
    try {
      await changePassword(pw)
      await qc.invalidateQueries({ queryKey: ["me"] })
      router.replace("/")
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to change password")
      setLoading(false)
    }
  }

  return (
    <div className="mx-auto max-w-md">
      <Card>
        <CardHeader>
          <div className="mb-1 flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <KeyRound className="size-5" />
          </div>
          <CardTitle>{forced ? "Set your password" : "Change password"}</CardTitle>
          <CardDescription>
            {forced
              ? "Your account uses a temporary password. Choose a new one to continue."
              : "Choose a new password for your account."}
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={submit} className="space-y-3">
            <div className="space-y-1.5">
              <label htmlFor="pw" className="text-sm font-medium">New password</label>
              <Input id="pw" type="password" autoComplete="new-password" placeholder="At least 8 characters" value={pw} onChange={(e) => setPw(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <label htmlFor="confirm" className="text-sm font-medium">Confirm password</label>
              <Input id="confirm" type="password" autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </div>
            {error ? <p className="text-destructive text-sm">{error}</p> : null}
            <Button type="submit" className="w-full" disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : null} Save password
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
