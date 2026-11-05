import { Fingerprint, KeyRound, type LucideIcon, Puzzle, ShieldCheck, UserRound } from "lucide-react";
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
  roles: ShieldCheck,
  plugin: Puzzle,
};

/** 画面に出さないスコープ */
const hiddenScopes = new Set(["email"]);

/** 要求されているスコープをチップで列挙する */
export const ScopeList = ({ scopes, messages }: ScopeListProps) => (
  <ul aria-label={messages.permissionsLabel} className={css({ display: "flex", flexWrap: "wrap", gap: "2" })}>
    {scopes
      .filter((scope) => !hiddenScopes.has(scope))
      .map((scope) => {
        const Icon = scopeIcons[scope] ?? KeyRound;
        return (
          // 説明が無いスコープでもスコープ名が分かるよう title に元の値を残す
          <li
            key={scope}
            title={scope}
            className={css({
              display: "inline-flex",
              alignItems: "center",
              gap: "1.5",
              px: "2.5",
              py: "1",
              borderRadius: "full",
              bg: "colorPalette.2",
              borderWidth: "1px",
              borderColor: "colorPalette.border.subtle",
              color: "colorPalette.12",
              textStyle: "sm",
            })}
          >
            <Icon aria-hidden size={14} />
            {describeScope(messages, scope)}
          </li>
        );
      })}
  </ul>
);
