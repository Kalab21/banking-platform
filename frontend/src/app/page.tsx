import { redirect } from "next/navigation";
import { getSession } from "@/lib/session";

/** Entry point: straight to the dashboard when signed in, otherwise to login. */
export default async function RootPage() {
  const session = await getSession();
  redirect(session ? "/dashboard" : "/login");
}
