<#--
  CIB seven login layout — a verbatim copy of the stock keycloak/base
  login/template.ftl with additions that mirror the SPA shell
  (frontend/src/App.tsx, components/OfficialBanner.tsx): the service pack's
  brand (cib7-pack-brand), the official-portal banner and mesh backdrop at the
  top of <body>, and the `#kc-header` brand bar. Everything else is
  byte-for-byte the base macro so the FreeMarker contract (nested
  "header"/"form"/"info"/... sections, locale dropdown, alerts, footer) keeps
  working across inherited pages.

  If you bump the Keycloak image, re-diff against the new base template.ftl and
  re-apply the blocks marked "cib7-".
-->
<#import "footer.ftl" as loginFooter>
<#--
  cib7-pack-brand: the service pack's branding, as the SPA reads it
  (frontend/src/pack/brand.ts): compose mounts the pack's branding/ folder at
  login/resources/pack. Read as JSON at render time, so a rebrand needs no
  theme change. Every value is checked like the SPA does it (hex colours,
  family names, bare image file names, listed token names); anything else, or a
  missing file, keeps the core default. Texts are escaped by the template's
  HTML output format.
-->
<#function packJson path>
    <#attempt>
        <#local raw><#include "resources/pack/" + path parse=false></#local>
        <#return raw?markup_string?eval_json>
    <#recover>
        <#return {}>
    </#attempt>
</#function>
<#assign packTokens = packJson("tokens.json")>
<#-- Like the SPA's parseTokens: one value or key outside brand format v1 refuses the whole file,
     so the login pages never look half rebranded next to the portal. -->
<#function tokensValid t>
    <#if !t?has_content><#return true></#if>
    <#if (t.version!0) != 1><#return false></#if>
    <#list t?keys as k><#if !["$comment", "version", "light", "dark", "fonts"]?seq_contains(k)><#return false></#if></#list>
    <#list ["light", "dark"] as scheme>
        <#local colors = t[scheme]!{}>
        <#if !colors?is_hash><#return false></#if>
        <#list colors?keys as name>
            <#local v = colors[name]>
            <#if !brandColorTokens?seq_contains(name) || !v?is_string || !v?matches("^#[0-9a-fA-F]{6}$")><#return false></#if>
        </#list>
    </#list>
    <#local fonts = t.fonts!{}>
    <#if !fonts?is_hash><#return false></#if>
    <#list fonts?keys as name>
        <#local v = fonts[name]>
        <#if !["display", "body"]?seq_contains(name) || !v?is_string || !v?matches("^[A-Za-z0-9][A-Za-z0-9 ]{0,39}$")><#return false></#if>
    </#list>
    <#return true>
</#function>
<#assign packBrand = packJson("brand.json")>
<#assign packTexts = packJson("locales/" + lang + "/brand.json")>
<#if !packTexts?has_content><#assign packTexts = packJson("locales/en/brand.json")></#if>
<#assign brandColorTokens = ["primary", "primary-hover", "primary-ink", "primary-soft", "primary-soft-border",
    "mesh-1", "mesh-2", "mesh-3", "mesh-4", "banner-bg", "banner-fg", "banner-strong"]>
<#if !tokensValid(packTokens)><#assign packTokens = {}></#if>
<#function brandText key fallback>
    <#local v = packTexts[key]!"">
    <#return (v?is_string && v?has_content)?then(v, fallback)>
</#function>
<#function brandImage key>
    <#local v = key!"">
    <#return (v?is_string && v?matches("^[a-z0-9][a-z0-9-]{0,62}\\.(svg|png|webp)$"))?then(v, "")>
</#function>
<#function brandVars scheme>
    <#local out = "">
    <#local colors = (packTokens[scheme]!{})>
    <#if colors?is_hash>
        <#list brandColorTokens as name>
            <#local v = colors[name]!"">
            <#if v?is_string && v?matches("^#[0-9a-fA-F]{6}$")><#local out = out + "--" + name + ": " + v + "; "></#if>
        </#list>
    </#if>
    <#return out>
</#function>
<#assign brandName = brandText("name", msg("cib7BrandName"))>
<#assign brandSub = brandText("sub", msg("cib7BrandSub"))>
<#assign brandPortal = brandText("portal", msg("cib7Official"))>
<#assign brandLogo = brandImage((packBrand.logo.light)!"")>
<#assign brandLogoDark = brandImage((packBrand.logo.dark)!"")>
<#assign brandFavicon = brandImage(packBrand.favicon!"")>
<#macro registrationLayout bodyClass="" displayInfo=false displayMessage=true displayRequiredFields=false>
<!DOCTYPE html>
<html class="${properties.kcHtmlClass!}" lang="${lang}"<#if realm.internationalizationEnabled> dir="${(locale.rtl)?then('rtl','ltr')}"</#if>>

<head>
    <meta charset="utf-8">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="robots" content="noindex, nofollow">

    <#if properties.meta?has_content>
        <#list properties.meta?split(' ') as meta>
            <meta name="${meta?split('==')[0]}" content="${meta?split('==')[1]}"/>
        </#list>
    </#if>
    <title>${msg("loginTitle", brandName)}</title>
    <#if brandFavicon?has_content>
    <link rel="icon" href="${url.resourcesPath}/pack/${brandFavicon}" />
    <#else>
    <link rel="icon" href="${url.resourcesPath}/img/favicon.ico" />
    </#if>
    <#-- Same Google Fonts the SPA loads (frontend/index.html). -->
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link href="https://fonts.googleapis.com/css2?family=Sora:wght@500;600;700&family=Instrument+Sans:wght@400;500;600;700&family=Noto+Sans+Arabic:wght@400;500;600;700&display=swap" rel="stylesheet" />
    <#if properties.stylesCommon?has_content>
        <#list properties.stylesCommon?split(' ') as style>
            <link href="${url.resourcesCommonPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#if properties.styles?has_content>
        <#list properties.styles?split(' ') as style>
            <link href="${url.resourcesPath}/${style}" rel="stylesheet" />
        </#list>
    </#if>
    <#-- cib7-pack-brand: the pack's colour and font tokens over css/cib7.css. -->
    <#assign lightVars = brandVars("light")>
    <#assign darkVars = brandVars("dark")>
    <#assign fontVars = "">
    <#list ["display", "body"] as font>
        <#assign f = ((packTokens.fonts)!{})[font]!"">
        <#if f?is_string && f?matches("^[A-Za-z0-9][A-Za-z0-9 ]{0,39}$")>
            <#assign fontVars = fontVars + "--font-" + font + ": '" + f + "', 'Segoe UI', system-ui, sans-serif; ">
        </#if>
    </#list>
    <#if lightVars?has_content || darkVars?has_content || fontVars?has_content>
    <#-- no_esc: inside <style> HTML escaping would break the CSS. Safe because every
         value passed a strict pattern above (hex, letters/digits/spaces, listed names). -->
    <style>:root { ${lightVars?no_esc}${fontVars?no_esc} } @media (prefers-color-scheme: dark) { :root { ${darkVars?no_esc} } }</style>
    </#if>
    <#if properties.scripts?has_content>
        <#list properties.scripts?split(' ') as script>
            <script src="${url.resourcesPath}/${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="importmap">
        {
            "imports": {
                "rfc4648": "${url.resourcesCommonPath}/vendor/rfc4648/rfc4648.js"
            }
        }
    </script>
    <script src="${url.resourcesPath}/js/menu-button-links.js" type="module"></script>
    <#if scripts??>
        <#list scripts as script>
            <script src="${script}" type="text/javascript"></script>
        </#list>
    </#if>
    <script type="module">
        import { startSessionPolling } from "${url.resourcesPath}/js/authChecker.js";

        startSessionPolling(
          "${url.ssoLoginInOtherTabsUrl?no_esc}"
        );
    </script>
    <#if authenticationSession??>
        <script type="module">
            import { checkAuthSession } from "${url.resourcesPath}/js/authChecker.js";

            checkAuthSession(
                "${authenticationSession.authSessionIdHash}"
            );
        </script>
    </#if>
</head>

<body class="${properties.kcBodyClass!}">
<#-- cib7-banner: official-portal strip + demo tag, as OfficialBanner.tsx. -->
<div class="cib7-official">
    <div class="cib7-official-row">
        <svg class="cib7-icon" width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor"
             stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
            <path d="m9 12 2 2 4-4" />
        </svg>
        <span>${brandPortal}</span>
        <span class="cib7-official-demo">
            <span class="cib7-official-tag">${msg("cib7DemoTag")}</span>
            <span class="cib7-official-demo-text">${msg("cib7DemoWarning")}</span>
        </span>
    </div>
</div>
<#-- cib7-mesh: the SPA's drifting colour backdrop (shell.css .app-mesh). -->
<div class="cib7-mesh" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
<div class="${properties.kcLoginClass!}">
    <#-- cib7-brand: glass brand bar with the pack's logo (or the core Landmark mark), as the SPA header. -->
    <div id="kc-header" class="${properties.kcHeaderClass!}">
        <div id="kc-header-wrapper" class="${properties.kcHeaderWrapperClass!}">
            <#if brandLogo?has_content>
            <picture class="cib7-brand-logo" aria-hidden="true">
                <#if brandLogoDark?has_content><source srcset="${url.resourcesPath}/pack/${brandLogoDark}" media="(prefers-color-scheme: dark)"></#if>
                <img src="${url.resourcesPath}/pack/${brandLogo}" alt="">
            </picture>
            <#else>
            <span class="cib7-logo" aria-hidden="true">
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                     stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M10 18v-7" />
                    <path d="M11.119 2.205a2 2 0 0 1 1.762 0l7.84 3.846A.5.5 0 0 1 20.5 7h-17a.5.5 0 0 1-.22-.949z" />
                    <path d="M14 18v-7" />
                    <path d="M18 18v-7" />
                    <path d="M3 22h18" />
                    <path d="M6 18v-7" />
                </svg>
            </span>
            </#if>
            <span class="cib7-brand-text">
                <span class="cib7-brand-name">${brandName}</span>
                <span class="cib7-brand-sub">${brandSub}</span>
            </span>
        </div>
    </div>
    <div class="${properties.kcFormCardClass!}">
        <header class="${properties.kcFormHeaderClass!}">
            <#if realm.internationalizationEnabled  && locale.supported?size gt 1>
                <div class="${properties.kcLocaleMainClass!}" id="kc-locale">
                    <div id="kc-locale-wrapper" class="${properties.kcLocaleWrapperClass!}">
                        <div id="kc-locale-dropdown" class="menu-button-links ${properties.kcLocaleDropDownClass!}">
                            <button tabindex="1" id="kc-current-locale-link" aria-label="${msg("languages")}" aria-haspopup="true" aria-expanded="false" aria-controls="language-switch1">${locale.current}</button>
                            <ul role="menu" tabindex="-1" aria-labelledby="kc-current-locale-link" aria-activedescendant="" id="language-switch1" class="${properties.kcLocaleListClass!}">
                                <#assign i = 1>
                                <#list locale.supported as l>
                                    <li class="${properties.kcLocaleListItemClass!}" role="none">
                                        <a role="menuitem" id="language-${i}" class="${properties.kcLocaleItemClass!}" href="${l.url}">${l.label}</a>
                                    </li>
                                    <#assign i++>
                                </#list>
                            </ul>
                        </div>
                    </div>
                </div>
            </#if>
        <#if !(auth?has_content && auth.showUsername() && !auth.showResetCredentials())>
            <#if displayRequiredFields>
                <div class="${properties.kcContentWrapperClass!}">
                    <div class="${properties.kcLabelWrapperClass!} subtitle">
                        <span class="subtitle"><span class="required">*</span> ${msg("requiredFields")}</span>
                    </div>
                    <div class="col-md-10">
                        <h1 id="kc-page-title"><#nested "header"></h1>
                    </div>
                </div>
            <#else>
                <h1 id="kc-page-title"><#nested "header"></h1>
            </#if>
        <#else>
            <#if displayRequiredFields>
                <div class="${properties.kcContentWrapperClass!}">
                    <div class="${properties.kcLabelWrapperClass!} subtitle">
                        <span class="subtitle"><span class="required">*</span> ${msg("requiredFields")}</span>
                    </div>
                    <div class="col-md-10">
                        <#nested "show-username">
                        <div id="kc-username" class="${properties.kcFormGroupClass!}">
                            <label id="kc-attempted-username">${auth.attemptedUsername}</label>
                            <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}">
                                <div class="kc-login-tooltip">
                                    <i class="${properties.kcResetFlowIcon!}"></i>
                                    <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
                                </div>
                            </a>
                        </div>
                    </div>
                </div>
            <#else>
                <#nested "show-username">
                <div id="kc-username" class="${properties.kcFormGroupClass!}">
                    <label id="kc-attempted-username">${auth.attemptedUsername}</label>
                    <a id="reset-login" href="${url.loginRestartFlowUrl}" aria-label="${msg("restartLoginTooltip")}">
                        <div class="kc-login-tooltip">
                            <i class="${properties.kcResetFlowIcon!}"></i>
                            <span class="kc-tooltip-text">${msg("restartLoginTooltip")}</span>
                        </div>
                    </a>
                </div>
            </#if>
        </#if>
      </header>
      <div id="kc-content">
        <div id="kc-content-wrapper">

          <#-- App-initiated actions should not see warning messages about the need to complete the action -->
          <#-- during login.                                                                               -->
          <#if displayMessage && message?has_content && (message.type != 'warning' || !isAppInitiatedAction??)>
              <div class="alert-${message.type} ${properties.kcAlertClass!} pf-m-<#if message.type = 'error'>danger<#else>${message.type}</#if>">
                  <div class="pf-c-alert__icon">
                      <#if message.type = 'success'><span class="${properties.kcFeedbackSuccessIcon!}"></span></#if>
                      <#if message.type = 'warning'><span class="${properties.kcFeedbackWarningIcon!}"></span></#if>
                      <#if message.type = 'error'><span class="${properties.kcFeedbackErrorIcon!}"></span></#if>
                      <#if message.type = 'info'><span class="${properties.kcFeedbackInfoIcon!}"></span></#if>
                  </div>
                      <span class="${properties.kcAlertTitleClass!}">${kcSanitize(message.summary)?no_esc}</span>
              </div>
          </#if>

          <#nested "form">

          <#if auth?has_content && auth.showTryAnotherWayLink()>
              <form id="kc-select-try-another-way-form" action="${url.loginAction}" method="post">
                  <div class="${properties.kcFormGroupClass!}">
                      <input type="hidden" name="tryAnotherWay" value="on"/>
                      <a href="#" id="try-another-way"
                         onclick="document.forms['kc-select-try-another-way-form'].requestSubmit();return false;">${msg("doTryAnotherWay")}</a>
                  </div>
              </form>
          </#if>

          <#nested "socialProviders">

          <#if displayInfo>
              <div id="kc-info" class="${properties.kcSignUpClass!}">
                  <div id="kc-info-wrapper" class="${properties.kcInfoAreaWrapperClass!}">
                      <#nested "info">
                  </div>
              </div>
          </#if>
        </div>
      </div>

      <@loginFooter.content/>
    </div>
  </div>
</body>
</html>
</#macro>
