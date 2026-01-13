import type * as Preset from "@docusaurus/preset-classic";
import type { Config } from "@docusaurus/types";
import { themes as prismThemes } from "prism-react-renderer";

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
    title: "MineAuth Documentation",
    favicon: "img/favicon.ico",
    staticDirectories: ["static"],
    trailingSlash: true,

    // Set the production url of your site here
    url: "https://mineauth.plugin.morino.party",
    // Set the /<baseUrl>/ pathname under which your site is served
    // For GitHub pages deployment, it is often '/<projectName>/'
    baseUrl: "/",

    // GitHub pages deployment config.
    // If you aren't using GitHub pages, you don't need these.
    organizationName: "morinoparty", // Usually your GitHub org/user name.
    projectName: "MineAuth", // Usually your repo name.

    onBrokenLinks: "throw",

    markdown: {
        hooks: {
            onBrokenMarkdownLinks: "warn"
        },
        mermaid: true,
    },

    // Even if you don't use internationalization, you can use this field to set
    // useful metadata like html lang. For example, if your site is Chinese, you
    // may want to replace "en" with "zh-Hans".
    i18n: {
        defaultLocale: "en",
        locales: ["en"],
    },
    themes: ["@docusaurus/theme-mermaid"],

    presets: [
        [
            "classic",
            {
                docs: {
                    sidebarPath: "./sidebars.ts",
                    routeBasePath: "",
                    // Please change this to your repo.
                    // Remove this to remove the "edit this page" links.
                    editUrl:
                        "https://github.com/morinoparty/mineauth/tree/master/docs/",
                },
                theme: {
                    customCss: "./src/css/custom.css",
                },
            } satisfies Preset.Options,
        ],
    ],
    plugins: [
        ["./src/plugins/llms-txt.ts", {}],
        ["./src/plugins/tailwind-config.js", {}],
        [require.resolve("@easyops-cn/docusaurus-search-local"),
            {
                // Options here
                indexDocs: true,
                language: ["en"],
                docsRouteBasePath: "/",
            },
        ],
    ],
    themeConfig: {
        // Replace with your project's social card
        image: "img/docusaurus-social-card.jpg",
        navbar: {
            title: "MineAuth",
            logo: {
                alt: "MineAuth Logo",
                src: "img/logo.svg",
            },
            items: [
                {
                    href: "https://github.com/morinoparty/mineauth",
                    label: "GitHub",
                    position: "right",
                },
                {
                    label: "OpenAPI",
                    position: "left",
                    to: "/openapi/",
                    target: "_blank",
                },
                {
                    label: "Dokka",
                    position: "left",
                    to: "/dokka/",
                    target: "_blank",
                },
                {
                    href: "https://modrinth.com/plugin/MineAuth",
                    label: "Download",
                    position: "right",
                },
                {
                    type: "localeDropdown",
                    position: "right",
                },
            ],
        },
        footer: {
            style: "dark",
            links: [
                {
                    title: "Documentation",
                    items: [
                        {
                            label: "Introduction",
                            to: "/intro",
                        },
                    ],
                },
                {
                    title: "Community",
                    items: [
                        {
                            label: "Homepage",
                            href: "https://morino.party",
                        },
                        {
                            label: "Discord",
                            href: "https://discord.com/invite/9HdanPM",
                        },
                        {
                            label: "X",
                            href: "https://x.com/morinoparty",
                        },
                    ],
                },
                {
                    title: "Other",
                    items: [
                        {
                            label: "GitHub",
                            href: "https://github.com/morinoparty/mineauth",
                        },
                    ],
                },
            ],
            copyright: `No right reserved. This docs under CC0. Built with Docusaurus.`,
        },
        prism: {
            additionalLanguages: [
                "java",
                "groovy",
                "diff",
                "toml",
                "yaml",
                "json",
                "json5",
                "kotlin",
            ],
            theme: prismThemes.github,
            darkTheme: prismThemes.dracula,
        },
    } satisfies Preset.ThemeConfig,
    future: {
        v4: true,
        experimental_faster: true
    },
};

export default config;
