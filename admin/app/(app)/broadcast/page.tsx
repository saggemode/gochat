"use client"

import { useState } from "react"
import { Megaphone, Loader2, Send } from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { usePermissions } from "@/lib/use-permissions"
import { broadcast } from "@/lib/resources"
import { confirm } from "@/lib/confirm"
import { toast } from "@/lib/toast"

export default function BroadcastPage() {
  const { can, ready } = usePermissions()
  const allowed = can("admin:roles:write")
  const [title, setTitle] = useState("")
  const [body, setBody] = useState("")
  const [loading, setLoading] = useState(false)

  if (ready && !allowed) {
    return (
      <Card>
        <CardContent className="text-muted-foreground py-16 text-center text-sm">
          Only a super admin can send broadcasts.
        </CardContent>
      </Card>
    )
  }

  async function send() {
    if (!title.trim() || !body.trim()) {
      toast.error("Title and message are required")
      return
    }
    const { confirmed } = await confirm({
      title: "Send push to every device?",
      message: "This sends a push notification to all registered devices. It can't be undone.",
      confirmLabel: "Send broadcast",
      danger: true,
    })
    if (!confirmed) return
    setLoading(true)
    try {
      const r = await broadcast(title.trim(), body.trim())
      toast.success(`Sent to ${r.sent} of ${r.recipients} devices`)
      setTitle("")
      setBody("")
    } catch {
      toast.error("Broadcast failed (push not configured?)")
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="mx-auto max-w-lg">
      <Card>
        <CardHeader>
          <div className="mb-1 flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
            <Megaphone className="size-5" />
          </div>
          <CardTitle>Broadcast notification</CardTitle>
          <CardDescription>Send a push notification to every GoChat device.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-3">
          <div className="space-y-1.5">
            <label className="text-sm font-medium">Title</label>
            <Input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. New feature available" maxLength={64} />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium">Message</label>
            <textarea
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="What do you want to tell everyone?"
              maxLength={240}
              rows={3}
              className="border-input focus-visible:border-ring focus-visible:ring-ring/50 w-full rounded-lg border bg-transparent px-3 py-2 text-sm shadow-sm outline-none focus-visible:ring-3 dark:bg-input/30"
            />
          </div>
          <div className="flex justify-end">
            <Button onClick={send} disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : <Send />} Send broadcast
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
