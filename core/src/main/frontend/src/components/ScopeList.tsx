import { Fingerprint, KeyRound, type LucideIcon, Mail, ShieldCheck, UserRound } from "lucide-react";
import { css } from "styled-system/css";
import { describeScope, type Messages } from "../i18n";

interface ScopeListProps {
  scopes: string[];
  messages: Messages;
}

/** スコープごとのアイコン。未知のスコープは鍵アイコンにする */
const scopeIcons: Record<string, LucideIcon> = {
  openid: Fingerprint,
  profile: UserRound,
  email: Mail,
  roles: ShieldCheck,
};

/** 要求されているスコープをアイコンと説明文つきで列挙する */
export const ScopeList = ({ scopes, messages }: ScopeListProps) => (
  <ul className={css({ display: "flex", flexDirection: "column", gap: "4" })}>
    {scopes.map((scope) => {
      const Icon = scopeIcons[scope] ?? KeyRound;
      return (
        <li key={scope} className={css({ display: "flex", alignItems: "center", gap: "4", textStyle: "lg" })}>
          <span
            className={css({
              flexShrink: "0",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              width: "12",
              height: "12",
              borderRadius: "l2",
              bg: "bg.panel",
              borderWidth: "1px",
              borderColor: "colorPalette.border.subtle",
              color: "colorPalette.fg",
            })}
          >
            <Icon aria-hidden size={24} />
          </span>
          {/* 説明が無いスコープでもスコープ名が分かるよう title に元の値を残す */}
          <span title={scope} className={css({ color: "colorPalette.12" })}>
            {describeScope(messages, scope)}
          </span>
        </li>
      );
    })}
  </ul>
);
