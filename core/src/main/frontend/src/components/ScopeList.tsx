import { Check } from "lucide-react";
import { css } from "styled-system/css";
import { describeScope, type Messages } from "../i18n";

interface ScopeListProps {
  scopes: string[];
  messages: Messages;
}

/** 要求されているスコープを説明文つきで列挙する */
export const ScopeList = ({ scopes, messages }: ScopeListProps) => (
  <ul className={css({ display: "flex", flexDirection: "column", gap: "2" })}>
    {scopes.map((scope) => (
      <li key={scope} className={css({ display: "flex", alignItems: "center", gap: "3", color: "fg.muted" })}>
        <Check aria-hidden size={18} className={css({ flexShrink: "0", color: "colorPalette.fg" })} />
        {/* 説明が無いスコープでもスコープ名が分かるよう title に元の値を残す */}
        <span title={scope}>{describeScope(messages, scope)}</span>
      </li>
    ))}
  </ul>
);
