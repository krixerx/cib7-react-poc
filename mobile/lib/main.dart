import 'package:flutter/material.dart';

import 'api.dart';
import 'auth/auth_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final auth = AuthService();
  // Completes a returning login redirect or restores a saved session before
  // the first frame, so we don't briefly flash the login screen.
  await auth.init();
  runApp(ApplicantApp(auth: auth));
}

class ApplicantApp extends StatelessWidget {
  const ApplicantApp({super.key, required this.auth});

  final AuthService auth;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ROP Applicant (POC)',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorSchemeSeed: const Color(0xFF1F6F43),
        useMaterial3: true,
      ),
      // Rebuild the gate whenever auth state changes (login/logout/refresh).
      home: ListenableBuilder(
        listenable: auth,
        builder: (context, _) =>
            auth.isAuthenticated ? HomeScreen(auth: auth) : LoginScreen(auth: auth),
      ),
    );
  }
}

class LoginScreen extends StatelessWidget {
  const LoginScreen({super.key, required this.auth});

  final AuthService auth;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.local_police_outlined, size: 64),
              const SizedBox(height: 16),
              Text(
                'ROP Applicant',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              const SizedBox(height: 8),
              Text(
                'Sign in to view your applications and certificates.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
              const SizedBox(height: 24),
              FilledButton.icon(
                onPressed: auth.login,
                icon: const Icon(Icons.login),
                label: const Text('Sign in'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Authenticated home. For this step it shows the service list (now sending the
/// bearer token) plus who is signed in and a logout action — proving the OIDC
/// round-trip. My-applications, certificates and the wallet land next.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.auth});

  final AuthService auth;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  late final EngineClient _client =
      EngineClient(tokenProvider: widget.auth.token);
  late Future<List<ServiceDefinition>> _services;

  @override
  void initState() {
    super.initState();
    _services = _client.listServices();
  }

  void _reload() => setState(() => _services = _client.listServices());

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ROP Services'),
        actions: [
          if (widget.auth.username != null)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(widget.auth.username!),
              ),
            ),
          IconButton(
            onPressed: _reload,
            icon: const Icon(Icons.refresh),
            tooltip: 'Reload',
          ),
          IconButton(
            onPressed: widget.auth.logout,
            icon: const Icon(Icons.logout),
            tooltip: 'Sign out',
          ),
        ],
      ),
      body: FutureBuilder<List<ServiceDefinition>>(
        future: _services,
        builder: (context, snapshot) {
          if (snapshot.connectionState != ConnectionState.done) {
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return _ErrorView(message: '${snapshot.error}', onRetry: _reload);
          }
          final services = snapshot.data ?? const <ServiceDefinition>[];
          if (services.isEmpty) {
            return const Center(child: Text('No services available.'));
          }
          return ListView.separated(
            itemCount: services.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (context, i) {
              final s = services[i];
              return ListTile(
                leading: const Icon(Icons.assignment_outlined),
                title: Text(s.name),
                subtitle: Text('${s.key} · v${s.version}'),
                trailing: const Icon(Icons.chevron_right),
                onTap: () {}, // start-service flow arrives in a later step
              );
            },
          );
        },
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
              'Could not load services',
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
