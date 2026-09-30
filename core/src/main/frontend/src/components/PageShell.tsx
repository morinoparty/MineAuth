import type { ReactNode } from "react";
import { css } from "styled-system/css";

interface PageShellProps {
  children: ReactNode;
}

/** 画面中央に大きなカードを置く外枠。背景には上部からブランドカラーを淡く敷く */
export const PageShell = ({ children }: PageShellProps) => (
  <main
    className={css({
      minHeight: "100vh",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      px: { base: "4", md: "8" },
      py: { base: "6", md: "12" },
      backgroundImage:
        "radial-gradient(ellipse 80% 60% at 50% -10%, var(--ma-colors-color-palette-4), transparent 70%)",
    })}
  >
    <div
      className={css({
        width: "full",
        maxWidth: "6xl",
        bg: "bg.panel",
        borderWidth: "1px",
        borderColor: "border.subtle",
        borderRadius: "panel",
        boxShadow: "raised",
        overflow: "hidden",
      })}
    >
      {children}
    </div>
  </main>
);
