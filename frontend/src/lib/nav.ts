import type { Role } from "@/types/api";

export interface NavItem {
  href: string;
  label: string;
  /** Roles allowed to see this entry. Omitted means every signed-in role. */
  roles?: Role[];
}

/**
 * Navigation, in the order it appears in the sidebar.
 *
 * Entries are filtered by role for clarity, not for security — hiding a link
 * hides nothing from anyone determined to type the URL. The backend's
 * `@PreAuthorize` checks are what actually gate staff-only data.
 */
export const NAV_ITEMS: NavItem[] = [
  { href: "/dashboard", label: "Overview" },
  { href: "/accounts", label: "Accounts" },
  { href: "/transactions", label: "Transactions" },
  { href: "/payments", label: "Payments" },
  { href: "/loans", label: "Loans" },
  { href: "/cards", label: "Credit Cards" },
  { href: "/notifications", label: "Notifications" },
  { href: "/profile", label: "Profile & Security" },
  { href: "/admin/kyc", label: "KYC Review", roles: ["EMPLOYEE", "ADMIN"] },
  { href: "/admin/applications", label: "Applications", roles: ["EMPLOYEE", "ADMIN"] },
  { href: "/admin/fraud", label: "Fraud Alerts", roles: ["EMPLOYEE", "ADMIN"] },
];

export function navItemsForRole(role: Role): NavItem[] {
  return NAV_ITEMS.filter((item) => !item.roles || item.roles.includes(role));
}

export function isStaff(role: Role): boolean {
  return role === "EMPLOYEE" || role === "ADMIN";
}

/** Marks the sidebar entry matching the current path, including nested routes. */
export function isActivePath(pathname: string, href: string): boolean {
  if (href === "/dashboard") return pathname === "/dashboard";
  return pathname === href || pathname.startsWith(`${href}/`);
}
