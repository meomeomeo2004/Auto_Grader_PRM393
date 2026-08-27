// Bộ chấm TẦNG TĨNH của pipeline container (tầng 1 trong run_grader.sh).
//
// Hợp đồng với bộ điều phối — do run_grader.sh đặt ra, không được đổi ở đây:
//   GRADER_SOURCE_DIR               thư mục mã nguồn sinh viên (mặc định /app/lib)
//   GRADER_FRAGMENT_OUTPUT          nơi ghi mảnh kết quả JSON
//   GRADER_ANALYSIS_TIMEOUT_SECONDS trần thời gian cho dart analyze (mặc định 45)
//
// LUẬT THOÁT: lint không đạt VẪN exit 0 — đó là kết quả chấm hợp lệ, không phải sự cố.
// Chỉ exit khác 0 khi công cụ hỏng hoặc quá giờ, để bộ hợp nhất phân loại được là lỗi
// HỆ THỐNG chứ không quy cho sinh viên.
//
// Tầng này KHÔNG tự sinh tiêu chí. Nó đọc kho tiêu chí đã khai trước và chỉ điền kết quả
// cho những mã khai `runner: STATIC_ANALYSIS` — xem S-01, luật chủ quyền theo tầng.
//
// HAI LOẠI LUẬT TĨNH:
//   · mã lint (empty_catches, ...)      — cần dart analyze, đòi bài biên dịch được;
//   · `static_rule: "source_pattern"`   — soi CÂY FILE + NỘI DUNG nguồn theo
//     `static_config` (glob đường dẫn, regex nội dung). Loại này chấm kiến trúc
//     (tách Model/View/tầng dữ liệu, dùng Riverpod, ...) và CHẠY ĐƯỢC KỂ CẢ KHI
//     BÀI KHÔNG BIÊN DỊCH — cấu trúc thư mục vẫn nhìn thấy được bằng mắt thường,
//     máy cũng phải thấy được như vậy.
import 'dart:convert';
import 'dart:io';

const String kEngineVersion = 'STATIC_V1-1.1.0';
const String kSourcePattern = 'source_pattern';

/// Một chẩn đoán do `dart analyze --format=machine` in ra.
/// Định dạng: SEVERITY|TYPE|CODE|FILE|LINE|COL|LENGTH|MESSAGE
class _ChanDoan {
  _ChanDoan(this.severity, this.code, this.file, this.line, this.message);
  final String severity;
  final String code;   // ví dụ: empty_catches, unused_import
  final String file;
  final int line;
  final String message;
}

String _env(String k, String mac) {
  final v = Platform.environment[k];
  return (v == null || v.trim().isEmpty) ? mac : v.trim();
}

Map<String, dynamic> _asMap(Object? v) =>
    v is Map ? v.map((k, val) => MapEntry(k.toString(), val)) : <String, dynamic>{};

List<Object?> _asList(Object? v) => v is List ? List<Object?>.from(v) : <Object?>[];

/// Gỡ escape của định dạng machine: `\|` `\\` `\n` xuất hiện trong MESSAGE.
String _unescape(String s) => s
    .replaceAll(r'\|', '|')
    .replaceAll(r'\n', '\n')
    .replaceAll(r'\\', r'\');

List<_ChanDoan> _parse(String stdout) {
  final out = <_ChanDoan>[];
  for (final raw in const LineSplitter().convert(stdout)) {
    final line = raw.trim();
    if (line.isEmpty) continue;
    // Tách thủ công theo `|` KHÔNG bị escape, vì MESSAGE có thể chứa `\|`.
    final parts = <String>[];
    final buf = StringBuffer();
    for (var i = 0; i < line.length; i++) {
      final c = line[i];
      if (c == r'\' && i + 1 < line.length) {
        buf.write(c);
        buf.write(line[i + 1]);
        i++;
      } else if (c == '|') {
        parts.add(buf.toString());
        buf.clear();
      } else {
        buf.write(c);
      }
    }
    parts.add(buf.toString());
    if (parts.length < 8) continue;   // dòng tóm tắt của công cụ, bỏ qua
    out.add(_ChanDoan(
      parts[0].toUpperCase(),
      parts[2].trim(),
      _unescape(parts[3]),
      int.tryParse(parts[4]) ?? 0,
      _unescape(parts.sublist(7).join('|')).trim(),
    ));
  }
  return out;
}

/// Rút gọn đường dẫn tuyệt đối trong container về dạng người đọc được: `lib/data/x.dart`.
String _duongDanNgan(String file, String sourceDir) {
  var f = file.replaceAll(r'\', '/');
  final root = sourceDir.replaceAll(r'\', '/');
  if (f.startsWith(root)) {
    f = f.substring(root.length);
    if (f.startsWith('/')) f = f.substring(1);
    f = 'lib/$f';
  }
  return f;
}

// ═══════════════════ LUẬT source_pattern ═══════════════════

/// Glob → RegExp. `**` khớp cả `/`, `*` khớp trong một cấp, `?` một ký tự.
/// So trên đường dẫn tương đối dạng `lib/models/expense.dart` (luôn dùng `/`).
RegExp _globRe(String glob) {
  final sb = StringBuffer('^');
  final g = glob.replaceAll(r'\', '/');
  for (var i = 0; i < g.length; i++) {
    final c = g[i];
    if (c == '*') {
      if (i + 1 < g.length && g[i + 1] == '*') {
        sb.write('.*');
        i++;
      } else {
        sb.write('[^/]*');
      }
    } else if (c == '?') {
      sb.write('[^/]');
    } else if (r'\^$.|+()[]{}'.contains(c)) {
      sb.write('\\$c');
    } else {
      sb.write(c);
    }
  }
  sb.write(r'$');
  return RegExp(sb.toString());
}

/// Một file nguồn đã nạp sẵn đường dẫn tương đối; nội dung đọc LƯỜI vì đa số
/// yêu cầu chỉ cần đường dẫn (kiểm cấu trúc thư mục), không cần mở file.
class _FileNguon {
  _FileNguon(this.relPath, this.file);
  final String relPath; // ví dụ: lib/models/expense.dart
  final File file;
  String? _noiDung;
  bool _docLoi = false;

  String? noiDung() {
    if (_docLoi) return null;
    if (_noiDung != null) return _noiDung;
    try {
      _noiDung = file.readAsStringSync();
      return _noiDung;
    } catch (_) {
      _docLoi = true; // file nhị phân/hỏng mã hoá — coi như không khớp nội dung
      return null;
    }
  }
}

List<_FileNguon> _quetNguon(String sourceDir) {
  final root = Directory(sourceDir);
  final out = <_FileNguon>[];
  if (!root.existsSync()) return out;
  final rootPath = root.absolute.path.replaceAll(r'\', '/');
  for (final entity in root.listSync(recursive: true, followLinks: false)) {
    if (entity is! File) continue;
    var rel = entity.absolute.path.replaceAll(r'\', '/');
    if (rel.startsWith(rootPath)) rel = rel.substring(rootPath.length);
    if (rel.startsWith('/')) rel = rel.substring(1);
    out.add(_FileNguon('lib/$rel', entity));
  }
  out.sort((a, b) => a.relPath.compareTo(b.relPath));
  return out;
}

/// Kết quả một YÊU CẦU trong static_config: đạt/không + câu mô tả tiếng Việt.
class _KqYeuCau {
  _KqYeuCau(this.dat, this.moTa, this.viDu);
  final bool dat;
  final String moTa;
  final List<String> viDu; // đường dẫn minh chứng (khớp hoặc vi phạm)
}

_KqYeuCau _khoRong(String vi) => _KqYeuCau(false, vi, const <String>[]);

/// Chấm một yêu cầu: đếm file khớp `paths` (và `contains` nếu có), so với min/max.
_KqYeuCau _chamYeuCau(Map<String, dynamic> yc, List<_FileNguon> nguon) {
  final label = (yc['label'] ?? '').toString().trim();
  final paths = _asList(yc['paths']).map((p) => p.toString()).where((p) => p.isNotEmpty).toList();
  if (paths.isEmpty) return _khoRong('Yêu cầu "$label" không khai mẫu đường dẫn (paths).');

  List<RegExp> res;
  try {
    res = paths.map(_globRe).toList();
  } catch (e) {
    return _khoRong('Mẫu đường dẫn của "$label" không hợp lệ: $e');
  }

  RegExp? noiDungRe;
  final contains = (yc['contains'] ?? '').toString();
  if (contains.isNotEmpty) {
    try {
      noiDungRe = RegExp(contains, multiLine: true);
    } catch (e) {
      return _khoRong('Regex nội dung của "$label" không hợp lệ: $e');
    }
  }

  final khop = <String>[];
  for (final f in nguon) {
    if (!res.any((re) => re.hasMatch(f.relPath))) continue;
    if (noiDungRe != null) {
      final nd = f.noiDung();
      if (nd == null || !noiDungRe.hasMatch(nd)) continue;
    }
    khop.add(f.relPath);
  }

  final maxRaw = yc['max'];
  final int? max = (maxRaw is num) ? maxRaw.toInt() : null;
  // Luật CẤM (chỉ khai max) thì min mặc định là 0 — "không có cái nào" là ĐẠT,
  // không phải "thiếu file". min khai tường minh vẫn được tôn trọng.
  final min = (yc['min'] is num) ? (yc['min'] as num).toInt() : (max == null ? 1 : 0);
  final viDu = khop.take(3).toList();
  final ten = label.isEmpty ? paths.first : label;

  if (max != null && khop.length > max) {
    final gioiHan = max == 0 ? 'bị cấm' : 'tối đa $max';
    return _KqYeuCau(false,
        '"$ten" $gioiHan nhưng tìm thấy ${khop.length} file (${viDu.join(', ')}).', viDu);
  }
  if (khop.length < min) {
    final dieuKien = noiDungRe == null ? '' : ' có nội dung khớp yêu cầu';
    return _KqYeuCau(false,
        'Không thấy file nào$dieuKien cho "$ten" (mẫu: ${paths.join(', ')}).'
        '${khop.isEmpty ? '' : ' Mới có ${khop.length}/$min.'}',
        viDu);
  }
  return _KqYeuCau(true, '"$ten": ${khop.length} file (${viDu.join(', ')}).', viDu);
}

/// Chấm một tiêu chí source_pattern. `require` = TẤT CẢ phải đạt;
/// `any_of` = danh sách nhánh, đạt khi MỘT nhánh đạt trọn (Riverpod HOẶC Provider...).
Map<String, dynamic> _chamSourcePattern(Map<String, dynamic> meta, List<_FileNguon> nguon) {
  final config = _asMap(meta['static_config']);
  final require = _asList(config['require']).map(_asMap).toList();
  final anyOf = _asList(config['any_of'])
      .map((nhanh) => _asList(nhanh).map(_asMap).toList())
      .where((nhanh) => nhanh.isNotEmpty)
      .toList();
  if (require.isEmpty && anyOf.isEmpty) {
    // Tiêu chí khai thiếu cấu hình là lỗi của ĐỀ, không phải của bài.
    return <String, dynamic>{
      'passed': false,
      'executed': false,
      'actual_source': 'static_rule',
      'actual': 'Tiêu chí source_pattern chưa khai require/any_of nên chưa kiểm được.',
      'not_run_origin': 'TESTCASE',
    };
  }

  final datList = <String>[];
  final truotList = <String>[];
  for (final yc in require) {
    final kq = _chamYeuCau(yc, nguon);
    (kq.dat ? datList : truotList).add(kq.moTa);
  }

  var nhanhDat = anyOf.isEmpty;
  final nhanhTruot = <String>[];
  for (final nhanh in anyOf) {
    final kqNhanh = nhanh.map((yc) => _chamYeuCau(yc, nguon)).toList();
    if (kqNhanh.every((kq) => kq.dat)) {
      nhanhDat = true;
      datList.addAll(kqNhanh.map((kq) => kq.moTa));
      break;
    }
    nhanhTruot.add(kqNhanh.firstWhere((kq) => !kq.dat).moTa);
  }
  if (!nhanhDat) {
    truotList.add(nhanhTruot.length == 1
        ? nhanhTruot.first
        : 'Không nhánh nào đạt: ${nhanhTruot.join(' / ')}');
  }

  if (truotList.isEmpty) {
    return <String, dynamic>{
      'passed': true,
      'executed': true,
      'actual_source': 'static_rule',
      'actual': datList.join(' '),
    };
  }
  return <String, dynamic>{
    'passed': false,
    'executed': true,
    'actual_source': 'static_rule',
    'actual': truotList.first,
    'violations': truotList.map((v) => <String, dynamic>{'message': v}).toList(),
  };
}

// ═══════════════════ ĐIỀU PHỐI ═══════════════════

void main() async {
  final sourceDir = _env('GRADER_SOURCE_DIR', '/app/lib');
  final fragmentPath = _env('GRADER_FRAGMENT_OUTPUT', '/tmp/grader-pipeline/static-analysis.json');
  final storePath = _env('GRADER_CRITERIA_STORE', '/app/test/skills_matrix.json');
  final timeout = Duration(
      seconds: int.tryParse(_env('GRADER_ANALYSIS_TIMEOUT_SECONDS', '45')) ?? 45);

  // ── Đọc kho tiêu chí, lấy phần thuộc tầng này ──────────────────────────────
  final store = File(storePath);
  Map<String, dynamic> matrix = <String, dynamic>{};
  if (store.existsSync()) {
    try {
      matrix = _asMap(jsonDecode(store.readAsStringSync()));
    } catch (e) {
      stderr.writeln('Kho tiêu chí hỏng ($storePath): $e');
    }
  } else {
    stderr.writeln('Không thấy kho tiêu chí: $storePath');
  }

  // Tách hai loại luật: pattern chấm được cả bài lỗi biên dịch, lint thì không.
  final luatLint = <String, Map<String, dynamic>>{};
  final luatPattern = <String, Map<String, dynamic>>{};
  matrix.forEach((id, v) {
    final meta = _asMap(v);
    if ((meta['runner'] ?? '').toString() != 'STATIC_ANALYSIS') return;
    if ((meta['static_rule'] ?? '').toString().trim() == kSourcePattern) {
      luatPattern[id] = meta;
    } else {
      luatLint[id] = meta;
    }
  });

  if (luatLint.isEmpty && luatPattern.isEmpty) {
    _ghi(fragmentPath, <String, dynamic>{
      'stage': 'STATIC_ANALYSIS',
      'engine_version': kEngineVersion,
      'executed': false,
      'skip_reason': 'NO_STATIC_CRITERIA',
      'results': <String, dynamic>{},
    });
    stdout.writeln('Kho tiêu chí không khai mã nào cho tầng tĩnh — bỏ qua.');
    exit(0);
  }

  final dir = Directory(sourceDir);
  if (!dir.existsSync()) {
    // Thiếu mã nguồn là sự cố MÔI TRƯỜNG (giải nén hỏng, mount sai), không phải
    // lỗi sinh viên — báo not_run để bộ hợp nhất loại khỏi mẫu số.
    final tatCa = <String, Map<String, dynamic>>{...luatLint, ...luatPattern};
    _ghiChuaChay(fragmentPath, tatCa, 'Không tìm thấy thư mục mã nguồn: $sourceDir');
    stderr.writeln('Không tìm thấy thư mục mã nguồn: $sourceDir');
    exit(0);
  }

  final results = <String, dynamic>{};

  // ── Luật source_pattern: chấm TRƯỚC, không phụ thuộc dart analyze ─────────
  if (luatPattern.isNotEmpty) {
    final nguon = _quetNguon(sourceDir);
    luatPattern.forEach((id, meta) {
      results[id] = _chamSourcePattern(meta, nguon);
    });
  }

  // ── Luật lint: chỉ chạy dart analyze khi thật sự có tiêu chí cần nó ───────
  var chanDoanCount = 0;
  int? analyzerExit;
  String? skipReason;
  if (luatLint.isNotEmpty) {
    ProcessResult? kq;
    var quaGio = false;
    try {
      final p = await Process.start('dart', <String>['analyze', '--format=machine', sourceDir]);
      final so = p.stdout.transform(utf8.decoder).join();
      final se = p.stderr.transform(utf8.decoder).join();
      final code = await p.exitCode.timeout(timeout, onTimeout: () {
        quaGio = true;
        p.kill(ProcessSignal.sigkill);
        return -1;
      });
      kq = ProcessResult(p.pid, code, await so, await se);
    } catch (e) {
      _dienChuaChay(results, luatLint, 'Không chạy được dart analyze: $e', 'SYSTEM');
      stderr.writeln('Không chạy được dart analyze: $e');
      kq = null;
    }

    if (kq != null && quaGio) {
      _dienChuaChay(results, luatLint,
          'dart analyze quá ${timeout.inSeconds} giây, đã dừng.', 'SYSTEM');
      stderr.writeln('dart analyze quá giờ.');
      kq = null;
    }

    if (kq != null) {
      // MÃ THOÁT CỦA dart analyze LÀ THEO MỨC NGHIÊM TRỌNG, KHÔNG PHẢI THÀNH/BẠI:
      // 0 sạch · 1 có info · 2 có warning · 3 có error. Đo thật trên Dart 3.12: một dự án
      // chưa pub get trả 3 kèm stderr RỖNG và stdout đầy chẩn đoán. Nên KHÔNG được coi
      // mã ≠ 0 là công cụ hỏng — làm vậy là quy mọi bài lỗi biên dịch thành sự cố hệ thống.
      // Hỏng thật thì stdout rỗng mà stderr có chữ.
      final so = kq.stdout.toString();
      final se = kq.stderr.toString();
      if (kq.exitCode > 3 || (so.trim().isEmpty && se.trim().isNotEmpty)) {
        _dienChuaChay(results, luatLint,
            'dart analyze lỗi công cụ (mã ${kq.exitCode}).', 'SYSTEM');
        stderr.writeln(se);
      } else {
        analyzerExit = kq.exitCode;
        final chanDoan = _parse(so);
        chanDoanCount = chanDoan.length;

        // Có lỗi BIÊN DỊCH thì bộ phân tích dừng suy luận ngữ nghĩa, và luật lint gần như
        // không còn phát ra được — bài không biên dịch sẽ "sạch lint" một cách giả tạo.
        // Không được cho đạt vống lên: báo chưa chạy, nhưng ghi rõ nguồn là BÀI LÀM để bộ
        // hợp nhất GIỮ trong mẫu số (khác với sự cố môi trường thì loại khỏi mẫu số).
        // Luật source_pattern KHÔNG bị ảnh hưởng — cấu trúc file vẫn kiểm được.
        final loiBienDich = chanDoan.where((d) => d.severity == 'ERROR').toList();
        if (loiBienDich.isNotEmpty) {
          final d = loiBienDich.first;
          final them = loiBienDich.length > 1 ? ' và ${loiBienDich.length - 1} lỗi nữa' : '';
          skipReason = 'COMPILE_ERRORS';
          _dienChuaChay(results, luatLint,
              'Mã nguồn có lỗi biên dịch nên chưa phân tích lint được: '
              '${_duongDanNgan(d.file, sourceDir)} dòng ${d.line}$them.',
              'STUDENT');
          stdout.writeln(
              'Luật lint: bỏ qua vì mã nguồn có ${loiBienDich.length} lỗi biên dịch.');
        } else {
          luatLint.forEach((id, meta) {
            final rule = (meta['static_rule'] ?? '').toString().trim();
            if (rule.isEmpty) {
              // Tiêu chí khai thiếu luật là lỗi của ĐỀ, không phải của bài — không được
              // tính là sinh viên làm sai.
              results[id] = <String, dynamic>{
                'passed': false,
                'executed': false,
                'actual_source': 'static_rule',
                'actual': 'Tiêu chí chưa khai static_rule nên chưa kiểm được.',
                'not_run_origin': 'TESTCASE',
              };
              return;
            }
            // dart analyze in MÃ LUẬT VIẾT HOA (EMPTY_CATCHES) trong khi analysis_options.yaml
            // khai chữ thường (empty_catches). So chữ thường cả hai vế, nếu không thì mọi tiêu
            // chí lint đều "đạt" một cách giả tạo — đúng lỗi đã gặp lúc chạy thử.
            final ruleChuan = rule.toLowerCase();
            final viPham = chanDoan.where((d) => d.code.toLowerCase() == ruleChuan).toList();
            if (viPham.isEmpty) {
              results[id] = <String, dynamic>{
                'passed': true,
                'executed': true,
                'actual_source': 'static_rule',
                'actual': 'Không có vi phạm luật $rule trong mã nguồn.',
              };
            } else {
              final dau = viPham.first;
              final them = viPham.length > 1 ? ' và ${viPham.length - 1} chỗ nữa' : '';
              results[id] = <String, dynamic>{
                'passed': false,
                'executed': true,
                'actual_source': 'static_rule',
                'actual': 'Vi phạm $rule tại ${_duongDanNgan(dau.file, sourceDir)} '
                    'dòng ${dau.line}$them.',
                'violations': viPham
                    .map((d) => <String, dynamic>{
                          'file': _duongDanNgan(d.file, sourceDir),
                          'line': d.line,
                          'severity': d.severity,
                          'message': d.message,
                        })
                    .toList(),
              };
            }
          });
        }
      }
    }
  }

  _ghi(fragmentPath, <String, dynamic>{
    'stage': 'STATIC_ANALYSIS',
    'engine_version': kEngineVersion,
    'executed': true,
    if (analyzerExit != null) 'analyzer_exit_code': analyzerExit,
    if (skipReason != null) 'skip_reason': skipReason,
    'diagnostic_count': chanDoanCount,
    'results': results,
  });

  final truot = results.values.where((r) => _asMap(r)['passed'] != true).length;
  stdout.writeln('Tầng tĩnh: ${results.length} tiêu chí '
      '(${luatPattern.length} pattern, ${luatLint.length} lint), $truot không đạt, '
      '$chanDoanCount chẩn đoán.');
  exit(0);
}

/// Điền "chưa chạy" cho MỘT NHÓM tiêu chí, nêu rõ trách nhiệm thuộc về ai.
void _dienChuaChay(Map<String, dynamic> results,
    Map<String, Map<String, dynamic>> nhom, String vi, String nguon) {
  for (final id in nhom.keys) {
    results[id] = <String, dynamic>{
      'passed': false,
      'executed': false,
      'actual_source': 'static_rule',
      'actual': vi,
      'not_run_origin': nguon,
    };
  }
}

/// Mọi tiêu chí của tầng này chưa chạy được vì sự cố MÔI TRƯỜNG.
/// `not_run_origin: SYSTEM` là tín hiệu để bộ hợp nhất LOẠI khỏi mẫu số tính điểm.
void _ghiChuaChay(String path, Map<String, Map<String, dynamic>> cuaToi, String vi) {
  final results = <String, dynamic>{};
  _dienChuaChay(results, cuaToi, vi, 'SYSTEM');
  _ghi(path, <String, dynamic>{
    'stage': 'STATIC_ANALYSIS',
    'engine_version': kEngineVersion,
    'executed': false,
    'skip_reason': vi,
    'results': results,
  });
}

void _ghi(String path, Map<String, dynamic> data) {
  final f = File(path);
  f.parent.createSync(recursive: true);
  f.writeAsStringSync(const JsonEncoder.withIndent('  ').convert(data));
}
