import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'memory_patcher.dart';

void main() => runApp(const PatcherApp());

const MethodChannel _launcher = MethodChannel('com.mmc.patcher/launcher');

class PatcherApp extends StatelessWidget {
  const PatcherApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MMC External Patcher',
      theme: ThemeData(useMaterial3: true, colorSchemeSeed: Colors.indigo),
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
  int? _pid;
  int? _base;
  String _status = 'Not attached. Launch the game, then tap Attach.';
  final Map<int, Uint8List> _originals = {};
  final Set<String> _enabled = {};

  final _libController = TextEditingController(text: targetLib);
  final _offsetController = TextEditingController();
  final _hexController = TextEditingController();

  Future<void> _launchGame() async {
    try {
      final ok = await _launcher.invokeMethod<bool>('launchGame') ?? false;
      _say(ok ? 'Launch request sent.' : 'MMC is not installed.');
    } on PlatformException catch (e) {
      _say('Launch failed: ${e.message}');
    }
  }

  void _say(String message) {
    if (!mounted) return;
    setState(() => _status = message);
  }

  void _attach() {
    final pid = findPid();
    if (pid == null) {
      _pid = null;
      _base = null;
      _say('Game process not found. Launch MMC first.');
      return;
    }
    final base = findModuleBase(pid, targetLib);
    if (base == null) {
      _pid = pid;
      _base = null;
      _say('PID $pid found but $targetLib is not mapped yet. Wait for the match to load.');
      return;
    }
    setState(() {
      _pid = pid;
      _base = base;
    });
    _say('Attached: pid=$pid  $targetLib base=0x${base.toRadixString(16)}');
  }

  void _toggle(PatchDef patch, bool value) {
    if (_pid == null || _base == null) {
      _say('Attach first.');
      return;
    }
    final ok = applyPatch(
      pid: _pid!,
      base: _base!,
      patch: patch,
      enable: value,
      originals: _originals,
    );
    if (!ok) {
      _say('Patch failed for ${patch.note}. Check memory access (root/container).');
      return;
    }
    setState(() {
      if (value) {
        _enabled.add(patch.note);
      } else {
        _enabled.remove(patch.note);
      }
    });
    _say('${patch.note}: ${value ? 'ON' : 'OFF'}');
  }

  void _applyCustom() {
    if (_pid == null || _base == null) {
      _say('Attach first.');
      return;
    }
    final offset = int.tryParse(_offsetController.text.trim().replaceAll('0x', ''), radix: 16);
    if (offset == null) {
      _say('Offset must be hex, e.g. 947a20');
      return;
    }
    final patch = PatchDef(
      group: 'Custom',
      lib: _libController.text.trim().isEmpty ? targetLib : _libController.text.trim(),
      offset: offset,
      hex: _hexController.text,
      note: 'custom@${offset.toRadixString(16)}',
    );
    final ok = applyPatch(
      pid: _pid!,
      base: _base!,
      patch: patch,
      enable: true,
      originals: _originals,
    );
    _say(ok ? 'Custom patch applied at 0x${offset.toRadixString(16)}' : 'Custom patch failed.');
  }

  @override
  void dispose() {
    _libController.dispose();
    _offsetController.dispose();
    _hexController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final groups = <String, List<PatchDef>>{};
    for (final p in builtInPatches) {
      groups.putIfAbsent(p.group, () => []).add(p);
    }

    return Scaffold(
      appBar: AppBar(title: const Text('MMC External Patcher')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Text(_status),
            ),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _launchGame,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('Launch Game'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: _attach,
                  icon: const Icon(Icons.link),
                  label: const Text('Attach'),
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          for (final entry in groups.entries)
            Card(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Padding(
                    padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                    child: Text(entry.key, style: Theme.of(context).textTheme.titleMedium),
                  ),
                  for (final p in entry.value)
                    SwitchListTile(
                      title: Text(p.note),
                      subtitle: Text('0x${p.offset.toRadixString(16)}  ${p.hex}'),
                      value: _enabled.contains(p.note),
                      onChanged: (v) => _toggle(p, v),
                    ),
                ],
              ),
            ),
          const SizedBox(height: 20),
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text('Custom patch', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _libController,
                    decoration: const InputDecoration(labelText: 'Library', border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _offsetController,
                    decoration: const InputDecoration(labelText: 'Offset (hex)', border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 8),
                  TextField(
                    controller: _hexController,
                    decoration: const InputDecoration(labelText: 'Bytes (hex)', border: OutlineInputBorder()),
                  ),
                  const SizedBox(height: 12),
                  FilledButton(onPressed: _applyCustom, child: const Text('Apply')),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
