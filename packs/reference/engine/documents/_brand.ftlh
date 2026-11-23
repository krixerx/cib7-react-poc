<#--
  Shared layout for every PDF document of this pack: the page skeleton, the
  house style and the brand bar (the pack's logo and portal name from
  branding/, as `brand`). A document imports it and wraps its body:

    <#import "/_brand.ftlh" as b>
    <@b.page title="Vehicle Registration Certificate" accent="#003c7a"> … </@b.page>

  `accent` is the issuing authority's colour; it defaults to the brand's
  primary colour. Classes a document may use: border, header (rule), org,
  meta, code, lead, party-list, muted, amount-due, stamp, ref, footer.
  Rendered by the engine's `documents` bean (DocumentRenderer); values are
  HTML-escaped automatically.
-->
<#macro page title accent=brand.primary>
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>${title}</title>
  <style>
    :root { --accent: ${accent}; }
    body { font-family: -apple-system, Segoe UI, Roboto, sans-serif; margin: 2.5rem; color: #1a1a1a; }
    .brandbar { display: flex; align-items: center; gap: 0.6rem; margin-bottom: 1.5rem; color: #555; font-size: 0.8rem; }
    .brandbar img { height: 28px; width: auto; }
    .border { border: 2px solid var(--accent); padding: 2.5rem 2.5rem 2rem; border-radius: 6px; }
    .header { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 1px solid #ddd; padding-bottom: 1rem; margin-bottom: 1.5rem; }
    .header.rule { border-bottom: 3px solid var(--accent); margin-bottom: 2rem; }
    .header h1 { color: var(--accent); margin: 0 0 0.25rem 0; font-size: 1.5rem; }
    .org { color: #555; font-size: 0.85rem; }
    .meta { text-align: right; color: #555; font-size: 0.85rem; }
    .meta strong { color: #1a1a1a; }
    .meta .code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--accent); font-size: 1.05rem; display: block; }
    .lead { font-size: 0.95rem; line-height: 1.5; margin-top: 0; }
    h2 { color: var(--accent); margin-top: 1.5rem; margin-bottom: 0.4rem; font-size: 0.95rem; text-transform: uppercase; letter-spacing: 0.05em; }
    table { border-collapse: collapse; width: 100%; }
    th, td { text-align: left; padding: 0.35rem 0.6rem; border-bottom: 1px solid #eee; font-size: 0.95rem; }
    th { width: 11rem; color: #555; font-weight: 500; }
    ul.party-list { margin: 0; padding-left: 1.25rem; }
    ul.party-list li { padding: 0.2rem 0; font-size: 0.95rem; }
    .muted { color: #666; }
    .amount-due { background: color-mix(in srgb, var(--accent) 7%, white); border-left: 4px solid var(--accent); padding: 1rem 1.25rem; margin-top: 2rem; font-size: 1.05rem; }
    .amount-due strong { color: var(--accent); font-size: 1.4rem; display: block; margin-top: 0.25rem; }
    .stamp { margin-top: 2rem; padding-top: 1rem; border-top: 1px solid #ddd; display: flex; justify-content: space-between; color: #666; font-size: 0.8rem; }
    .ref { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
    .footer { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid #ddd; color: #888; font-size: 0.75rem; }
  </style>
</head>
<body>
  <div class="brandbar"><#if brand.logo??><img src="${brand.logo}" alt="" /></#if><span>Issued through ${brand.name}</span></div>
  <#nested>
</body>
</html>
</#macro>
