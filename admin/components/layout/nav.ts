import {
  LayoutDashboard,
  Users,
  ShoppingBag,
  ShieldAlert,
  ScrollText,
  type LucideIcon,
} from "lucide-react"

export type NavItem = {
  label: string
  href: string
  icon: LucideIcon
  /** Optional badge count key resolved by the sidebar. */
  badge?: "disputes" | "reports"
}

export const NAV: NavItem[] = [
  { label: "Overview", href: "/", icon: LayoutDashboard },
  { label: "Users", href: "/users", icon: Users },
  { label: "Marketplace", href: "/marketplace", icon: ShoppingBag, badge: "disputes" },
  { label: "Moderation", href: "/moderation", icon: ShieldAlert, badge: "reports" },
  { label: "Audit log", href: "/audit", icon: ScrollText },
]
