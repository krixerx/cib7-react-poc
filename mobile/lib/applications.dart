import 'package:flutter/material.dart';

import 'api.dart';
import 'application_detail.dart';
import 'auth/auth_service.dart';
import 'wallet.dart';

/// "My applications" — the applicant's process instances, with a coarse status
/// per row. This is the mobile counterpart of the SPA's `MyProcessesPage`; the
/// next step turns each row into a detail view surfacing the generated
/// certificate (with a QR code) ready to drop into the in-app wallet.
class MyApplicationsScreen extends StatefulWidget {
  const MyApplicationsScreen({
    super.key,
    required this.auth,
    required this.walletStore,
  });

  final AuthService auth;
  final WalletStore walletStore;

  @override
  State<MyApplicationsScreen> createState() => MyApplicationsScreenState();
}

class MyApplicationsScreenState extends State<MyApplicationsScreen> {
  late final EngineClient _client =
      EngineClient(tokenProvider: widget.auth.token);
  late Future<List<Application>> _apps;

  @override
  void initState() {
    super.initState();
    _apps = _load();
  }

  Future<List<Application>> _load() {
    final userId = widget.auth.userId;
    if (userId == null) return Future.value(const []);
    return _client.listMyApplications(userId);
  }

  /// Public so the shell's pull-to-refresh / AppBar refresh can trigger it.
  void reload() => setState(() => _apps = _load());

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<List<Application>>(
      future: _apps,
      builder: (context, snapshot) {
        if (snapshot.connectionState != ConnectionState.done) {
          return const Center(child: CircularProgressIndicator());
        }
        if (snapshot.hasError) {
          return _ErrorView(message: '${snapshot.error}', onRetry: reload);
        }
        final apps = snapshot.data ?? const <Application>[];
        if (apps.isEmpty) {
          return _EmptyView(onRefresh: reload);
        }
        return RefreshIndicator(
          onRefresh: () async => reload(),
          child: ListView.separated(
            itemCount: apps.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, i) => _ApplicationTile(
              app: apps[i],
              auth: widget.auth,
              walletStore: widget.walletStore,
            ),
          ),
        );
      },
    );
  }
}

class _ApplicationTile extends StatelessWidget {
  const _ApplicationTile({
    required this.app,
    required this.auth,
    required this.walletStore,
  });

  final Application app;
  final AuthService auth;
  final WalletStore walletStore;

  @override
  Widget build(BuildContext context) {
    final pi = app.instance;
    final when = pi.isEnded ? pi.endTime! : pi.startTime;
    final whenLabel =
        pi.isEnded ? 'Completed ${_formatDate(when)}' : 'Started ${_formatDate(when)}';
    return ListTile(
      leading: const Icon(Icons.folder_outlined),
      title: Text(app.serviceName),
      subtitle: Text(whenLabel),
      trailing: _StatusChip(status: pi.status),
      onTap: () => Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => ApplicationDetailScreen(
            app: app,
            auth: auth,
            walletStore: walletStore,
          ),
        ),
      ),
    );
  }
}

class _StatusChip extends StatelessWidget {
  const _StatusChip({required this.status});

  final ApplicationStatus status;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final (label, bg, fg) = switch (status) {
      ApplicationStatus.approved => (
          'Approved',
          scheme.primaryContainer,
          scheme.onPrimaryContainer,
        ),
      ApplicationStatus.inProgress => (
          'In progress',
          scheme.secondaryContainer,
          scheme.onSecondaryContainer,
        ),
      ApplicationStatus.ended => (
          'Closed',
          scheme.surfaceContainerHighest,
          scheme.onSurfaceVariant,
        ),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        label,
        style: Theme.of(context)
            .textTheme
            .labelMedium
            ?.copyWith(color: fg, fontWeight: FontWeight.w600),
      ),
    );
  }
}

class _EmptyView extends StatelessWidget {
  const _EmptyView({required this.onRefresh});

  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    // Wrapped in a scroll view so RefreshIndicator-style pull still works and
    // the column can center on tall screens.
    return LayoutBuilder(
      builder: (context, constraints) => RefreshIndicator(
        onRefresh: () async => onRefresh(),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          child: ConstrainedBox(
            constraints: BoxConstraints(minHeight: constraints.maxHeight),
            child: Center(
              child: Padding(
                padding: const EdgeInsets.all(32),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    const Icon(Icons.inbox_outlined, size: 48),
                    const SizedBox(height: 12),
                    Text(
                      'No applications yet',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
                    const SizedBox(height: 8),
                    const Text(
                      'Applications you start in the portal will appear here.',
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
            ),
          ),
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
              'Could not load your applications',
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

const _months = [
  'Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', //
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec',
];

/// Compact `19 Jun 2026` date — local time, no intl dependency.
String _formatDate(DateTime dt) {
  final d = dt.toLocal();
  return '${d.day} ${_months[d.month - 1]} ${d.year}';
}
