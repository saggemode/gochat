"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { MessageCircle, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { NAV } from "./nav"

function isActive(pathname: string, href: string) {
  if (href === "/") return pathname === "/"
  return pathname === href || pathname.startsWith(href + "/")
}

export function Sidebar({
  open,
  onClose,
  counts,
}: {
  open: boolean
  onClose: () => void
  counts: { disputes?: number; reports?: number }
}) {
  const pathname = usePathname()

  return (
    <>
      {/* mobile backdrop */}
      <div
        className={cn(
          "fixed inset-0 z-40 bg-black/40 backdrop-blur-sm transition-opacity lg:hidden",
          open ? "opacity-100" : "pointer-events-none opacity-0",
        )}
        onClick={onClose}
      />
      <aside
        className={cn(
          "fixed inset-y-0 left-0 z-50 flex w-64 flex-col border-r bg-sidebar text-sidebar-foreground transition-transform lg:static lg:z-0 lg:translate-x-0",
          open ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex h-14 items-center gap-2 border-b px-4">
          <div className="flex size-7 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <MessageCircle className="size-4" />
          </div>
          <span className="font-semibold tracking-tight">GoChat</span>
          <span className="text-muted-foreground text-xs font-medium">Admin</span>
          <Button
            variant="ghost"
            size="icon-sm"
            className="ml-auto lg:hidden"
            onClick={onClose}
            aria-label="Close menu"
          >
            <X />
          </Button>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto p-3">
          {NAV.map((item) => {
            const active = isActive(pathname, item.href)
            const count = item.badge ? counts[item.badge] : undefined
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={onClose}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
                  active
                    ? "bg-sidebar-accent text-sidebar-accent-foreground"
                    : "text-muted-foreground hover:bg-sidebar-accent/60 hover:text-sidebar-foreground",
                )}
              >
                <item.icon className="size-4 shrink-0" />
                <span className="flex-1">{item.label}</span>
                {count ? (
                  <Badge variant={item.badge === "reports" ? "destructive" : "secondary"}>
                    {count}
                  </Badge>
                ) : null}
              </Link>
            )
          })}
        </nav>

        <div className="border-t p-3">
          <p className="text-muted-foreground px-3 text-xs">
            v0.1.0 · mock data
          </p>
        </div>
      </aside>
    </>
  )
}
