import type { I18nConfig } from "fumadocs-core/i18n";
import Link from "fumadocs-core/link";
import type { ComponentProps, ReactNode } from "react";
import type { LinkItemType } from "./link-item";

export interface NavOptions {
    enabled: boolean;
    component: ReactNode;

    title?: ReactNode | ((props: ComponentProps<"a">) => ReactNode);

    /**
     * Redirect url of title
     * @defaultValue '/'
     */
    url?: string;

    /**
     * Use transparent background
     *
     * @defaultValue none
     */
    transparentMode?: "always" | "top" | "none";

    children?: ReactNode;
}

export interface BaseLayoutProps {
    themeSwitch?: {
        enabled?: boolean;
        component?: ReactNode;
        mode?: "light-dark" | "light-dark-system";
    };

    paletteSwitch?: {
        enabled?: boolean;
        component?: ReactNode;
        mode?: "toggle" | "select";
    };

    searchToggle?: Partial<{
        enabled: boolean;
        components: Partial<{
            sm: ReactNode;
            lg: ReactNode;
        }>;
    }>;

    /**
     * I18n options
     *
     * @defaultValue false
     */
    i18n?: boolean | I18nConfig;

    /**
     * GitHub url
     */
    githubUrl?: string;

    /**
     * Modrinth url
     */
    modrinthUrl?: string;

    links?: LinkItemType[];
    /**
     * Replace or disable navbar
     */
    nav?: Partial<NavOptions>;

    children?: ReactNode;
}

/**
 * Get link items with shortcuts
 */
export function resolveLinkItems({
    links = [],
    githubUrl,
    modrinthUrl,
}: Pick<BaseLayoutProps, "links" | "githubUrl" | "modrinthUrl">): LinkItemType[] {
    const result = [...links];

    if (modrinthUrl)
        result.push({
            type: "icon",
            url: modrinthUrl,
            text: "Modrinth",
            label: "Modrinth",
            icon: (
                <svg role="img" viewBox="0 0 512 514" fill="currentColor" aria-label="Modrinth" className="size-4.5">
                    <path
                        fillRule="evenodd"
                        clipRule="evenodd"
                        d="M503.16 323.56C514.55 281.47 515.32 235.91 503.2 190.76C466.57 54.2299 326.04 -26.8001 189.33 9.77991C83.8101 38.0199 11.3899 128.07 0.689941 230.47H43.99C54.29 147.33 113.74 74.7298 199.75 51.7098C306.05 23.2598 415.13 80.6699 453.17 181.38L411.03 192.65C391.64 145.8 352.57 111.45 306.3 96.8198L298.56 140.66C335.09 154.13 364.72 184.5 375.56 224.91C391.36 283.8 361.94 344.14 308.56 369.17L320.09 412.16C390.25 383.21 432.4 310.3 422.43 235.14L464.41 223.91C468.91 252.62 467.35 281.16 460.55 308.07L503.16 323.56Z"
                    />
                    <path d="M321.99 504.22C185.27 540.8 44.7501 459.77 8.11011 323.24C3.84011 307.31 1.17 291.33 0 275.46H43.27C44.36 287.37 46.4699 299.35 49.6799 311.29C53.0399 323.8 57.45 335.75 62.79 347.07L101.38 323.92C98.1299 316.42 95.39 308.6 93.21 300.47C69.17 210.87 122.41 118.77 212.13 94.7601C229.13 90.2101 246.23 88.4401 262.93 89.1501L255.19 133C244.73 133.05 234.11 134.42 223.53 137.25C157.31 154.98 118.01 222.95 135.75 289.09C136.85 293.16 138.13 297.13 139.59 300.99L188.94 271.38L174.07 231.95L220.67 184.08L279.57 171.39L296.62 192.38L269.47 219.88L245.79 227.33L228.87 244.72L237.16 267.79C237.16 267.79 253.95 285.63 253.98 285.64L277.7 279.33L294.58 260.79L331.44 249.12L342.42 273.82L304.39 320.45L240.66 340.63L212.08 308.81L162.26 338.7C187.8 367.78 226.2 383.93 266.01 380.56L277.54 423.55C218.13 431.41 160.1 406.82 124.05 361.64L85.6399 384.68C136.25 451.17 223.84 484.11 309.61 461.16C371.35 444.64 419.4 402.56 445.42 349.38L488.06 364.88C457.17 431.16 398.22 483.82 321.99 504.22Z" />
                </svg>
            ),
            external: true,
        });

    if (githubUrl)
        result.push({
            type: "icon",
            url: githubUrl,
            text: "Github",
            label: "GitHub",
            icon: (
                <svg role="img" viewBox="0 0 24 24" fill="currentColor" aria-label="GitHub" className="size-4.5">
                    <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12" />
                </svg>
            ),
            external: true,
        });

    return result;
}

export function renderTitleNav({ title, url = "/" }: Partial<NavOptions>, props: ComponentProps<"a">) {
    if (typeof title === "function") return title({ href: url, ...props });
    return (
        <Link href={url} {...props}>
            {title}
        </Link>
    );
}

export type * from "./link-item";
