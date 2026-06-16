"use client";
import { RootProvider } from "fumadocs-ui/provider/next";
import type { ReactNode } from "react";
import SearchDialog from "@/components/search";

export function Provider({ children }: { children: ReactNode }) {
    return (
        <RootProvider
            // ライトテーマのみを強制し、ダークモードへの切り替えを無効化する
            theme={{ forcedTheme: "light" }}
            search={{
                SearchDialog,
            }}
        >
            {children}
        </RootProvider>
    );
}
