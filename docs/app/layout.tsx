import './global.css';
import { RootProvider } from 'fumadocs-ui/provider/next';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';

export const metadata: Metadata = {
    title: {
        template: '%s | MineAuth',
        default: 'MineAuth Documentation',
    },
    description: 'OAuth2 and OpenID Connect authentication plugin for Minecraft',
};

export default function RootLayout({ children }: { children: ReactNode }) {
    return (
        <html lang="ja" suppressHydrationWarning>
            <body className="flex flex-col min-h-screen">
                <RootProvider>{children}</RootProvider>
            </body>
        </html>
    );
}
