import "./index.css";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { AuthorizePage } from "./components/AuthorizePage";
import { LoadError } from "./components/LoadError";
import { detectLocale, getMessages } from "./i18n";
import { readModel } from "./model";

const container = document.getElementById("root");
if (!container) throw new Error("#root element is missing from index.html");

const locale = detectLocale();
const messages = getMessages(locale);
// <html lang> を表示言語に合わせ、読み上げやフォント選択を正しくする
document.documentElement.lang = locale;

const model = readModel();

createRoot(container).render(
  <StrictMode>
    {model ? <AuthorizePage model={model} messages={messages} /> : <LoadError messages={messages} />}
  </StrictMode>,
);
