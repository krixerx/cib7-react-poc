/// Runtime configuration for the applicant app.
///
/// On Flutter **web** this app is served same-origin with the engine: Traefik
/// routes `/mobile` to this container and `/engine-rest` straight to the CIB
/// seven engine, so the engine base URL is just the current document origin —
/// no build-time value needed. For a future **native** (iOS/Android) build
/// there is no document origin, so pass the public host explicitly with
/// `--dart-define=ENGINE_BASE=https://app.companylab.ai`.
library;

const String _engineBaseOverride =
    String.fromEnvironment('ENGINE_BASE', defaultValue: '');

/// Origin the CIB seven REST API lives on. Empty override → same origin as the
/// loaded document (the web case).
Uri get engineBase {
  if (_engineBaseOverride.isNotEmpty) return Uri.parse(_engineBaseOverride);
  return Uri.parse(Uri.base.origin);
}
