import "./global.css";
import type { Metadata } from "next";
import type { ReactNode } from "react";
import { Provider } from "@/components/provider";

export const metadata: Metadata = {
	title: {
		template: "%s | MineAuth",
		default: "MineAuth Documentation",
	},
	description: "OAuth2 and OpenID Connect authentication plugin for Minecraft",
};

export default function RootLayout({ children }: { children: ReactNode }) {
    return (
        <html lang="ja" suppressHydrationWarning>
            <head>
                {/* Satoshi font */}
                <link rel="stylesheet" href="https://api.fontshare.com/v2/css?f[]=satoshi@1&display=swap" />
                {/* GenJyuuGothic Japanese font */}
                <link
                    rel="stylesheet"
                    type="text/css"
                    href="https://shogo82148.github.io/genjyuugothic-subsets/GenJyuuGothicL-P-Medium/GenJyuuGothicL-P-Medium.css"
                />
                <link
                    rel="stylesheet"
                    type="text/css"
                    href="https://shogo82148.github.io/genjyuugothic-subsets/GenJyuuGothicL-P-Bold/GenJyuuGothicL-P-Bold.css"
                />
            </head>
            <body className="flex flex-col min-h-screen">
                <Provider>{children}</Provider>
            </body>
        </html>
    );
}
