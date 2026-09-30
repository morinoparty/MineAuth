import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { css, cx } from "styled-system/css";
import type { Messages } from "../i18n";
import { inputStyle, labelStyle } from "./fieldStyles";

interface PasswordFieldProps {
  messages: Messages;
}

/** 表示・非表示を切り替えられるパスワード入力欄 */
export const PasswordField = ({ messages }: PasswordFieldProps) => {
  const [visible, setVisible] = useState(false);

  return (
    <div>
      <label htmlFor="password" className={labelStyle}>
        {messages.passwordLabel}
      </label>
      <div className={css({ position: "relative" })}>
        <input
          id="password"
          name="password"
          type={visible ? "text" : "password"}
          autoComplete="current-password"
          placeholder="••••••••"
          required
          className={cx(inputStyle, css({ pr: "10" }))}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? messages.hidePassword : messages.showPassword}
          aria-pressed={visible}
          className={css({
            position: "absolute",
            insetEnd: "2",
            top: "50%",
            transform: "translateY(-50%)",
            display: "flex",
            p: "1",
            borderRadius: "l1",
            color: "colorPalette.fg",
            cursor: "pointer",
            _focusVisible: {
              outlineStyle: "solid",
              outlineWidth: "focus.ring",
              outlineColor: "colorPalette.focus.ring",
            },
          })}
        >
          {visible ? <EyeOff aria-hidden size={18} /> : <Eye aria-hidden size={18} />}
        </button>
      </div>
    </div>
  );
};
