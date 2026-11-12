import { css } from "styled-system/css";
import logo from "../assets/logo.svg";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { CoverPanel } from "./CoverPanel";
import { LoginForm } from "./LoginForm";
import { ScopeList } from "./ScopeList";

interface AuthorizePageProps {
  model: AuthorizeModel;
  messages: Messages;
}

/**
 * 森のパーティの OAuth2 認可画面。
 * 左にログインフォーム、右にカバー画像を並べる（狭い画面ではカバーを出さない）
 */
export const AuthorizePage = ({ model, messages }: AuthorizePageProps) => (
  <div
    className={css({
      display: "grid",
      gridTemplateColumns: { base: "1fr", lg: "minmax(0, 1fr) minmax(0, 1fr)" },
      minHeight: "100dvh",
      bg: "bg.panel",
    })}
  >
    <main className={css({ display: "flex", alignItems: "center", justifyContent: "center", px: { base: "6", md: "12" }, py: { base: "10", md: "12" } })}>
      <div className={css({ width: "full", maxWidth: "md", display: "flex", flexDirection: "column", gap: "8" })}>
        <header className={css({ display: "flex", alignItems: "center", gap: "2.5" })}>
          <img src={logo} alt="" className={css({ height: "9", width: "auto" })} />
          <span className={css({ textStyle: "lg", fontWeight: "bold", color: "colorPalette.fg" })}>{model.applicationName}</span>
        </header>

        <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
          <h1 className={css({ textStyle: "3xl", fontWeight: "bold", color: "colorPalette.12" })}>{messages.signIn}</h1>
          <p className={css({ textStyle: "md", color: "fg.muted" })}>
            <span className={css({ fontWeight: "bold", color: "colorPalette.fg" })}>{model.clientName}</span> {messages.requestingAccess}
          </p>
          <ScopeList scopes={model.scopeList} messages={messages} />
        </div>

        <LoginForm model={model} messages={messages} />
      </div>
    </main>

    {/* カバーは装飾なので支援技術からは隠し、スクロールしても画面に留める */}
    <aside aria-hidden className={css({ display: { base: "none", lg: "block" }, position: "sticky", top: "0", height: "100dvh" })}>
      <CoverPanel />
    </aside>
  </div>
);
