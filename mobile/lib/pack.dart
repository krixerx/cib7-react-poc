/// The service pack's branding and texts for the app, read once at startup.
///
/// The app holds nothing customer-specific. Its nginx serves the pack's
/// `branding/` and catalog texts under `/mobile/pack/` (mobile/Dockerfile),
/// the same files the SPA and the login pages read, so one rebrand reaches all
/// three. The rules are the SPA's brand format v1 (frontend/src/pack/brand.ts):
/// hex colours, listed tokens, bare image file names; one value outside them
/// refuses that file whole and the core default stays. A missing or unreadable
/// pack never stops the app.
library;

import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;

/// The core defaults: the SPA's tokens.css and i18n texts.
const _corePrimaryLight = Color(0xFF0B57C9);
const _corePrimaryDark = Color(0xFF6AA5FF);
const _coreName = 'eRegistrations';
const _coreSub = 'Public services portal';

/// The core's own document category and its label; every other category is
/// the pack's (`backend/documents.json`, labels in its catalog texts).
const _coreDocumentLabels = {'generated-certificate': 'Certificate of approval'};

const _brandColorTokens = {
  'primary', 'primary-hover', 'primary-ink', 'primary-soft', 'primary-soft-border',
  'mesh-1', 'mesh-2', 'mesh-3', 'mesh-4', 'banner-bg', 'banner-fg', 'banner-strong',
};
final _hex = RegExp(r'^#[0-9a-fA-F]{6}$');
final _font = RegExp(r'^[A-Za-z0-9][A-Za-z0-9 ]{0,39}$');
final _image = RegExp(r'^[a-z0-9][a-z0-9-]{0,62}\.(svg|png|webp)$');

class Pack {
  const Pack({
    required this.name,
    required this.sub,
    required this.primaryLight,
    required this.primaryDark,
    required this.logoLight,
    required this.logoDark,
    required this.documentLabels,
  });

  /// Portal name and subtitle (`branding/locales/en/brand.json`).
  final String name;
  final String sub;

  /// The brand's primary colour per scheme (`branding/tokens.json`), the
  /// seed of the app's Material colour scheme.
  final Color primaryLight;
  final Color primaryDark;

  /// Logo image URLs, or null for the core icon.
  final Uri? logoLight;
  final Uri? logoDark;

  final Map<String, String> documentLabels;

  static const core = Pack(
    name: _coreName,
    sub: _coreSub,
    primaryLight: _corePrimaryLight,
    primaryDark: _corePrimaryDark,
    logoLight: null,
    logoDark: null,
    documentLabels: _coreDocumentLabels,
  );

  /// The label of a document category: the pack's, the core's, else the code.
  String documentLabel(String category) => documentLabels[category] ?? category;

  /// Reads the pack under `/mobile/pack/`; never throws.
  static Future<Pack> load() async {
    // Absolute: the web build's base href is /mobile/ (mobile/Dockerfile), whatever
    // the current route. A native build has no such origin and keeps the core look.
    final base = Uri.base.resolve('/mobile/pack/');
    final tokens = tokensOf(await _json(base.resolve('branding/tokens.json')));
    final brand = logoOf(await _json(base.resolve('branding/brand.json')));
    final texts = await _json(base.resolve('branding/locales/en/brand.json'));
    final catalog = await _json(base.resolve('locales/en/catalog.json'));

    String text(String key, String fallback) {
      final v = texts?[key];
      return v is String && v.isNotEmpty ? v : fallback;
    }

    final labels = Map<String, String>.of(_coreDocumentLabels);
    final documents = catalog?['documents'];
    if (documents is Map) {
      documents.forEach((k, v) {
        if (k is String && v is String && v.isNotEmpty) labels[k] = v;
      });
    }
    Uri? image(String? file) => file == null ? null : base.resolve('branding/$file');
    return Pack(
      name: text('name', _coreName),
      sub: text('sub', _coreSub),
      primaryLight: _color(tokens?['light']?['primary']) ?? _corePrimaryLight,
      primaryDark: _color(tokens?['dark']?['primary']) ?? _corePrimaryDark,
      logoLight: image(brand?['light']),
      logoDark: image(brand?['dark'] ?? brand?['light']),
      documentLabels: labels,
    );
  }

  static Future<Map<String, dynamic>?> _json(Uri url) async {
    try {
      final res = await http.get(url, headers: {'Accept': 'application/json'});
      if (res.statusCode != 200) return null;
      final body = jsonDecode(utf8.decode(res.bodyBytes));
      return body is Map<String, dynamic> ? body : null;
    } catch (e) {
      debugPrint('Service pack: $url not read ($e); using the core default.');
      return null;
    }
  }

  /// tokens.json if it is brand format v1 throughout, else null (all core).
  @visibleForTesting
  static Map<String, dynamic>? tokensOf(Map<String, dynamic>? t) {
    if (t == null) return null;
    bool valid() {
      if (t['version'] != 1) return false;
      if (t.keys.any((k) => !{r'$comment', 'version', 'light', 'dark', 'fonts'}.contains(k))) {
        return false;
      }
      for (final scheme in ['light', 'dark']) {
        final colors = t[scheme] ?? <String, dynamic>{};
        if (colors is! Map) return false;
        for (final e in colors.entries) {
          if (!_brandColorTokens.contains(e.key) || e.value is! String || !_hex.hasMatch(e.value)) {
            return false;
          }
        }
      }
      final fonts = t['fonts'] ?? <String, dynamic>{};
      if (fonts is! Map) return false;
      for (final e in fonts.entries) {
        if (!{'display', 'body'}.contains(e.key) || e.value is! String || !_font.hasMatch(e.value)) {
          return false;
        }
      }
      return true;
    }

    if (valid()) return t;
    debugPrint('Service pack: tokens.json is outside brand format v1; using the core colours.');
    return null;
  }

  /// brand.json's logo file names if valid brand format v1, else null.
  @visibleForTesting
  static Map<String, String?>? logoOf(Map<String, dynamic>? b) {
    if (b == null || b['version'] != 1) return null;
    if (b.keys.any((k) => !{r'$comment', 'version', 'logo', 'favicon'}.contains(k))) return null;
    final logo = b['logo'];
    if (logo == null) return null;
    if (logo is! Map || logo.keys.any((k) => !{'light', 'dark'}.contains(k))) return null;
    String? file(Object? v) => v is String && _image.hasMatch(v) ? v : null;
    final light = file(logo['light']);
    if (light == null || (logo['dark'] != null && file(logo['dark']) == null)) return null;
    return {'light': light, 'dark': file(logo['dark'])};
  }

  /// The pack the app runs with; set once in main() before the first frame.
  static Pack current = core;

  static Color? _color(Object? hex) =>
      hex is String && _hex.hasMatch(hex) ? Color(0xFF000000 | int.parse(hex.substring(1), radix: 16)) : null;
}
