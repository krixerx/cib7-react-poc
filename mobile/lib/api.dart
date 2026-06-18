import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';

/// A deployed process definition — surfaced to the applicant as a "service".
/// Mirrors the React SPA's `ProcessDefinition`
/// (frontend/src/api/camundaClient.ts).
class ServiceDefinition {
  ServiceDefinition({
    required this.key,
    required this.name,
    required this.version,
  });

  final String key;
  final String name;
  final int version;

  factory ServiceDefinition.fromJson(Map<String, dynamic> json) =>
      ServiceDefinition(
        key: json['key'] as String,
        // `name` is nullable in the engine; fall back to the key like the SPA.
        name: (json['name'] as String?) ?? json['key'] as String,
        version: json['version'] as int,
      );
}

/// Thin client for the CIB seven REST API (`/engine-rest`).
///
/// The services list (`GET /process-definition`) is the one anonymous
/// endpoint (PublicEngineRestSecurityConfig on the backend) — the SPA also
/// fires it on the public landing page before sign-in. Authenticated calls
/// (my-applications, tasks) attach the Keycloak bearer token via
/// [tokenProvider], supplied by the AuthService.
class EngineClient {
  EngineClient({http.Client? client, this.tokenProvider})
      : _client = client ?? http.Client();

  final http.Client _client;

  /// Returns a fresh access token, or null when signed out (then the request
  /// goes anonymous — fine for the public services list).
  final Future<String?> Function()? tokenProvider;

  Future<Map<String, String>> _headers() async {
    final headers = {'Accept': 'application/json'};
    final token = await tokenProvider?.call();
    if (token != null) headers['Authorization'] = 'Bearer $token';
    return headers;
  }

  Future<List<ServiceDefinition>> listServices() async {
    final uri = engineBase.replace(
      path: '/engine-rest/process-definition',
      queryParameters: {'latestVersion': 'true', 'active': 'true'},
    );
    final res = await _client.get(uri, headers: await _headers());
    if (res.statusCode != 200) {
      throw Exception('Engine returned HTTP ${res.statusCode}');
    }
    final list = jsonDecode(res.body) as List<dynamic>;
    return list
        .map((e) => ServiceDefinition.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}
