import { css } from "styled-system/css";
import type { Messages } from "../i18n";
import type { AuthorizeModel } from "../model";
import { ScopeList } from "./ScopeList";

interface ClientInfoProps {
  model: AuthorizeModel;
  messages: Messages;
}

/** 認可を要求しているクライアント・発行者・要求スコープの表示 */
export const ClientInfo = ({ model, messages }: ClientInfoProps) => (
  <div className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
    <h1 className={css({ textStyle: "xl", fontWeight: "bold", color: "colorPalette.fg" })}>{messages.authorizeApp}</h1>
    <p>
      <span className={css({ fontWeight: "semibold", color: "colorPalette.fg" })}>"{model.clientName}"</span>{" "}
      {messages.requestingAccess}
    </p>
    <dl
      className={css({
        display: "flex",
        justifyContent: "space-between",
        alignItems: "start",
        gap: "2",
        bg: "bg.subtle",
        borderRadius: "l2",
        p: "4",
      })}
    >
      <dt className={css({ flexShrink: "0", fontWeight: "medium", color: "fg.muted" })}>{messages.issuerLabel}</dt>
      <dd className={css({ wordBreak: "break-all", textAlign: "end", color: "colorPalette.fg" })}>{model.issuer}</dd>
    </dl>
    <div>
      <p className={css({ fontWeight: "medium", mb: "3" })}>{messages.permissionsLabel}</p>
      <ScopeList scopes={model.scopeList} messages={messages} />
    </div>
  </div>
);
