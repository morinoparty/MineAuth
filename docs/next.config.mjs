import { createMDX } from "fumadocs-mdx/next";

const withMDX = createMDX();

/** @type {import('next').NextConfig} */
const config = {
    reactStrictMode: true,
    output: "export",
    trailingSlash: true,
    basePath: process.env.BASE_PATH || "",
    images: {
        unoptimized: true,
    },
    // Scalarパッケージのトランスパイル設定
    transpilePackages: [
        "@scalar/api-reference-react",
        "@scalar/api-reference",
        "@scalar/agent-chat",
    ],
};

export default withMDX(config);
