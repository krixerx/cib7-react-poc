import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:web/web.dart' as web;

import 'api.dart';
import 'auth/auth_service.dart';
import 'documents.dart';

/// One certificate the applicant has saved to their in-app wallet. Holds the
/// durable fields a verifier needs — the wallet renders entirely from these,
/// no network call — so a saved card keeps working offline. Designed to map
/// cleanly onto a real Apple/Google wallet pass later (the QR payload is the
/// pass's barcode message).
class WalletItem {
  WalletItem({
    required this.certId,
    required this.caseId,
    required this.serviceName,
    required this.issuedAt,
  });

  /// The certificate attachment id — also the wallet's unique key.
  final String certId;
  final String caseId;
  final String serviceName;
  final DateTime issuedAt;

  /// The scannable payload (RFP FR 2.6). Durable identifiers, NOT the 60s
  /// presigned download URL. Single source of truth for both the detail card
  /// and the wallet card, so they always render the same code.
  String get qrPayload => [
        'eRegistrations certificate',
        'ref:$certId',
        'case:$caseId',
        'service:$serviceName',
        'issued:${issuedAt.toUtc().toIso8601String()}',
      ].join('\n');

  factory WalletItem.from(Application app, DocumentEntry certificate) {
    final pi = app.instance;
    return WalletItem(
      certId: certificate.id,
      caseId: pi.id,
      serviceName: app.serviceName,
      issuedAt: certificate.createdAt ?? pi.endTime ?? pi.startTime,
    );
  }

  Map<String, dynamic> toJson() => {
        'certId': certId,
        'caseId': caseId,
        'serviceName': serviceName,
        'issuedAt': issuedAt.toIso8601String(),
      };

  factory WalletItem.fromJson(Map<String, dynamic> json) => WalletItem(
        certId: json['certId'] as String,
        caseId: json['caseId'] as String,
        serviceName: json['serviceName'] as String,
        issuedAt: DateTime.parse(json['issuedAt'] as String),
      );
}

/// The applicant's wallet, persisted in `localStorage` and namespaced per user
/// (the browser is shared, so homer's wallet must not leak into bart's). A
/// [ChangeNotifier] so the certificate card's toggle and the Wallet tab both
/// stay in sync.
class WalletStore extends ChangeNotifier {
  String? _userId;
  List<WalletItem> _items = [];

  List<WalletItem> get items => List.unmodifiable(_items);

  String _storageKey(String userId) => 'cib7_wallet_$userId';

  /// Loads the given user's saved cards. Call once we know who is signed in.
  void load(String userId) {
    _userId = userId;
    _items = _read(userId);
    notifyListeners();
  }

  bool contains(String certId) => _items.any((i) => i.certId == certId);

  void add(WalletItem item) {
    if (contains(item.certId)) return;
    _items = [..._items, item];
    _persist();
    notifyListeners();
  }

  void remove(String certId) {
    _items = _items.where((i) => i.certId != certId).toList();
    _persist();
    notifyListeners();
  }

  List<WalletItem> _read(String userId) {
    final raw = web.window.localStorage.getItem(_storageKey(userId));
    if (raw == null || raw.isEmpty) return [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .map((e) => WalletItem.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (_) {
      return []; // tolerate a corrupt/old payload rather than crashing
    }
  }

  void _persist() {
    final userId = _userId;
    if (userId == null) return;
    web.window.localStorage.setItem(
      _storageKey(userId),
      jsonEncode(_items.map((e) => e.toJson()).toList()),
    );
  }
}

/// The Wallet tab: the saved certificates as scannable QR cards. Renders from
/// the local store, so it works without a round-trip; downloads still mint a
/// fresh presigned URL on demand.
class WalletScreen extends StatelessWidget {
  const WalletScreen({super.key, required this.store, required this.auth});

  final WalletStore store;
  final AuthService auth;

  Future<void> _download(BuildContext context, WalletItem item) async {
    final client = DocumentsClient(tokenProvider: auth.token);
    try {
      final url = await client.getDownloadUrl(item.certId);
      web.window.open(url, '_blank');
    } catch (e) {
      if (!context.mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not open the certificate: $e')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: store,
      builder: (context, _) {
        final items = store.items;
        if (items.isEmpty) return const _WalletEmpty();
        return ListView(
          padding: const EdgeInsets.all(16),
          children: [
            for (final item in items)
              Padding(
                padding: const EdgeInsets.only(bottom: 16),
                child: CertificateCard(
                  item: item,
                  actions: [
                    OutlinedButton.icon(
                      onPressed: () => store.remove(item.certId),
                      icon: const Icon(Icons.delete_outline),
                      label: const Text('Remove'),
                    ),
                    FilledButton.icon(
                      onPressed: () => _download(context, item),
                      icon: const Icon(Icons.download),
                      label: const Text('Download PDF'),
                    ),
                  ],
                ),
              ),
          ],
        );
      },
    );
  }
}

class _WalletEmpty extends StatelessWidget {
  const _WalletEmpty();

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.account_balance_wallet_outlined, size: 48),
            const SizedBox(height: 12),
            Text(
              'Your wallet',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            const Text(
              'Open an approved application and tap "Add to wallet" to keep '
              'its certificate here as a scannable card.',
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}

/// The wallet-style certificate card: title, service, the verification QR on a
/// white quiet-zone backing, the issue date, and a caller-supplied action row.
/// Shared by the application detail view and the Wallet tab so a card looks the
/// same wherever it appears.
class CertificateCard extends StatelessWidget {
  const CertificateCard({super.key, required this.item, this.actions = const []});

  final WalletItem item;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Card(
      elevation: 0,
      color: scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
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
            Text(
              item.serviceName,
              style: Theme.of(context)
                  .textTheme
                  .bodyMedium
                  ?.copyWith(color: scheme.onPrimaryContainer),
            ),
            const SizedBox(height: 16),
            Center(
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: QrImageView(
                  data: item.qrPayload,
                  version: QrVersions.auto,
                  size: 180,
                  gapless: false,
                ),
              ),
            ),
            const SizedBox(height: 12),
            Text(
              'Issued ${_fmtDate(item.issuedAt)} · present this code for '
              'verification.',
              textAlign: TextAlign.center,
              style: Theme.of(context)
                  .textTheme
                  .bodySmall
                  ?.copyWith(color: scheme.onPrimaryContainer),
            ),
            if (actions.isNotEmpty) ...[
              const SizedBox(height: 12),
              Wrap(
                alignment: WrapAlignment.center,
                spacing: 8,
                runSpacing: 8,
                children: actions,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

const _months = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', //
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

String _fmtDate(DateTime dt) {
  final d = dt.toLocal();
  return '${d.day} ${_months[d.month - 1]} ${d.year}';
}
