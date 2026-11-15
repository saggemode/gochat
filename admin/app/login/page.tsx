"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { MessageCircle, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { login, useSession } from "@/lib/auth"
import { getSetupStatus } from "@/lib/resources"

export default function LoginPage() {
  const router = useRouter()
  const { session, ready } = useSession()
  const [email, setEmail] = useState("")
  const [password, setPassword] = useState("")
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    if (ready && session) router.replace("/")
  }, [ready, session, router])

  // First run: if no super admin exists yet, send the user to setup.
  useEffect(() => {
    let active = true
    getSetupStatus()
      .then((s) => {
        if (active && s.needsSetup) router.replace("/setup")
      })
      .catch(() => {})
    return () => {
      active = false
    }
  }, [router])

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    setLoading(true)
    try {
      await login(email, password)
      router.replace("/")
    } catch (err) {
      setError(err instanceof Error ? err.message : "Sign in failed")
      setLoading(false)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-sm">
        <CardHeader className="items-center text-center">
          <div className="mb-2 flex size-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <MessageCircle className="size-6" />
          </div>
          <CardTitle className="text-xl">GoChat Admin</CardTitle>
          <CardDescription>Sign in to the operations console</CardDescription>
        </CardHeader>
        <CardContent>
          <form onSubmit={onSubmit} className="space-y-3">
            <div className="space-y-1.5">
              <label htmlFor="email" className="text-sm font-medium">Email</label>
              <Input
                id="email"
                type="email"
                autoComplete="username"
                placeholder="admin@gochat.app"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <label htmlFor="password" className="text-sm font-medium">Password</label>
              <Input
                id="password"
                type="password"
                autoComplete="current-password"
                placeholder="••••••••"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
            {error ? <p className="text-destructive text-sm">{error}</p> : null}
            <Button type="submit" size="lg" className="w-full" disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : null}
              Sign in
            </Button>
            {process.env.NEXT_PUBLIC_USE_MOCKS === "true" ? (
              <p className="text-muted-foreground pt-1 text-center text-xs">
                Mock auth — any email &amp; password works for now.
              </p>
            ) : (
              <p className="text-muted-foreground pt-1 text-center text-xs">
                Use your GoChat admin account.
              </p>
            )}
          </form>
        </CardContent>
      </Card>
    </div>
  )
}
