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
import 'dart:convert';
import 'dart:io';

const String kEngineVersion = 'STATIC_V1-1.0.0';

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

  final cuaToi = <String, Map<String, dynamic>>{};
  matrix.forEach((id, v) {
    final meta = _asMap(v);
    if ((meta['runner'] ?? '').toString() == 'STATIC_ANALYSIS') cuaToi[id] = meta;
  });

  // Không có tiêu chí tĩnh nào thì đừng chạy dart analyze cho tốn 45 giây.
  if (cuaToi.isEmpty) {
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

  // ── Chạy dart analyze ─────────────────────────────────────────────────────
  final dir = Directory(sourceDir);
  if (!dir.existsSync()) {
    // Thiếu mã nguồn là sự cố MÔI TRƯỜNG (giải nén hỏng, mount sai), không phải
    // lỗi sinh viên — báo not_run để bộ hợp nhất loại khỏi mẫu số.
    _ghiChuaChay(fragmentPath, cuaToi, 'Không tìm thấy thư mục mã nguồn: $sourceDir');
    stderr.writeln('Không tìm thấy thư mục mã nguồn: $sourceDir');
    exit(0);
  }

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
    _ghiChuaChay(fragmentPath, cuaToi, 'Không chạy được dart analyze: $e');
    stderr.writeln('Không chạy được dart analyze: $e');
    exit(0);
  }

  if (quaGio) {
    _ghiChuaChay(fragmentPath, cuaToi,
        'dart analyze quá ${timeout.inSeconds} giây, đã dừng.');
    stderr.writeln('dart analyze quá giờ.');
    exit(0);
  }

  // MÃ THOÁT CỦA dart analyze LÀ THEO MỨC NGHIÊM TRỌNG, KHÔNG PHẢI THÀNH/BẠI:
  // 0 sạch · 1 có info · 2 có warning · 3 có error. Đo thật trên Dart 3.12: một dự án
  // chưa pub get trả 3 kèm stderr RỖNG và stdout đầy chẩn đoán. Nên KHÔNG được coi
  // mã ≠ 0 là công cụ hỏng — làm vậy là quy mọi bài lỗi biên dịch thành sự cố hệ thống.
  // Hỏng thật thì stdout rỗng mà stderr có chữ.
  final so = kq.stdout.toString();
  final se = kq.stderr.toString();
  if (kq.exitCode > 3 || (so.trim().isEmpty && se.trim().isNotEmpty)) {
    _ghiChuaChay(fragmentPath, cuaToi,
        'dart analyze lỗi công cụ (mã ${kq.exitCode}).');
    stderr.writeln(se);
    exit(0);
  }

  final chanDoan = _parse(so);

  // Có lỗi BIÊN DỊCH thì bộ phân tích dừng suy luận ngữ nghĩa, và luật lint gần như
  // không còn phát ra được — bài không biên dịch sẽ "sạch lint" một cách giả tạo.
  // Không được cho đạt vống lên: báo chưa chạy, nhưng ghi rõ nguồn là BÀI LÀM để bộ
  // hợp nhất GIỮ trong mẫu số (khác với sự cố môi trường thì loại khỏi mẫu số).
  final loiBienDich = chanDoan.where((d) => d.severity == 'ERROR').toList();
  if (loiBienDich.isNotEmpty) {
    final d = loiBienDich.first;
    final them = loiBienDich.length > 1 ? ' và ${loiBienDich.length - 1} lỗi nữa' : '';
    final results = <String, dynamic>{};
    for (final id in cuaToi.keys) {
      results[id] = <String, dynamic>{
        'passed': false,
        'executed': false,
        'actual_source': 'static_rule',
        'actual': 'Mã nguồn có lỗi biên dịch nên chưa phân tích tĩnh được: '
            '${_duongDanNgan(d.file, sourceDir)} dòng ${d.line}$them.',
        'not_run_origin': 'STUDENT',
      };
    }
    _ghi(fragmentPath, <String, dynamic>{
      'stage': 'STATIC_ANALYSIS',
      'engine_version': kEngineVersion,
      'executed': false,
      'skip_reason': 'COMPILE_ERRORS',
      'analyzer_exit_code': kq.exitCode,
      'diagnostic_count': chanDoan.length,
      'compile_error_count': loiBienDich.length,
      'results': results,
    });
    stdout.writeln('Tầng tĩnh: bỏ qua vì mã nguồn có ${loiBienDich.length} lỗi biên dịch.');
    exit(0);
  }

  // ── Chấm từng tiêu chí tĩnh ───────────────────────────────────────────────
  final results = <String, dynamic>{};
  cuaToi.forEach((id, meta) {
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

  _ghi(fragmentPath, <String, dynamic>{
    'stage': 'STATIC_ANALYSIS',
    'engine_version': kEngineVersion,
    'executed': true,
    'analyzer_exit_code': kq.exitCode,
    'diagnostic_count': chanDoan.length,
    'results': results,
  });

  final truot = results.values.where((r) => _asMap(r)['passed'] != true).length;
  stdout.writeln('Tầng tĩnh: ${results.length} tiêu chí, $truot không đạt, '
      '${chanDoan.length} chẩn đoán.');
  exit(0);
}

/// Mọi tiêu chí của tầng này chưa chạy được vì sự cố MÔI TRƯỜNG.
/// `not_run_origin: SYSTEM` là tín hiệu để bộ hợp nhất LOẠI khỏi mẫu số tính điểm.
void _ghiChuaChay(String path, Map<String, Map<String, dynamic>> cuaToi, String vi) {
  final results = <String, dynamic>{};
  for (final id in cuaToi.keys) {
    results[id] = <String, dynamic>{
      'passed': false,
      'executed': false,
      'actual_source': 'static_rule',
      'actual': vi,
      'not_run_origin': 'SYSTEM',
    };
  }
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
