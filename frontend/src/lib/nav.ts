import {
  FileText,
  Sparkles,
  ArrowLeftRight,
  Bell,
  ClipboardList,
  CreditCard,
  HandCoins,
  Landmark,
  LayoutDashboard,
  ReceiptText,
  ShieldAlert,
  ShieldCheck,
  FileCheck2,
  type LucideIcon,
} from "lucide-react";
import type { Role } from "@/types/api";

export interface NavItem {
  href: string;
  label: string;
  icon: LucideIcon;
  /** Roles allowed to see this entry. Omitted means every signed-in role. */
  roles?: Role[];
}

export interface NavGroup {
  /** Names the group for assistive technology; also drawn above the group. */
  label: string;
  items: NavItem[];
}

/**
 * Navigation, in the order it appears in the sidebar.
 *
 * Grouped rather than one flat list: banking, then the customer's own account,
 * then staff tools. Eleven undifferentiated links read as a settings menu; three
 * short groups read as a product.
 *
 * Entries are filtered by role for clarity, not for security — hiding a link
 * hides nothing from anyone determined to type the URL. The backend's
 * `@PreAuthorize` checks are what actually gate staff-only data.
 *
 * Every entry carries an icon *and* a label. The icon is decorative: it makes a
 * familiar row findable at a glance, and it is never the only thing naming the
 * destination.
 */
export const NAV_GROUPS: NavGroup[] = [
  {
    label: "Banking",
    items: [
      { href: "/dashboard", label: "Overview", icon: LayoutDashboard },
      { href: "/accounts", label: "Accounts", icon: Landmark },
      { href: "/move-money", label: "Move Money", icon: ArrowLeftRight },
      { href: "/transactions", label: "Transactions", icon: ReceiptText },
      { href: "/payments", label: "Payments", icon: HandCoins },
    ],
  },
  /*
   * Borrowing is its own family, not a corner of banking. Cards and loans sat
   * under Banking beside the deposit accounts, which made credit look like
   * something you get once you have an account — and left the products a
   * customer could apply for with nowhere to be listed at all.
   */
  {
    label: "Borrow & credit",
    items: [
      { href: "/credit", label: "Explore Credit", icon: Sparkles },
      { href: "/applications", label: "My Applications", icon: FileText },
      { href: "/cards", label: "Credit Cards", icon: CreditCard },
      { href: "/loans", label: "Loans", icon: ClipboardList },
    ],
  },
  {
    label: "Your account",
    items: [
      { href: "/notifications", label: "Notifications", icon: Bell },
      { href: "/profile", label: "Profile & Security", icon: ShieldCheck },
    ],
  },
  {
    label: "Staff tools",
    items: [
      { href: "/admin/kyc", label: "KYC Review", icon: FileCheck2, roles: ["EMPLOYEE", "ADMIN"] },
      {
        href: "/admin/applications",
        label: "Applications",
        icon: ClipboardList,
        roles: ["EMPLOYEE", "ADMIN"],
      },
      { href: "/admin/fraud", label: "Fraud Alerts", icon: ShieldAlert, roles: ["EMPLOYEE", "ADMIN"] },
    ],
  },
];

/** Flat list, for anything that only cares about the destinations. */
export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

export function navItemsForRole(role: Role): NavItem[] {
  return NAV_ITEMS.filter((item) => !item.roles || item.roles.includes(role));
}

/** Groups with their entries filtered, and any group left empty dropped. */
export function navGroupsForRole(role: Role): NavGroup[] {
  return NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => !item.roles || item.roles.includes(role)),
  })).filter((group) => group.items.length > 0);
}

export function isStaff(role: Role): boolean {
  return role === "EMPLOYEE" || role === "ADMIN";
}

/** Marks the sidebar entry matching the current path, including nested routes. */
export function isActivePath(pathname: string, href: string): boolean {
  if (href === "/dashboard") return pathname === "/dashboard";
  return pathname === href || pathname.startsWith(`${href}/`);
}

/**
 * Two letters for the avatar disc.
 *
 * Initials, not a photograph: there is no avatar upload behind this, and
 * fetching one from an external service would send the customer's email address
 * to a third party to get a picture back.
 */
export function initialsFor(firstName?: string | null, lastName?: string | null): string {
  const first = firstName?.trim()?.[0] ?? "";
  const last = lastName?.trim()?.[0] ?? "";
  const initials = `${first}${last}`.toUpperCase();
  return initials || "—";
}
