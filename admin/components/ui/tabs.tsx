"use client"

import * as React from "react"
import { cn } from "@/lib/utils"

type TabsCtx = { value: string; setValue: (v: string) => void }
const Ctx = React.createContext<TabsCtx | null>(null)

function Tabs({
  defaultValue,
  value: controlled,
  onValueChange,
  className,
  children,
}: {
  defaultValue: string
  value?: string
  onValueChange?: (v: string) => void
  className?: string
  children: React.ReactNode
}) {
  const [internal, setInternal] = React.useState(defaultValue)
  const value = controlled ?? internal
  const setValue = (v: string) => {
    setInternal(v)
    onValueChange?.(v)
  }
  return (
    <Ctx.Provider value={{ value, setValue }}>
      <div className={className}>{children}</div>
    </Ctx.Provider>
  )
}

function TabsList({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      role="tablist"
      className={cn(
        "inline-flex h-9 items-center justify-center gap-1 rounded-lg bg-muted p-1 text-muted-foreground",
        className,
      )}
      {...props}
    />
  )
}

function TabsTrigger({ value, className, ...props }: React.ComponentProps<"button"> & { value: string }) {
  const ctx = React.useContext(Ctx)!
  const active = ctx.value === value
  return (
    <button
      role="tab"
      aria-selected={active}
      onClick={() => ctx.setValue(value)}
      className={cn(
        "inline-flex items-center justify-center gap-1.5 rounded-md px-3 py-1 text-sm font-medium whitespace-nowrap transition-colors outline-none",
        active ? "bg-background text-foreground shadow-sm" : "hover:text-foreground",
        className,
      )}
      {...props}
    />
  )
}

function TabsContent({ value, className, ...props }: React.ComponentProps<"div"> & { value: string }) {
  const ctx = React.useContext(Ctx)!
  if (ctx.value !== value) return null
  return <div role="tabpanel" className={cn("mt-4", className)} {...props} />
}

export { Tabs, TabsList, TabsTrigger, TabsContent }
