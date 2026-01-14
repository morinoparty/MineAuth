import {defineConfig, defineDocs} from 'fumadocs-mdx/config';
import { remarkMdxMermaid } from 'fumadocs-core/mdx-plugins';
import remarkDirective from 'remark-directive';
import { remarkDirectiveAdmonition } from 'fumadocs-core/mdx-plugins';

export const docs = defineDocs({
    dir: 'content/docs',
});

export default defineConfig({
    mdxOptions: {
        remarkPlugins: [remarkMdxMermaid, remarkDirective, remarkDirectiveAdmonition],
    },
});