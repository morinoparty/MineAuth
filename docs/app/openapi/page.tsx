"use client";

import { ApiReferenceReact } from "@scalar/api-reference-react";
import "@scalar/api-reference-react/style.css";

export default function OpenAPIPage() {
	return (
		<div className="h-screen w-full">
			<ApiReferenceReact
				configuration={{
					url: "/openapi/openapi-mineauth.yaml",
					theme: "kepler",
					layout: "modern",
					darkMode: true,
					metaData: {
						title: "MineAuth API Reference",
						description:
							"OAuth2 and OpenID Connect authentication API for Minecraft",
					},
					hideDownloadButton: false,
					hideModels: false,
					defaultHttpClient: {
						targetKey: "js",
						clientKey: "fetch",
					},
				}}
			/>
		</div>
	);
}
