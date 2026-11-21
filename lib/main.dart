import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  runApp(const PatcherApp());
}

const MethodChannel _channel = MethodChannel('com.mmc.patcher/launcher');

class PatcherApp extends StatelessWidget {
  const PatcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MMC Patcher',
      theme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorSchemeSeed: Colors.red,
      ),
      home: const PatcherHome(),
    );
  }
}

class PatcherHome extends StatefulWidget {
  const PatcherHome({super.key});

  @override
  State<PatcherHome> createState() => _PatcherHomeState();
}

class _PatcherHomeState extends State<PatcherHome> {
  String _status = 'Tap Launch to open the floating menu.';

  Future<void> _start() async {
    String? result;
    try {
      result = await _channel.invokeMethod<String>('start');
    } on PlatformException catch (e) {
      result = 'error: ${e.message}';
    }
    if (!mounted) return;
    setState(() {
      _status = switch (result) {
        'permission' => 'Allow "Display over other apps", then tap Launch again.',
        'started' => 'Floating menu is up. Tap the MMC icon over the game.',
        'not_installed' => 'Mini Militia is not installed.',
        _ => 'Unknown result: $result',
      };
    });
  }

  Future<void> _copyLogs() async {
    String? result;
    try {
      result = await _channel.invokeMethod<String>('copyLogs');
    } on PlatformException catch (e) {
      result = 'error: ${e.message}';
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(result == 'copied' ? 'Logs copied to clipboard' : 'Copy failed: $result')),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('MMC Patcher')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 88,
                height: 88,
                decoration: BoxDecoration(
                  color: const Color(0xFF1C1C20),
                  borderRadius: BorderRadius.circular(28),
                ),
                alignment: Alignment.center,
                child: const Text(
                  'MMC',
                  style: TextStyle(color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold),
                ),
              ),
              const SizedBox(height: 28),
              FilledButton.icon(
                onPressed: _start,
                icon: const Icon(Icons.play_arrow),
                label: const Text('Launch'),
              ),
              const SizedBox(height: 10),
              OutlinedButton.icon(
                onPressed: _copyLogs,
                icon: const Icon(Icons.copy),
                label: const Text('Copy logs'),
              ),
              const SizedBox(height: 20),
              Text(
                _status,
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
