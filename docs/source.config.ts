import {
	remarkDirectiveAdmonition,
	remarkMdxMermaid,
} from "fumadocs-core/mdx-plugins";
import { defineConfig, defineDocs } from "fumadocs-mdx/config";
import remarkDirective from "remark-directive";

export const docs = defineDocs({
	dir: "content/docs",
	docs: {
		postprocess: {
			includeProcessedMarkdown: true,
		},
	},
});

export default defineConfig({
	mdxOptions: {
		remarkPlugins: [
			remarkMdxMermaid,
			remarkDirective,
			remarkDirectiveAdmonition,
		],
	},
});
