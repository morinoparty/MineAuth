"use client";
import { cva } from "class-variance-authority";
import { Airplay, Moon, Sun } from "lucide-react";
import { type ComponentProps, useEffect, useState } from "react";
import { cn } from "../../lib/cn";

type Mode = "light" | "dark" | "system";

const itemVariants = cva("size-6.5 rounded-full p-1.5 text-fd-muted-foreground", {
    variants: {
        active: {
            true: "bg-fd-accent text-fd-accent-foreground",
            false: "text-fd-muted-foreground",
        },
    },
});

const modes: [Mode, typeof Sun][] = [
    ["light", Sun],
    ["dark", Moon],
    ["system", Airplay],
];

function getSystemTheme(): "light" | "dark" {
    if (typeof window === "undefined") return "light";
    return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function applyMode(mode: Mode) {
    const resolvedMode = mode === "system" ? getSystemTheme() : mode;
    document.documentElement.setAttribute("data-mode", resolvedMode);
    document.documentElement.classList.toggle("dark", resolvedMode === "dark");
    localStorage.setItem("mode", mode);
}

export function ThemeToggle({
    className,
    mode: toggleMode = "light-dark",
}: {
    className?: string;
    mode?: "light-dark" | "light-dark-system";
}) {
    const [mounted, setMounted] = useState(false);
    const [mode, setMode] = useState<Mode>("light");
    const [resolvedMode, setResolvedMode] = useState<"light" | "dark">("light");

    useEffect(() => {
        setMounted(true);
        const saved = localStorage.getItem("mode") as Mode | null;
        const initialMode = saved || "system";
        setMode(initialMode);
        setResolvedMode(initialMode === "system" ? getSystemTheme() : (initialMode as "light" | "dark"));

        // Listen for system theme changes
        const mediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
        const handleChange = () => {
            if (mode === "system") {
                setResolvedMode(getSystemTheme());
                applyMode("system");
            }
        };
        mediaQuery.addEventListener("change", handleChange);
        return () => mediaQuery.removeEventListener("change", handleChange);
    }, [mode]);

    const changeMode = (newMode: Mode) => {
        setMode(newMode);
        setResolvedMode(newMode === "system" ? getSystemTheme() : (newMode as "light" | "dark"));
        applyMode(newMode);
    };

    const container = cn("inline-flex items-center rounded-full border p-1", className);

    if (toggleMode === "light-dark") {
        const value = mounted ? resolvedMode : null;

        return (
            <button
                className={container}
                aria-label="Toggle Theme"
                onClick={() => changeMode(value === "light" ? "dark" : "light")}
                data-theme-toggle=""
            >
                {modes.map(([key, Icon]) => {
                    if (key === "system") return null;

                    return (
                        <Icon key={key} fill="currentColor" className={cn(itemVariants({ active: value === key }))} />
                    );
                })}
            </button>
        );
    }

    const value = mounted ? mode : null;

    return (
        <div className={container} data-theme-toggle="">
            {modes.map(([key, Icon]) => (
                <button
                    type="button"
                    key={key}
                    aria-label={key}
                    className={cn(itemVariants({ active: value === key }))}
                    onClick={() => changeMode(key)}
                >
                    <Icon className="size-full" fill="currentColor" />
                </button>
            ))}
        </div>
    );
}
