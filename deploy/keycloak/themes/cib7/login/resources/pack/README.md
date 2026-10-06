Mountpoint for the service pack's `branding/` folder (compose mounts it here,
read-only). `template.ftl` reads `brand.json`, `tokens.json` and
`locales/<lang>/brand.json` from it and serves the logo images. Empty, the
theme shows the core defaults.
