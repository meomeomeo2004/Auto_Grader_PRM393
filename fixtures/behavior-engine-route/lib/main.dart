import 'package:flutter/material.dart';

void main() => runApp(const RouteFixtureApp());

class RouteFixtureApp extends StatefulWidget {
  const RouteFixtureApp({super.key});

  @override
  State<RouteFixtureApp> createState() => _RouteFixtureAppState();
}

class _RouteFixtureAppState extends State<RouteFixtureApp> {
  final _delegate = FixtureRouterDelegate();
  final _parser = FixtureRouteParser();

  @override
  Widget build(BuildContext context) => MaterialApp.router(
        routeInformationParser: _parser,
        routerDelegate: _delegate,
      );
}

class FixtureRouteParser extends RouteInformationParser<String> {
  @override
  Future<String> parseRouteInformation(RouteInformation routeInformation) async =>
      routeInformation.uri.toString();

  @override
  RouteInformation restoreRouteInformation(String configuration) =>
      RouteInformation(uri: Uri.parse(configuration));
}

class FixtureRouterDelegate extends RouterDelegate<String>
    with ChangeNotifier, PopNavigatorRouterDelegateMixin<String> {
  @override
  final navigatorKey = GlobalKey<NavigatorState>();

  String _route = '/';

  @override
  String get currentConfiguration => _route;

  @override
  Future<void> setNewRoutePath(String configuration) async {
    _route = configuration;
    notifyListeners();
  }

  @override
  Widget build(BuildContext context) => Navigator(
        key: navigatorKey,
        pages: <Page<void>>[
          MaterialPage<void>(
            key: ValueKey<String>(_route),
            child: Scaffold(
              body: Center(
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Text(_route),
                    Row(
                      mainAxisSize: MainAxisSize.min,
                      children: <Widget>[
                        Semantics(identifier: 'layout.a', child: const Text('A')),
                        const SizedBox(width: 24),
                        Semantics(identifier: 'layout.b', child: const Text('B')),
                      ],
                    ),
                  ],
                ),
              ),
            ),
          ),
        ],
        onDidRemovePage: (Page<void> page) {},
      );
}
