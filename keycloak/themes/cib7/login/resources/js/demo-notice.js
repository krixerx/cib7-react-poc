/*
 * CIB seven login theme — public-demo email notice.
 *
 * The companylab.ai instance is a PUBLIC demo: Keycloak's "verify email" step is
 * on (realm-export.json `verifyEmail: true`) and SMTP is wired to a shared
 * Mailpit test inbox, not a real mail server. So a registrant's verification
 * email — and every process notification — lands in one inbox that anyone can
 * open. This script progressively enhances two pages with a notice:
 *
 *   1. the registration form, right under the e-mail field (pre-warning), and
 *   2. the post-registration "verify your email" page (where they're stuck),
 *      with a direct link to the inbox.
 *
 * It is deliberately a theme resource (loaded via theme.properties `scripts=`)
 * rather than an .ftl override: the cib7 theme intentionally does NOT fork
 * Keycloak's version-specific form FreeMarker (see theme.properties). No JS =>
 * no notice, registration still works.
 *
 * Other deployments: change MAILPIT_PUBLIC_URL below to your inbox URL.
 */
(function () {
  "use strict";

  // Public Mailpit inbox. On localhost we assume the opt-in `mail` compose
  // profile (docker compose --profile mail up -d mailpit-ui) on :8025.
  var MAILPIT_PUBLIC_URL = "https://companylab.ai/mailpit/";
  var host = window.location.hostname;
  var isLocal = host === "localhost" || host === "127.0.0.1";
  var mailUrl = isLocal ? "http://localhost:8025/" : MAILPIT_PUBLIC_URL;

  // EN/AR copy keyed off the page locale (template.ftl sets <html lang>). Kept
  // in sync with the SPA's i18n tone; Arabic is formal MSA.
  var lang = (document.documentElement.getAttribute("lang") || "en").toLowerCase();
  var ar = lang.indexOf("ar") === 0;
  var T = ar
    ? {
        title: "بيئة تجريبية",
        registerBody:
          "هذا عرض تجريبي عام. تُرسَل رسائل التحقق والإشعارات إلى صندوق بريد تجريبي مشترك يمكن لأي شخص الاطلاع عليه — يُرجى عدم إدخال بيانات شخصية حقيقية.",
        verifyBody:
          "هذا عرض تجريبي عام. أُرسِلت رسالة التحقق إلى صندوق بريد تجريبي مشترك يمكن لأي شخص الاطلاع عليه؛ يُرجى عدم استخدام بيانات شخصية حقيقية.",
        openInbox: "افتح صندوق البريد التجريبي"
      }
    : {
        title: "Demo environment",
        registerBody:
          "This is a public demo. Verification and notification emails go to a shared test inbox that anyone can read — please don’t enter real personal data.",
        verifyBody:
          "This is a public demo. The verification email was delivered to a shared test inbox that anyone can read — please don’t use real personal data.",
        openInbox: "Open the demo inbox"
      };

  function buildNotice(body, withLink) {
    var box = document.createElement("div");
    box.className = "cib7-demo-notice";
    box.setAttribute("role", "note");

    var strong = document.createElement("strong");
    strong.className = "cib7-demo-notice__title";
    strong.textContent = T.title;
    box.appendChild(strong);

    var p = document.createElement("p");
    p.className = "cib7-demo-notice__body";
    p.textContent = body;
    box.appendChild(p);

    if (withLink) {
      var a = document.createElement("a");
      a.className = "cib7-demo-notice__link";
      a.href = mailUrl;
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      a.textContent = T.openInbox + " →";
      box.appendChild(a);
    }
    return box;
  }

  function enhanceRegister() {
    var form = document.getElementById("kc-register-form");
    if (!form) return false;
    var email =
      form.querySelector("#email") ||
      form.querySelector('input[name="email"]') ||
      form.querySelector('input[type="email"]');
    if (!email) return false;

    // Drop the notice right after the e-mail field's form group (fall back to
    // the input's immediate wrapper if the PatternFly group class shifts).
    var group =
      email.closest('[class*="form__group"]') ||
      email.closest(".form-group") ||
      email.parentElement;
    group.insertAdjacentElement("afterend", buildNotice(T.registerBody, false));
    return true;
  }

  function enhanceVerifyEmail() {
    // The verify-email page has no input fields, shows the user's address in a
    // ".instruction" paragraph, and links back to login-actions to resend.
    // Match on all three to avoid firing on other text-only pages.
    var content = document.getElementById("kc-content-wrapper") || document.body;
    var hasInputs = content.querySelector(
      'input[type="text"], input[type="password"], input[type="email"]'
    );
    if (hasInputs) return false;
    var instruction = content.querySelector(".instruction");
    var resendLink = content.querySelector('a[href*="login-actions"]');
    if (!instruction || !resendLink || instruction.textContent.indexOf("@") === -1) {
      return false;
    }
    instruction.insertAdjacentElement(
      "beforebegin",
      buildNotice(T.verifyBody, true)
    );
    return true;
  }

  function run() {
    if (enhanceRegister()) return;
    enhanceVerifyEmail();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", run);
  } else {
    run();
  }
})();
