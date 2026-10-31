import * as React from "react"
import { ChevronDown } from "lucide-react"
import { cn } from "@/lib/utils"

/** Lightweight styled wrapper over a native <select> for filters. */
function Select({ className, children, ...props }: React.ComponentProps<"select">) {
  return (
    <div className="relative inline-flex">
      <select
        className={cn(
          "h-9 appearance-none rounded-lg border border-input bg-transparent pl-3 pr-8 text-sm shadow-sm outline-none transition-colors",
          "focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50",
          "disabled:pointer-events-none disabled:opacity-50 dark:bg-input/30",
          className,
        )}
        {...props}
      >
        {children}
      </select>
      <ChevronDown className="text-muted-foreground pointer-events-none absolute right-2.5 top-1/2 size-4 -translate-y-1/2" />
    </div>
  )
}

export { Select }
