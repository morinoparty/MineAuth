import { css } from "styled-system/css";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { ScopeList } from "./ScopeList";

interface ClientInfoProps {
  model: AuthorizeModel;
  messages: Messages;
}

/** サーバーのロゴ・認可を要求しているクライアント・要求スコープ・発行者の表示 */
export const ClientInfo = ({ model, messages }: ClientInfoProps) => (
  <div className={css({ display: "flex", flexDirection: "column", gap: "10", height: "full" })}>
    <header className={css({ display: "flex", alignItems: "center", gap: "3" })}>
      <img src={model.logoUrl} alt="" className={css({ width: "14", height: "14", borderRadius: "l3" })} />
      <span className={css({ textStyle: "3xl", fontWeight: "bold", color: "colorPalette.fg" })}>{model.applicationName}</span>
    </header>

    <div className={css({ display: "flex", flexDirection: "column", gap: "3" })}>
      <h1 className={css({ textStyle: { base: "3xl", md: "4xl" }, fontWeight: "bold", color: "colorPalette.12" })}>
        {messages.authorizeApp}
      </h1>
      <p className={css({ textStyle: "xl", color: "fg.muted" })}>
        <span className={css({ fontWeight: "bold", color: "colorPalette.fg" })}>{model.clientName}</span> {messages.requestingAccess}
      </p>
    </div>

    <div className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
      <p className={css({ textStyle: "lg", fontWeight: "bold", color: "colorPalette.12" })}>{messages.permissionsLabel}</p>
      <ScopeList scopes={model.scopeList} messages={messages} />
    </div>

    {/* 発行者は確認用の補足情報なので、パネルの下端に小さく置く */}
    <dl className={css({ mt: "auto", display: "flex", flexDirection: "column", gap: "1", textStyle: "md" })}>
      <dt className={css({ color: "fg.muted" })}>{messages.issuerLabel}</dt>
      <dd className={css({ fontFamily: "mono", wordBreak: "break-all", color: "colorPalette.fg" })}>{model.issuer}</dd>
    </dl>
  </div>
);
