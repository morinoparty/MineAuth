import Link from "next/link";

// TODO: Scalar API Reference はTurbopack と互換性がないため、一時的に無効化
// https://github.com/scalar/scalar/issues でTurbopack対応を確認
export default function OpenAPIPage() {
	return (
		<div className="flex h-screen w-full flex-col items-center justify-center gap-4 p-8">
			<h1 className="text-2xl font-bold">MineAuth API Reference</h1>
			<p className="text-muted-foreground">
				OpenAPI specification is available for download.
			</p>
			<Link
				href="/openapi/openapi-mineauth.yaml"
				className="rounded-md bg-primary px-4 py-2 text-primary-foreground hover:bg-primary/90"
			>
				Download OpenAPI Spec (YAML)
			</Link>
		</div>
	);
}
