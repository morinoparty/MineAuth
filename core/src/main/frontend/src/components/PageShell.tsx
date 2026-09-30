import type { ReactNode } from "react";
import { css } from "styled-system/css";
import { Separator } from "../chlorophyll";

interface PageShellProps {
  logoUrl: string;
  applicationName: string;
  children: ReactNode;
}

/** 画面中央のカードと、ロゴ・アプリケーション名のヘッダー */
export const PageShell = ({ logoUrl, applicationName, children }: PageShellProps) => (
  <main
    className={css({
      minHeight: "100vh",
      display: "flex",
      alignItems: "center",
      justifyContent: "center",
      px: "4",
      py: "8",
    })}
  >
    <div
      className={css({
        width: "full",
        maxWidth: "4xl",
        bg: "bg.panel",
        borderWidth: "1px",
        borderColor: "border.subtle",
        borderRadius: "panel",
        boxShadow: "raised",
        overflow: "hidden",
      })}
    >
      <header className={css({ display: "flex", alignItems: "center", gap: "2", p: "4" })}>
        <img src={logoUrl} alt="" className={css({ width: "8", height: "8" })} />
        <span className={css({ textStyle: "xl", fontWeight: "semibold", color: "colorPalette.fg" })}>{applicationName}</span>
      </header>
      <Separator />
      {children}
    </div>
  </main>
);
