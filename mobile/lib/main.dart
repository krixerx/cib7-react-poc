import 'package:flutter/material.dart';

import 'applications.dart';
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
      title: 'eRegistrations Applicant (POC)',
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
                'eRegistrations',
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

/// Authenticated home: a bottom-nav shell over the applicant's **Applications**
/// and their **Wallet**. Applications is live now; the wallet (certificate QR
/// cards collected from approved applications) lands in a later step.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key, required this.auth});

  final AuthService auth;

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  int _tab = 0;
  final _applicationsKey = GlobalKey<MyApplicationsScreenState>();

  static const _titles = ['Applications', 'Wallet'];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_titles[_tab]),
        actions: [
          if (widget.auth.username != null)
            Center(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 8),
                child: Text(widget.auth.username!),
              ),
            ),
          if (_tab == 0)
            IconButton(
              onPressed: () => _applicationsKey.currentState?.reload(),
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
      body: IndexedStack(
        index: _tab,
        children: [
          MyApplicationsScreen(key: _applicationsKey, auth: widget.auth),
          const _WalletPlaceholder(),
        ],
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) => setState(() => _tab = i),
        destinations: const [
          NavigationDestination(
            icon: Icon(Icons.folder_outlined),
            selectedIcon: Icon(Icons.folder),
            label: 'Applications',
          ),
          NavigationDestination(
            icon: Icon(Icons.account_balance_wallet_outlined),
            selectedIcon: Icon(Icons.account_balance_wallet),
            label: 'Wallet',
          ),
        ],
      ),
    );
  }
}

class _WalletPlaceholder extends StatelessWidget {
  const _WalletPlaceholder();

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
              'Approved certificates you add will appear here as scannable '
              'cards.',
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
