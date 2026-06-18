import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:web/web.dart' as web;

import 'auth_config.dart';

/// OpenID Connect Authorization Code + PKCE flow for the Flutter **web** app,
/// against the `cib7-mobile` public Keycloak client. Mirrors what keycloak-js
/// does for the React SPA, implemented directly to avoid a heavy dependency:
///
///  - [login] redirects the browser to Keycloak with a PKCE challenge.
///  - [init] (called once at startup) completes the redirect — it swaps the
///    returned `code` for tokens — or restores a session from a saved refresh
///    token.
///  - [token] hands callers a valid access token, refreshing if it is about
///    to expire. Wire this into the API clients as the bearer source.
///
/// Tokens live in `sessionStorage` so a page reload keeps the session, and are
/// cleared on [logout].
class AuthService extends ChangeNotifier {
  AuthService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const _kVerifier = 'cib7_pkce_verifier';
  static const _kState = 'cib7_oauth_state';
  static const _kRefresh = 'cib7_refresh_token';

  String? _accessToken;
  String? _refreshToken;
  DateTime? _expiresAt;
  String? _username;

  bool get isAuthenticated => _accessToken != null;
  String? get username => _username;

  /// The app's own URL used as the OIDC redirect target — current origin +
  /// path, query/fragment stripped. With the app served under `/mobile/` this
  /// is e.g. `http://localhost:3001/mobile/` — covered by the client's
  /// `…/*` redirect URI in the realm export.
  String get _redirectUri {
    final loc = web.window.location;
    return '${loc.origin}${loc.pathname}';
  }

  /// Completes a pending login redirect, or restores a saved session. Safe to
  /// call once on startup regardless of which case applies.
  Future<void> init() async {
    final params = Uri.parse(web.window.location.href).queryParameters;
    if (params['code'] != null && params['state'] != null) {
      await _completeLogin(params['code']!, params['state']!);
      _stripQueryFromUrl();
      return;
    }
    final saved = web.window.sessionStorage.getItem(_kRefresh);
    if (saved != null && saved.isNotEmpty) {
      _refreshToken = saved;
      try {
        await _refresh();
      } catch (_) {
        _clear();
      }
    }
  }

  Future<void> login() async {
    final verifier = _randomString(64);
    final state = _randomString(24);
    web.window.sessionStorage.setItem(_kVerifier, verifier);
    web.window.sessionStorage.setItem(_kState, state);

    final url = Uri.parse(AuthConfig.authorizationEndpoint).replace(
      queryParameters: {
        'client_id': AuthConfig.clientId,
        'response_type': 'code',
        // Just 'openid' — the cib7-mobile client (like cib7-frontend) only has
        // the cib7-claims scope assigned, not the built-in 'profile' scope, so
        // requesting 'profile' yields invalid_scope. preferred_username still
        // rides along in the access token.
        'scope': 'openid',
        'redirect_uri': _redirectUri,
        'state': state,
        'code_challenge': _codeChallenge(verifier),
        'code_challenge_method': 'S256',
      },
    );
    web.window.location.href = url.toString();
  }

  Future<void> logout() async {
    final refresh = _refreshToken;
    _clear();
    notifyListeners();
    final url = Uri.parse(AuthConfig.endSessionEndpoint).replace(
      queryParameters: {
        'client_id': AuthConfig.clientId,
        'post_logout_redirect_uri': _redirectUri,
        if (refresh != null) 'refresh_token': refresh,
      },
    );
    web.window.location.href = url.toString();
  }

  /// A valid access token, refreshed if it expires within 30s. Null when not
  /// signed in. Use as the bearer source for API clients.
  Future<String?> token() async {
    if (_accessToken == null) return null;
    final exp = _expiresAt;
    if (exp != null && exp.difference(DateTime.now()).inSeconds < 30) {
      try {
        await _refresh();
      } catch (_) {
        _clear();
        notifyListeners();
        return null;
      }
    }
    return _accessToken;
  }

  // --- internals ----------------------------------------------------------

  Future<void> _completeLogin(String code, String state) async {
    final expectedState = web.window.sessionStorage.getItem(_kState);
    final verifier = web.window.sessionStorage.getItem(_kVerifier);
    web.window.sessionStorage.removeItem(_kState);
    web.window.sessionStorage.removeItem(_kVerifier);
    if (expectedState == null || state != expectedState || verifier == null) {
      throw StateError('OAuth state/verifier mismatch');
    }
    await _exchange({
      'grant_type': 'authorization_code',
      'client_id': AuthConfig.clientId,
      'code': code,
      'redirect_uri': _redirectUri,
      'code_verifier': verifier,
    });
  }

  Future<void> _refresh() async {
    final refresh = _refreshToken;
    if (refresh == null) throw StateError('No refresh token');
    await _exchange({
      'grant_type': 'refresh_token',
      'client_id': AuthConfig.clientId,
      'refresh_token': refresh,
    });
  }

  Future<void> _exchange(Map<String, String> body) async {
    final res = await _client.post(
      Uri.parse(AuthConfig.tokenEndpoint),
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: body,
    );
    if (res.statusCode != 200) {
      throw Exception('Token endpoint HTTP ${res.statusCode}');
    }
    final json = jsonDecode(res.body) as Map<String, dynamic>;
    _accessToken = json['access_token'] as String?;
    _refreshToken = (json['refresh_token'] as String?) ?? _refreshToken;
    final expiresIn = (json['expires_in'] as num?)?.toInt() ?? 60;
    _expiresAt = DateTime.now().add(Duration(seconds: expiresIn));
    _username = _usernameFromJwt(_accessToken);
    if (_refreshToken != null) {
      web.window.sessionStorage.setItem(_kRefresh, _refreshToken!);
    }
    notifyListeners();
  }

  void _clear() {
    _accessToken = null;
    _refreshToken = null;
    _expiresAt = null;
    _username = null;
    web.window.sessionStorage.removeItem(_kRefresh);
  }

  void _stripQueryFromUrl() {
    final loc = web.window.location;
    web.window.history
        .replaceState(null, '', '${loc.origin}${loc.pathname}');
  }

  static String? _usernameFromJwt(String? jwt) {
    if (jwt == null) return null;
    final parts = jwt.split('.');
    if (parts.length != 3) return null;
    try {
      final payload = jsonDecode(
        utf8.decode(base64Url.decode(base64Url.normalize(parts[1]))),
      ) as Map<String, dynamic>;
      return (payload['name'] ?? payload['preferred_username']) as String?;
    } catch (_) {
      return null;
    }
  }

  static String _randomString(int len) {
    const chars =
        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    final r = Random.secure();
    return List.generate(len, (_) => chars[r.nextInt(chars.length)]).join();
  }

  static String _codeChallenge(String verifier) {
    final digest = sha256.convert(ascii.encode(verifier));
    return base64Url.encode(digest.bytes).replaceAll('=', '');
  }
}
