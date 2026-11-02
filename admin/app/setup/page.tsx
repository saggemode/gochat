"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { MessageCircle, Loader2, ShieldCheck } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { getSetupStatus, setupSuperAdmin } from "@/lib/resources"
import { setSession, useSession } from "@/lib/auth"

export default function SetupPage() {
  const router = useRouter()
  const { session } = useSession()
  const [checking, setChecking] = useState(true)
  const [name, setName] = useState("")
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  // If setup is already done, there is no super admin to create — go to login.
  useEffect(() => {
    let active = true
    getSetupStatus()
      .then((s) => {
        if (!active) return
        if (!s.needsSetup) router.replace(session ? "/" : "/login")
        else setChecking(false)
      })
      .catch(() => active && setChecking(false))
    return () => {
      active = false
    }
  }, [router, session])

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    if (password.length < 8) {
      setError("Password must be at least 8 characters")
      return
    }
    setLoading(true)
    try {
      const res = await setupSuperAdmin(email, password, name)
      setSession(res)
      router.replace("/")
    } catch (err) {
      setError(err instanceof Error ? err.message : "Setup failed")
      setLoading(false)
    }
  }

  if (checking) {
    return (
      <div className="flex min-h-screen items-center justify-center text-muted-foreground text-sm">
        Loading…
      </div>
    )
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <div className="mb-2 flex size-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <MessageCircle className="size-6" />
          </div>
          <CardTitle className="text-xl">Welcome to GoChat Admin</CardTitle>
          <CardDescription>Create the first account — it becomes the super admin</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-3">
            <div className="space-y-1.5">
              <label htmlFor="name" className="text-sm font-medium">Name</label>
              <Input id="name" placeholder="Jane Admin" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <label htmlFor="email" className="text-sm font-medium">Email</label>
              <Input id="email" type="email" autoComplete="username" placeholder="you@company.com" value={email} onChange={(e) => setEmail(e.target.value)} />
            </div>
            <div className="space-y-1.5">
              <label htmlFor="password" className="text-sm font-medium">Password</label>
              <Input id="password" type="password" autoComplete="new-password" placeholder="At least 8 characters" value={password} onChange={(e) => setPassword(e.target.value)} />
            </div>
            {error ? <p className="text-destructive text-sm">{error}</p> : null}
            <Button type="submit" size="lg" className="w-full" disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : <ShieldCheck />}
              Create super admin
            </Button>
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
