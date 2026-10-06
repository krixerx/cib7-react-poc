// Fills the web shell's brand placeholders (web/index.html, web/manifest.json)
// from the service pack's branding, at image build: a browser reads the page
// title and the install manifest before the app runs, so these cannot come at
// runtime like the rest of the brand (lib/pack.dart). Same rules as the app:
// a value outside brand format v1 keeps the core default.
//
//   dart tool/brand_web_shell.dart <branding-dir> <web-dir>
import 'dart:convert';
import 'dart:io';

const coreName = 'eRegistrations';
const coreColor = '#0b57c9';

Map<String, dynamic>? readJson(String path) {
  final file = File(path);
  if (!file.existsSync()) return null;
  try {
    final value = jsonDecode(file.readAsStringSync());
    return value is Map<String, dynamic> ? value : null;
  } on FormatException {
    stderr.writeln('brand_web_shell: $path is not JSON; using the core default');
    return null;
  }
}

String htmlEscape(String s) => s
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;');

void main(List<String> args) {
  if (args.length != 2) {
    stderr.writeln('usage: dart tool/brand_web_shell.dart <branding-dir> <web-dir>');
    exit(2);
  }
  final branding = args[0];
  final web = args[1];
  final texts = readJson('$branding/locales/en/brand.json');
  final tokens = readJson('$branding/tokens.json');

  final rawName = texts?['name'];
  final name = rawName is String && rawName.trim().isNotEmpty && rawName.length <= 60
      ? rawName.trim()
      : coreName;
  final rawColor = (tokens?['version'] == 1 && tokens?['light'] is Map)
      ? (tokens!['light'] as Map)['primary']
      : null;
  final color = rawColor is String && RegExp(r'^#[0-9a-fA-F]{6}$').hasMatch(rawColor)
      ? rawColor
      : coreColor;

  final index = File('$web/index.html');
  index.writeAsStringSync(index
      .readAsStringSync()
      .replaceAll('__BRAND_NAME__', htmlEscape(name))
      .replaceAll('__BRAND_COLOR__', color));
  // The manifest is JSON: encode the name, then drop the quotes the template keeps.
  final manifest = File('$web/manifest.json');
  final encodedName = jsonEncode(name);
  manifest.writeAsStringSync(manifest
      .readAsStringSync()
      .replaceAll('"__BRAND_NAME__"', encodedName)
      .replaceAll('__BRAND_NAME__', encodedName.substring(1, encodedName.length - 1))
      .replaceAll('__BRAND_COLOR__', color));
  stdout.writeln('brand_web_shell: "$name", $color');
}
