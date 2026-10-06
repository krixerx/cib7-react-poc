import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import 'applications.dart';
import 'auth/auth_service.dart';
import 'pack.dart';
import 'wallet.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // The service pack's name, colours, logo and labels, before the first frame
  // so the app never shows the core look first. Never throws.
  Pack.current = await Pack.load();
  final auth = AuthService();
  // Completes a returning login redirect or restores a saved session before
  // the first frame, so we don't briefly flash the login screen.
  await auth.init();
  final wallet = WalletStore();
  // Login is a full-page redirect, so by here the session is settled: load the
  // signed-in user's saved cards before the first frame.
  if (auth.userId != null) wallet.load(auth.userId!);
  runApp(ApplicantApp(auth: auth, wallet: wallet));
}

class ApplicantApp extends StatelessWidget {
  const ApplicantApp({super.key, required this.auth, required this.wallet});

  final AuthService auth;
  final WalletStore wallet;

  @override
  Widget build(BuildContext context) {
    final pack = Pack.current;
    return MaterialApp(
      title: pack.name,
      debugShowCheckedModeBanner: false,
      // Seeded from the pack's primary colour per scheme, and following the
      // system's light or dark setting, as the SPA does.
      theme: ThemeData(colorSchemeSeed: pack.primaryLight, useMaterial3: true),
      darkTheme: ThemeData(
        colorSchemeSeed: pack.primaryDark,
        brightness: Brightness.dark,
        useMaterial3: true,
      ),
      themeMode: ThemeMode.system,
      // Rebuild the gate whenever auth state changes (login/logout/refresh).
      home: ListenableBuilder(
        listenable: auth,
        builder: (context, _) => auth.isAuthenticated
            ? HomeScreen(auth: auth, wallet: wallet)
            : LoginScreen(auth: auth),
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
              const BrandLogo(size: 64),
              const SizedBox(height: 16),
              Text(
                Pack.current.name,
                style: Theme.of(context).textTheme.headlineSmall,
              ),
              Text(
                Pack.current.sub,
                style: Theme.of(context).textTheme.bodySmall,
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
  const HomeScreen({super.key, required this.auth, required this.wallet});

  final AuthService auth;
  final WalletStore wallet;

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
          MyApplicationsScreen(
            key: _applicationsKey,
            auth: widget.auth,
            walletStore: widget.wallet,
          ),
          WalletScreen(store: widget.wallet, auth: widget.auth),
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

/// The pack's logo for the current brightness, or the core icon when the pack
/// has none or it fails to load.
class BrandLogo extends StatelessWidget {
  const BrandLogo({super.key, required this.size});

  final double size;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final url = dark ? Pack.current.logoDark : Pack.current.logoLight;
    final fallback = Icon(Icons.account_balance_outlined, size: size);
    if (url == null) return fallback;
    final src = url.toString();
    return src.endsWith('.svg')
        ? SvgPicture.network(src, height: size, placeholderBuilder: (_) => SizedBox(height: size))
        : Image.network(src, height: size, errorBuilder: (_, __, ___) => fallback);
  }
}
