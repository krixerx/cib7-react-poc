/// Keycloak settings, read at runtime from `window.__ENV__` (written to
/// `env.js` by the container entrypoint — see mobile/docker/40-runtime-env.sh),
/// exactly like the React SPA's `/env.js`. One published image therefore works
/// on any host: localhost in dev, keycloak.companylab.ai in prod.
///
/// NOTE: `dart:js_interop` is web-only, which is fine — this app only builds
/// the web target for now. A future native build would swap this for a
/// `--dart-define`-based config behind a conditional import.
library;

import 'dart:js_interop';

extension type _Env(JSObject _) implements JSObject {
  external String? get KEYCLOAK_URL;
  external String? get KEYCLOAK_REALM;
  external String? get KEYCLOAK_CLIENT_ID;
}

@JS('window.__ENV__')
external _Env? get _env;

String _orDefault(String? v, String fallback) =>
    (v != null && v.isNotEmpty) ? v : fallback;

class AuthConfig {
  /// Keycloak base URL the *browser* reaches (not an internal docker name).
  static String get keycloakUrl =>
      _orDefault(_env?.KEYCLOAK_URL, 'http://localhost:8180');

  static String get realm => _orDefault(_env?.KEYCLOAK_REALM, 'cib7-poc');

  static String get clientId =>
      _orDefault(_env?.KEYCLOAK_CLIENT_ID, 'cib7-mobile');

  static String get issuer => '$keycloakUrl/realms/$realm';
  static String get authorizationEndpoint =>
      '$issuer/protocol/openid-connect/auth';
  static String get tokenEndpoint => '$issuer/protocol/openid-connect/token';
  static String get endSessionEndpoint =>
      '$issuer/protocol/openid-connect/logout';
}
