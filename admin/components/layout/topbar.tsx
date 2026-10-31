"use client"

import { useRouter } from "next/navigation"
import { Menu, LogOut, Search } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { Badge } from "@/components/ui/badge"
import { ThemeToggle } from "@/components/theme-toggle"
import { logout, useSession } from "@/lib/auth"
import { usePermissions } from "@/lib/use-permissions"
import { initials } from "@/lib/format"

export function Topbar({ title, onMenu }: { title: string; onMenu: () => void }) {
  const { session } = useSession()
  const { roles } = usePermissions()
  const router = useRouter()

  function signOut() {
    logout()
    router.replace("/login")
  }

  return (
    <header className="sticky top-0 z-30 flex h-14 items-center gap-3 border-b bg-background/80 px-4 backdrop-blur">
      <Button variant="ghost" size="icon" className="lg:hidden" onClick={onMenu} aria-label="Open menu">
        <Menu />
      </Button>

      <h1 className="text-base font-semibold">{title}</h1>
      {roles[0] ? (
        <Badge variant="outline" className="hidden capitalize sm:inline-flex">
          {roles[0].replace(/_/g, " ")}
        </Badge>
      ) : null}

      <div className="relative ml-auto hidden w-64 md:block">
        <Search className="text-muted-foreground pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2" />
        <Input placeholder="Search…" className="pl-8" />
      </div>

      <ThemeToggle />

      <div className="flex items-center gap-2">
        <Avatar>
          <AvatarFallback>{initials(session?.name ?? "Admin")}</AvatarFallback>
        </Avatar>
        <div className="hidden text-sm leading-tight sm:block">
          <div className="font-medium">{session?.name ?? "Admin"}</div>
          <div className="text-muted-foreground text-xs">{session?.email ?? ""}</div>
        </div>
        <Button variant="ghost" size="icon" onClick={signOut} aria-label="Sign out" title="Sign out">
          <LogOut />
        </Button>
      </div>
    </header>
  )
}
