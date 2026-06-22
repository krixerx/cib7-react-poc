import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';
import 'documents.dart';

/// A deployed process definition — surfaced to the applicant as a "service".
/// Mirrors the React SPA's `ProcessDefinition`
/// (frontend/src/api/camundaClient.ts).
class ServiceDefinition {
  ServiceDefinition({
    required this.id,
    required this.key,
    required this.name,
    required this.version,
  });

  /// The versioned definition id (e.g. `vehicle:3:abc…`). A process instance
  /// references this, so it's the join key for naming an application.
  final String id;
  final String key;
  final String name;
  final int version;

  factory ServiceDefinition.fromJson(Map<String, dynamic> json) =>
      ServiceDefinition(
        id: json['id'] as String,
        key: json['key'] as String,
        // `name` is nullable in the engine; fall back to the key like the SPA.
        name: (json['name'] as String?) ?? json['key'] as String,
        version: json['version'] as int,
      );
}

/// Status an applicant sees for one of their applications. A deliberately
/// coarser view than the SPA's `MyProcessesPage` (which inspects open tasks
/// and wait states): for mobile we only distinguish running from finished, and
/// approved from otherwise-ended.
enum ApplicationStatus { inProgress, approved, ended }

/// One of the applicant's process instances, from the history API. Mirrors the
/// SPA's `HistoricProcessInstance` (frontend/src/api/camundaClient.ts) but only
/// the fields the mobile list needs.
class ProcessInstance {
  ProcessInstance({
    required this.id,
    required this.processDefinitionId,
    required this.processDefinitionKey,
    required this.startTime,
    required this.endTime,
  });

  final String id;
  final String processDefinitionId;
  final String processDefinitionKey;
  final DateTime startTime;

  /// Null while the instance is still running.
  final DateTime? endTime;

  bool get isEnded => endTime != null;

  factory ProcessInstance.fromJson(Map<String, dynamic> json) => ProcessInstance(
        id: json['id'] as String,
        processDefinitionId: json['processDefinitionId'] as String,
        processDefinitionKey: json['processDefinitionKey'] as String,
        startTime: DateTime.parse(json['startTime'] as String),
        endTime: (json['endTime'] as String?) != null
            ? DateTime.parse(json['endTime'] as String)
            : null,
      );
}

/// An applicant's application joined with its service name, ready for the list.
class Application {
  Application({
    required this.instance,
    required this.serviceName,
    required this.hasCertificate,
  });

  final ProcessInstance instance;
  final String serviceName;

  /// Whether the case issued a certificate — a `generated-certificate`
  /// document. This is the approval signal: the engine's history API doesn't
  /// populate `endActivityId`, so the issued certificate (the same credential
  /// the wallet holds) is what distinguishes an approved case from one that
  /// ended without one.
  final bool hasCertificate;

  ApplicationStatus get status {
    if (!instance.isEnded) return ApplicationStatus.inProgress;
    return hasCertificate
        ? ApplicationStatus.approved
        : ApplicationStatus.ended;
  }
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

  /// Used to detect the issued certificate that marks an application approved.
  late final DocumentsClient _documents =
      DocumentsClient(client: _client, tokenProvider: tokenProvider);

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

  /// The signed-in applicant's applications, newest first, each joined with its
  /// service name. Mirrors `MyProcessesPage`'s data load: list the user's
  /// historic instances (`startedBy`) and name them from the process
  /// definitions. [userId] must be the Keycloak login name (preferred_username)
  /// — the engine records that as the instance initiator.
  Future<List<Application>> listMyApplications(String userId) async {
    final results = await Future.wait([
      _listMyInstances(userId),
      listServices(),
    ]);
    final instances = results[0] as List<ProcessInstance>;
    final defs = results[1] as List<ServiceDefinition>;

    // Name by definition id when we have it (exact version), else by key — the
    // services list is latest-version only, so older instances fall back to key.
    final nameById = {for (final d in defs) d.id: d.name};
    final nameByKey = {for (final d in defs) d.key: d.name};
    String nameFor(ProcessInstance pi) =>
        nameById[pi.processDefinitionId] ??
        nameByKey[pi.processDefinitionKey] ??
        pi.processDefinitionKey;

    // Approval signal: the engine's history API doesn't expose the end event
    // id, so an ended instance counts as approved only if it issued a
    // `generated-certificate`. Looked up per ended instance (concurrently) and
    // degraded to false on error, so a documents hiccup never fails the list.
    final hasCert = await Future.wait(
      instances.map((pi) async {
        if (!pi.isEnded) return false;
        try {
          final docs = await _documents.listAttachments(pi.id);
          return docs.any((d) => d.isCertificate);
        } catch (_) {
          return false;
        }
      }),
    );

    return [
      for (var i = 0; i < instances.length; i++)
        Application(
          instance: instances[i],
          serviceName: nameFor(instances[i]),
          hasCertificate: hasCert[i],
        ),
    ];
  }

  Future<List<ProcessInstance>> _listMyInstances(String userId) async {
    final uri = engineBase.replace(
      path: '/engine-rest/history/process-instance',
      queryParameters: {
        'startedBy': userId,
        'sortBy': 'startTime',
        'sortOrder': 'desc',
        'maxResults': '100',
      },
    );
    final res = await _client.get(uri, headers: await _headers());
    if (res.statusCode != 200) {
      throw Exception('Engine returned HTTP ${res.statusCode}');
    }
    final list = jsonDecode(res.body) as List<dynamic>;
    return list
        .map((e) => ProcessInstance.fromJson(e as Map<String, dynamic>))
        .toList();
  }
}
