import 'dart:convert';

import 'package:http/http.dart' as http;

import 'config.dart';

/// A document attached to a process instance, from the backend `/api/documents`
/// API. Mirrors the SPA's `DocumentEntry` (frontend/src/api/documentsApi.ts) —
/// only the fields the mobile detail/wallet needs.
class DocumentEntry {
  DocumentEntry({
    required this.id,
    required this.category,
    required this.filename,
    required this.contentType,
    required this.createdAt,
  });

  final String id;

  /// e.g. `generated-certificate`, `generated-approval-pdf`,
  /// `applicant-id-document`. `generated-*` are system-produced PDFs.
  final String category;
  final String filename;
  final String contentType;
  final DateTime? createdAt;

  /// The "certificate of approval" — the document the wallet is built around.
  bool get isCertificate => category == 'generated-certificate';

  /// Engine-produced PDFs vs applicant uploads (prefix matches the SPA's
  /// DocumentsCard split).
  bool get isGenerated => category.startsWith('generated-');

  /// UI label for the category, mirroring `categoryLabel` in the SPA.
  String get label => switch (category) {
        'applicant-id-document' => 'ID document',
        'founder-articles-of-association' => 'Articles of Association',
        'generated-approval-pdf' => 'Approval PDF',
        'generated-certificate' => 'Certificate of approval',
        'generated-business-fee-invoice' => 'State fee invoice',
        'generated-bcard' => 'B-card extract',
        _ => category,
      };

  factory DocumentEntry.fromJson(Map<String, dynamic> json) => DocumentEntry(
        id: json['id'] as String,
        category: json['category'] as String,
        filename: json['filename'] as String,
        contentType: json['contentType'] as String,
        createdAt: (json['createdAt'] as String?) != null
            ? DateTime.tryParse(json['createdAt'] as String)
            : null,
      );
}

/// Client for the backend `/api/documents` endpoints. Same-origin on web
/// (Traefik / the mobile nginx proxies `/api` to the backend). All calls are
/// bearer-authed; the backend gates each case by ownership.
class DocumentsClient {
  DocumentsClient({http.Client? client, this.tokenProvider})
      : _client = client ?? http.Client();

  final http.Client _client;
  final Future<String?> Function()? tokenProvider;

  Future<Map<String, String>> _headers() async {
    final headers = {'Accept': 'application/json'};
    final token = await tokenProvider?.call();
    if (token != null) headers['Authorization'] = 'Bearer $token';
    return headers;
  }

  /// Documents on one process instance, in the order the engine produced them.
  Future<List<DocumentEntry>> listAttachments(String processInstanceId) async {
    final uri = engineBase.replace(
      path: '/api/documents/${Uri.encodeComponent(processInstanceId)}',
    );
    // `no-store` — the backend GET sends no Cache-Control, and a freshly
    // generated certificate must not be hidden behind a stale cached list.
    final res = await _client.get(
      uri,
      headers: {...await _headers(), 'Cache-Control': 'no-store'},
    );
    if (res.statusCode != 200) {
      throw Exception('Documents API returned HTTP ${res.statusCode}');
    }
    final list = jsonDecode(res.body) as List<dynamic>;
    return list
        .map((e) => DocumentEntry.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Mints a short-lived (≈60s) presigned GET URL to download an attachment.
  Future<String> getDownloadUrl(String attachmentId) async {
    final uri = engineBase.replace(
      path: '/api/documents/attachments/${Uri.encodeComponent(attachmentId)}'
          '/download-url',
    );
    final res = await _client.get(uri, headers: await _headers());
    if (res.statusCode != 200) {
      throw Exception('Download URL returned HTTP ${res.statusCode}');
    }
    final json = jsonDecode(res.body) as Map<String, dynamic>;
    return json['url'] as String;
  }
}
