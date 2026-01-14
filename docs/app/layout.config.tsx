import type {BaseLayoutProps} from 'fumadocs-ui/layouts/shared';

export const baseOptions: BaseLayoutProps = {
    nav: {
        title: 'MineAuth',
    },
    links: [
        {
            text: 'Docs',
            url: '/docs',
            active: 'nested-url',
        },
        {
            text: 'GitHub',
            url: 'https://github.com/morinoparty/MineAuth',
            external: true,
        },
        {
            text: 'Download',
            url: 'https://modrinth.com/plugin/MineAuth',
            external: true,
        },
    ],
};
