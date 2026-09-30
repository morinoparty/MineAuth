import { Eye, EyeOff } from "lucide-react";
import { useState } from "react";
import { css, cx } from "styled-system/css";
import { Field } from "../chlorophyll";
import type { Messages } from "../i18n";
import { largeInput, largeLabel } from "./fieldSize";

interface PasswordFieldProps {
  messages: Messages;
}

/** 表示・非表示を切り替えられるパスワード入力欄 */
export const PasswordField = ({ messages }: PasswordFieldProps) => {
  const [visible, setVisible] = useState(false);

  return (
    <Field.Root required>
      <Field.Label className={largeLabel}>{messages.passwordLabel}</Field.Label>
      <div className={css({ position: "relative" })}>
        <Field.Input
          name="password"
          type={visible ? "text" : "password"}
          autoComplete="current-password"
          placeholder="••••••••"
          className={cx(largeInput, css({ pr: "14" }))}
        />
        <button
          type="button"
          onClick={() => setVisible((v) => !v)}
          aria-label={visible ? messages.hidePassword : messages.showPassword}
          aria-pressed={visible}
          className={css({
            position: "absolute",
            insetEnd: "3",
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
          {visible ? <EyeOff aria-hidden size={22} /> : <Eye aria-hidden size={22} />}
        </button>
      </div>
    </Field.Root>
  );
};
