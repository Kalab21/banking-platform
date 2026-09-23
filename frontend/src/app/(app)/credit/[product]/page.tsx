import type { Metadata } from "next";
import { notFound } from "next/navigation";
import { Card, PageHeader } from "@/components/ui/primitives";
import { ApplyForm } from "@/features/credit/ApplyForm";
import { CREDIT_PRODUCTS, creditProduct } from "@/features/credit/products";

/** The route segment for a product: CREDIT_CARD becomes credit-card. */
function typeFromSlug(slug: string): string {
  return slug.toUpperCase().replace(/-/g, "_");
}

export function generateStaticParams() {
  return CREDIT_PRODUCTS.map((product) => ({
    product: product.type.toLowerCase().replace(/_/g, "-"),
  }));
}

export async function generateMetadata({
  params,
}: {
  params: Promise<{ product: string }>;
}): Promise<Metadata> {
  const { product } = await params;
  const found = creditProduct(typeFromSlug(product));
  return { title: found ? `Apply for a ${found.name.toLowerCase()}` : "Apply" };
}

export default async function ApplyPage({ params }: { params: Promise<{ product: string }> }) {
  const { product } = await params;
  const found = creditProduct(typeFromSlug(product));

  // Only the four credit products. A deposit account is opened through
  // onboarding, and anything else is not a product this bank offers.
  if (!found) notFound();

  return (
    <>
      <PageHeader title={`Apply for a ${found.name.toLowerCase()}`} description={found.summary} />
      <Card>
        <ApplyForm product={found} />
      </Card>
    </>
  );
}
