import i18n from "i18next";
import { initReactI18next } from "react-i18next";
import en from "./locales/en.json";
import de from "./locales/de.json";

// Guarded: this runs at module load, before any error boundary exists, and a browser with site
// storage blocked throws on the bare access - which used to be a white page instead of a login form.
function readSavedLang(): string {
  try {
    return window.localStorage.getItem("loom-ui-language") ?? "en";
  } catch {
    return "en";
  }
}

const savedLang = readSavedLang();

i18n
  .use(initReactI18next)
  .init({
    resources: {
      en: { translation: en },
      de: { translation: de },
    },
    lng: savedLang,
    fallbackLng: "en",
    interpolation: {
      // React already escapes values
      escapeValue: false,
    },
  });

export default i18n;
