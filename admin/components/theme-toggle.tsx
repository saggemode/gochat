"use client"

import { useSyncExternalStore } from "react"
import { Moon, Sun } from "lucide-react"
import { Button } from "@/components/ui/button"

const KEY = "gochat_admin_theme"

function subscribe(cb: () => void) {
  const obs = new MutationObserver(cb)
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ["class"] })
  return () => obs.disconnect()
}

function isDark() {
  return document.documentElement.classList.contains("dark")
}

export function ThemeToggle() {
  // Reflects the <html> dark class, staying in sync if it changes anywhere.
  const dark = useSyncExternalStore(subscribe, isDark, () => false)

  function toggle() {
    const next = !dark
    document.documentElement.classList.toggle("dark", next)
    try {
      localStorage.setItem(KEY, next ? "dark" : "light")
    } catch {
      /* ignore */
    }
  }

  return (
    <Button variant="ghost" size="icon" onClick={toggle} aria-label="Toggle theme">
      {dark ? <Sun /> : <Moon />}
    </Button>
  )
}
