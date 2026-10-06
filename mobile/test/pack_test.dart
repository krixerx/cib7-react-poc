import 'package:cib7_applicant/pack.dart';
import 'package:flutter_test/flutter_test.dart';

/// The app applies the SPA's brand format v1 rules (frontend/src/pack/brand.ts).
void main() {
  Map<String, dynamic> tokens() => {
        'version': 1,
        'light': {'primary': '#1f6f43'},
        'dark': {'primary': '#6aa5ff'},
        'fonts': {'body': 'Inter'},
      };

  test('valid tokens are kept', () {
    expect(Pack.tokensOf(tokens()), isNotNull);
  });

  test('one value outside the format refuses the whole file', () {
    for (final change in <void Function(Map<String, dynamic>)>[
      (t) => (t['light'] as Map)['primary'] = 'red; } body { display: none',
      (t) => (t['light'] as Map)['danger'] = '#000000',
      (t) => (t['fonts'] as Map)['body'] = "Sora'; }",
      (t) => t['version'] = 2,
      (t) => t['script'] = 'x',
    ]) {
      final t = tokens();
      change(t);
      expect(Pack.tokensOf(t), isNull, reason: t.toString());
    }
  });

  test('a logo is a bare image file name', () {
    expect(Pack.logoOf({'version': 1, 'logo': {'light': 'logo.svg', 'dark': 'logo-dark.png'}}),
        {'light': 'logo.svg', 'dark': 'logo-dark.png'});
    for (final light in ['../../etc/passwd', 'https://evil.example/x.svg', 'logo.html', 'Logo.svg']) {
      expect(Pack.logoOf({'version': 1, 'logo': {'light': light}}), isNull, reason: light);
    }
  });

  test('labels: the pack, the core certificate, else the code', () {
    final pack = Pack(
      name: 'n',
      sub: 's',
      primaryLight: Pack.core.primaryLight,
      primaryDark: Pack.core.primaryDark,
      logoLight: null,
      logoDark: null,
      documentLabels: {...Pack.core.documentLabels, 'applicant-id-document': 'ID document'},
    );
    expect(pack.documentLabel('applicant-id-document'), 'ID document');
    expect(pack.documentLabel('generated-certificate'), 'Certificate of approval');
    expect(pack.documentLabel('unknown-category'), 'unknown-category');
  });
}
