import type { BaseLayoutProps } from "@/components/layout/shared";

export function baseOptions(): BaseLayoutProps {
    return {
        nav: {
            title: (
                <div className="flex items-center gap-2">
                    <span className="text-lg font-bold">MineAuth</span>
                </div>
            ),
            transparentMode: "top",
        },
        // ライトテーマのみのため、テーマ切り替えスイッチを非表示にする
        themeSwitch: {
            enabled: false,
        },
        githubUrl: "https://github.com/morinoparty/MineAuth",
        modrinthUrl: "https://modrinth.com/plugin/mineauth",
    };
}