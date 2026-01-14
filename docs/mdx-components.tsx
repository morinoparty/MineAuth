import defaultComponents from "fumadocs-ui/mdx";
import type { MDXComponents } from "mdx/types";
import { Mermaid } from "./components/mdx/mermaind";

export function getMDXComponents(components?: MDXComponents): MDXComponents {
	return {
		...defaultComponents,
		Mermaid,
		...components,
	};
}
