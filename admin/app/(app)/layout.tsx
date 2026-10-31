import { AppShell } from "@/components/layout/app-shell"

// The admin area is a client-rendered, auth-gated dashboard: every route waits
// on client-side data (session from localStorage, React Query), so navigations
// into it are legitimately not "instant". Opting the group layout out of
// instant-navigation + static-shell validation (a server-only export) is the
// documented way to tell Next.js that, and it covers all pages beneath it.
export const instant = false

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>
}
