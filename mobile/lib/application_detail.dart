import 'package:flutter/material.dart';
import 'package:web/web.dart' as web;

import 'api.dart';
import 'auth/auth_service.dart';
import 'documents.dart';
import 'wallet.dart';

/// Detail for one application: its documents, and — when the case has produced
/// a `generated-certificate` — that certificate shown as a scannable QR card
/// (RFP FR 2.6) with a download and an "Add to wallet" toggle.
class ApplicationDetailScreen extends StatefulWidget {
  const ApplicationDetailScreen({
    super.key,
    required this.app,
    required this.auth,
    required this.walletStore,
  });

  final Application app;
  final AuthService auth;
  final WalletStore walletStore;

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
                    item: WalletItem.from(widget.app, certificate),
                    actions: [
                      _WalletToggle(
                        store: widget.walletStore,
                        item: WalletItem.from(widget.app, certificate),
                      ),
                      FilledButton.icon(
                        onPressed: () => _download(certificate),
                        icon: const Icon(Icons.download),
                        label: const Text('Download PDF'),
                      ),
                    ],
                  )
                else
                  _NoCertificateNote(status: widget.app.status),
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

/// Add/remove this certificate to the wallet, reflecting current store state.
class _WalletToggle extends StatelessWidget {
  const _WalletToggle({required this.store, required this.item});

  final WalletStore store;
  final WalletItem item;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final inWallet = store.contains(item.certId);
        if (inWallet) {
          return OutlinedButton.icon(
            onPressed: () {
              store.remove(item.certId);
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Removed from wallet')),
              );
            },
            icon: const Icon(Icons.check),
            label: const Text('In wallet'),
          );
        }
        return FilledButton.tonalIcon(
          onPressed: () {
            store.add(item);
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('Added to wallet')),
            );
          },
          icon: const Icon(Icons.add),
          label: const Text('Add to wallet'),
        );
      },
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.app});

  final Application app;

  @override
  Widget build(BuildContext context) {
    final (icon, text) = switch (app.status) {
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
