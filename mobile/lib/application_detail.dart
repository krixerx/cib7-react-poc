import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:web/web.dart' as web;

import 'api.dart';
import 'auth/auth_service.dart';
import 'documents.dart';

/// Detail for one application: its documents, and — when the case has produced
/// a `generated-certificate` — that certificate shown as a scannable QR card
/// (RFP FR 2.6) with a download. The "Add to wallet" action lands in the next
/// step; for now the certificate card is the thing the wallet will collect.
class ApplicationDetailScreen extends StatefulWidget {
  const ApplicationDetailScreen({
    super.key,
    required this.app,
    required this.auth,
  });

  final Application app;
  final AuthService auth;

  @override
  State<ApplicationDetailScreen> createState() =>
      _ApplicationDetailScreenState();
}

class _ApplicationDetailScreenState extends State<ApplicationDetailScreen> {
  late final DocumentsClient _client =
      DocumentsClient(tokenProvider: widget.auth.token);
  late Future<List<DocumentEntry>> _docs;

  @override
  void initState() {
    super.initState();
    _docs = _client.listAttachments(widget.app.instance.id);
  }

  void _reload() =>
      setState(() => _docs = _client.listAttachments(widget.app.instance.id));

  Future<void> _download(DocumentEntry doc) async {
    try {
      final url = await _client.getDownloadUrl(doc.id);
      web.window.open(url, '_blank');
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not open ${doc.filename}: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.app.serviceName)),
      body: FutureBuilder<List<DocumentEntry>>(
        future: _docs,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _ErrorView(message: '${snapshot.error}', onRetry: _reload);
          }
          final docs = snapshot.data ?? const <DocumentEntry>[];
          final certificate =
              docs.where((d) => d.isCertificate).firstOrNull;
          final others = docs.where((d) => !d.isCertificate).toList();

          return RefreshIndicator(
            onRefresh: () async => _reload(),
            child: ListView(
              padding: const EdgeInsets.all(16),
              children: [
                _StatusBanner(app: widget.app),
                const SizedBox(height: 16),
                if (certificate != null)
                  CertificateCard(
                    app: widget.app,
                    certificate: certificate,
                    onDownload: () => _download(certificate),
                  )
                else
                  _NoCertificateNote(status: widget.app.instance.status),
                if (others.isNotEmpty) ...[
                  const SizedBox(height: 24),
                  Text(
                    'Documents',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                  const SizedBox(height: 8),
                  for (final d in others)
                    Card(
                      margin: const EdgeInsets.only(bottom: 8),
                      child: ListTile(
                        leading: Icon(
                          d.isGenerated
                              ? Icons.description_outlined
                              : Icons.attach_file,
                        ),
                        title: Text(d.label),
                        subtitle: Text(d.filename),
                        trailing: IconButton(
                          icon: const Icon(Icons.download),
                          tooltip: 'Download',
                          onPressed: () => _download(d),
                        ),
                      ),
                    ),
                ],
              ],
            ),
          );
        },
      ),
    );
  }
}

/// The certificate as a wallet-style card: service title, a QR encoding a
/// durable verification reference, and a download. Designed so a real Apple/
/// Google wallet pass can replace the QR card later without changing the flow.
class CertificateCard extends StatelessWidget {
  const CertificateCard({
    super.key,
    required this.app,
    required this.certificate,
    required this.onDownload,
  });

  final Application app;
  final DocumentEntry certificate;
  final VoidCallback onDownload;

  /// A durable, self-describing payload a verifier can scan. Not the presigned
  /// download URL (that expires in ~60s) — these are stable identifiers, the
  /// same data a printed certificate's verification QR would carry.
  String get _qrPayload {
    final pi = app.instance;
    final issued = (certificate.createdAt ?? pi.endTime ?? pi.startTime)
        .toUtc()
        .toIso8601String();
    return [
      'eRegistrations certificate',
      'ref:${certificate.id}',
      'case:${pi.id}',
      'service:${app.serviceName}',
      'issued:$issued',
    ].join('\n');
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      elevation: 0,
      color: scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            Row(
              children: [
                Icon(Icons.verified, color: scheme.onPrimaryContainer),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'Certificate of approval',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          color: scheme.onPrimaryContainer,
                          fontWeight: FontWeight.w600,
                        ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 4),
            Align(
              alignment: AlignmentDirectional.centerStart,
              child: Text(
                app.serviceName,
                style: Theme.of(context)
                    .textTheme
                    .bodyMedium
                    ?.copyWith(color: scheme.onPrimaryContainer),
              ),
            ),
            const SizedBox(height: 16),
            // White quiet-zone backing so the code stays scannable on the
            // tinted card.
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.circular(12),
              ),
              child: QrImageView(
                data: _qrPayload,
                version: QrVersions.auto,
                size: 180,
                gapless: false,
              ),
            ),
            const SizedBox(height: 12),
            Text(
              'Present this code for verification.',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                    color: scheme.onPrimaryContainer,
                  ),
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: onDownload,
              icon: const Icon(Icons.download),
              label: const Text('Download PDF'),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.app});

  final Application app;

  @override
  Widget build(BuildContext context) {
    final pi = app.instance;
    final (icon, text) = switch (pi.status) {
      ApplicationStatus.approved => (Icons.check_circle, 'Approved'),
      ApplicationStatus.inProgress => (Icons.hourglass_top, 'In progress'),
      ApplicationStatus.ended => (Icons.cancel, 'Closed'),
    };
    return Row(
      children: [
        Icon(icon, size: 20),
        const SizedBox(width: 8),
        Text(text, style: Theme.of(context).textTheme.titleMedium),
      ],
    );
  }
}

class _NoCertificateNote extends StatelessWidget {
  const _NoCertificateNote({required this.status});

  final ApplicationStatus status;

  @override
  Widget build(BuildContext context) {
    final message = status == ApplicationStatus.inProgress
        ? 'A certificate will appear here once this application is approved.'
        : 'No certificate was issued for this application.';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          children: [
            const Icon(Icons.info_outline),
            const SizedBox(width: 12),
            Expanded(child: Text(message)),
          ],
        ),
      ),
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.cloud_off, size: 48),
            const SizedBox(height: 12),
            Text(
              'Could not load documents',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }
}
