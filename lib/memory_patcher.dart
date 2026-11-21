import 'dart:io';
import 'dart:typed_data';

/// Mini Militia target.
const String targetPackage = 'com.appsomniacs.mmc';
const String targetLib = 'libcocos2dcpp.so';

/// A single byte patch: overwrite [hex] at lib base + [offset].
final class PatchDef {
  final String group;
  final String lib;
  final int offset;
  final String hex;
  final String note;

  const PatchDef({
    required this.group,
    required this.lib,
    required this.offset,
    required this.hex,
    this.note = '',
  });
}

/// ARM64 patches derived from the function offsets in lgl_project/jni/Main.cpp.
/// `C0 03 5F D6` = `ret`, `00 A6 8E 52` = `mov w0, #30000`.
const List<PatchDef> builtInPatches = [
  PatchDef(group: 'God Mode', lib: targetLib, offset: 0x67cffc, hex: 'C0 03 5F D6', note: 'Humanoid damage -> ret'),
  PatchDef(group: 'God Mode', lib: targetLib, offset: 0x677130, hex: 'C0 03 5F D6', note: 'Hawk damage -> ret'),
  PatchDef(group: 'God Mode', lib: targetLib, offset: 0x95e4c0, hex: 'C0 03 5F D6', note: 'Worm damage -> ret'),
  PatchDef(group: 'God Mode', lib: targetLib, offset: 0x8e2864, hex: 'C0 03 5F D6', note: 'SoldierController damage -> ret'),
  PatchDef(group: 'Infinite Ammo', lib: targetLib, offset: 0x947a20, hex: '00 A6 8E 52 C0 03 5F D6', note: 'getAmmo -> 30000'),
  PatchDef(group: 'Infinite Ammo', lib: targetLib, offset: 0x947954, hex: '00 A6 8E 52 C0 03 5F D6', note: 'getClip -> 30000'),
];

/// Mirrors the injector's getProcessId(): scan /proc for a cmdline match.
int? findPid([String processName = targetPackage]) {
  final proc = Directory('/proc');
  if (!proc.existsSync()) return null;
  for (final entity in proc.listSync()) {
    final name = entity.path.split('/').last;
    if (!RegExp(r'^\d+$').hasMatch(name)) continue;
    try {
      final bytes = File('${entity.path}/cmdline').readAsBytesSync();
      final cmd = String.fromCharCodes(bytes).replaceAll('\u0000', ' ');
      if (cmd.contains(processName)) return int.parse(name);
    } catch (_) {
      // Process disappeared between listing and read; ignore.
    }
  }
  return null;
}

/// Mirrors the maps scan: first mapping whose line contains [libName].
int? findModuleBase(int pid, String libName) {
  try {
    for (final line in File('/proc/$pid/maps').readAsLinesSync()) {
      if (!line.contains(libName)) continue;
      final dash = line.indexOf('-');
      if (dash > 0) return int.parse(line.substring(0, dash), radix: 16);
    }
  } catch (_) {
    // Unreadable maps (permission).
  }
  return null;
}

/// Strip the injector's `h` marker / spaces and turn hex pairs into bytes.
Uint8List hexToBytes(String hex) {
  final clean = hex.replaceAll(RegExp(r'[^0-9a-fA-F]'), '');
  final out = <int>[];
  for (int i = 0; i + 1 < clean.length; i += 2) {
    out.add(int.parse(clean.substring(i, i + 2), radix: 16));
  }
  return Uint8List.fromList(out);
}

/// Direct write to `/proc/<pid>/mem`. Works when patcher owns the process
/// (virtual container) or runs privileged.
bool _writeDirect(int pid, int address, List<int> bytes) {
  RandomAccessFile? f;
  try {
    f = File('/proc/$pid/mem').openSync(mode: FileMode.writeOnly);
    f.setPositionSync(address);
    f.writeFromSync(bytes);
    return true;
  } catch (_) {
    return false;
  } finally {
    try {
      f?.closeSync();
    } catch (_) {}
  }
}

/// Root-shell fallback: printf octal bytes into dd at the virtual address.
bool _writeViaShell(int pid, int address, List<int> bytes) {
  final octal = bytes.map((b) => '\\${b.toRadixString(8).padLeft(3, '0')}').join();
  final cmd = "printf '$octal' | dd of=/proc/$pid/mem bs=1 seek=$address conv=notrunc 2>/dev/null";
  try {
    final r = Process.runSync('su', ['-c', cmd]);
    return r.exitCode == 0;
  } catch (_) {
    return false;
  }
}

bool writeMemory(int pid, int address, List<int> bytes) {
  if (_writeDirect(pid, address, bytes)) return true;
  return _writeViaShell(pid, address, bytes);
}

/// Read original bytes so a toggle-off can restore them.
Uint8List? readMemory(int pid, int address, int length) {
  RandomAccessFile? f;
  try {
    f = File('/proc/$pid/mem').openSync(mode: FileMode.read);
    f.setPositionSync(address);
    final buf = Uint8List(length);
    final got = f.readIntoSync(buf);
    return got == length ? buf : null;
  } catch (_) {
    return null;
  } finally {
    try {
      f?.closeSync();
    } catch (_) {}
  }
}

/// Apply or restore one patch. Returns true on success.
bool applyPatch({
  required int pid,
  required int base,
  required PatchDef patch,
  required bool enable,
  required Map<int, Uint8List> originals,
}) {
  final libBase = patch.lib == targetLib
      ? base
      : findModuleBase(pid, patch.lib);
  if (libBase == null) return false;

  final address = libBase + patch.offset;
  final bytes = hexToBytes(patch.hex);

  if (enable) {
    final original = readMemory(pid, address, bytes.length);
    if (original != null) originals[address] = original;
    return writeMemory(pid, address, bytes);
  } else {
    final original = originals.remove(address);
    if (original == null) return false;
    return writeMemory(pid, address, original);
  }
}
