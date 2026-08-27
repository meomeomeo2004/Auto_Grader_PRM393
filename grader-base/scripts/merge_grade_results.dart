// BỘ HỢP NHẤT — tầng 4 của pipeline container, và là thành phần DUY NHẤT được phép
// in khối GRADE_RESULT (xem run_grader.sh và N1-01-grading-pipeline.md).
//
// Ba việc nó làm, không việc nào làm được ở tầng khác:
//   1. Ghép kết quả ba tầng vào MỘT danh sách tiêu chí, theo kho đã khai trước.
//   2. Chủ quyền theo tầng: kết quả của một mã chỉ được nhận từ đúng tầng khai `runner`.
//   3. Chia mẫu số cho đúng — phần quan trọng nhất, xem CHIA MẪU SỐ bên dưới.
//
// CHIA MẪU SỐ (S-01 mục 6). Tiêu chí chưa chạy được chia theo TRÁCH NHIỆM:
//   · lỗi HỆ THỐNG (tầng chết, quá giờ, container hỏng) → LOẠI khỏi mẫu số;
//     sinh viên không được mất điểm vì thứ máy chưa từng kiểm.
//   · lỗi ĐỀ (khai tiêu chí mà không có tầng chạy nó)   → LOẠI khỏi mẫu số.
//   · lỗi BÀI LÀM (không biên dịch, không khởi động)    → GIỮ trong mẫu số, 0 điểm.
// Phần bị loại được công bố riêng ở `coverage` để người chấm biết mình đang nhìn
// một bài chấm thiếu bao nhiêu phần trăm.
import 'dart:convert';
import 'dart:io';

const String kEngineVersion = 'MERGE_V1-1.0.0';

/// Nguồn gây ra việc một tiêu chí chưa chạy. Chỉ STUDENT mới bị tính vào mẫu số.
const String kSystem = 'SYSTEM';
const String kTestcase = 'TESTCASE';
const String kStudent = 'STUDENT';

String _env(String k, [String mac = '']) {
  final v = Platform.environment[k];
  return (v == null || v.trim().isEmpty) ? mac : v.trim();
}

int _envInt(String k) => int.tryParse(_env(k, '0')) ?? 0;

Map<String, dynamic> _asMap(Object? v) =>
    v is Map ? v.map((k, val) => MapEntry(k.toString(), val)) : <String, dynamic>{};

double _double(Object? v, [double mac = 0]) {
  if (v is num) return v.toDouble();
  return double.tryParse(v?.toString() ?? '') ?? mac;
}

Map<String, dynamic> _docJson(String path) {
  if (path.isEmpty) return <String, dynamic>{};
  final f = File(path);
  if (!f.existsSync()) return <String, dynamic>{};
  try {
    return _asMap(jsonDecode(f.readAsStringSync()));
  } catch (e) {
    stderr.writeln('Mảnh kết quả hỏng ($path): $e');
    return <String, dynamic>{};
  }
}

/// Bóc khối GRADE_RESULT mà tầng hành vi in ra stdout.
Map<String, dynamic> _bocKhoiKetQua(String path) {
  if (path.isEmpty) return <String, dynamic>{};
  final f = File(path);
  if (!f.existsSync()) return <String, dynamic>{};
  final text = f.readAsStringSync();
  const start = '--- GRADE_RESULT_START ---';
  const end = '--- GRADE_RESULT_END ---';
  final i = text.indexOf(start);
  final j = text.indexOf(end, i + start.length);
  if (i < 0 || j < 0) return <String, dynamic>{};
  try {
    return _asMap(jsonDecode(text.substring(i + start.length, j).trim()));
  } catch (e) {
    stderr.writeln('Không đọc được khối GRADE_RESULT của tầng hành vi: $e');
    return <String, dynamic>{};
  }
}

/// Trạng thái một tầng: có chạy không, và nếu không thì vì sao / lỗi của ai.
class _Tang {
  _Tang(this.ten, {required this.chay, required this.viSao, required this.nguon});
  final String ten;
  final bool chay;
  final String viSao;
  final String nguon;
}

void main() {
  // ── Kho tiêu chí: nguồn sự thật về CÓ BAO NHIÊU dòng và mỗi dòng thuộc tầng nào ──
  final storePath = _env('GRADER_CRITERIA_STORE', '/app/test/skills_matrix.json');
  final store = _docJson(storePath);
  if (store.isEmpty) {
    // Không có kho thì không hợp nhất được. Trả nguyên kết quả tầng hành vi để
    // các bộ đề đời cũ (chưa khai kho ba tầng) vẫn chấm được như trước.
    final bh = _bocKhoiKetQua(_env('GRADER_BEHAVIOR_STDOUT'));
    if (bh.isNotEmpty) {
      _inKetQua(bh);
      exit(0);
    }
    stderr.writeln('Không có kho tiêu chí ($storePath) lẫn kết quả tầng hành vi.');
    exit(1);
  }

  // ── Thu kết quả từng tầng ────────────────────────────────────────────────
  final behavior = _bocKhoiKetQua(_env('GRADER_BEHAVIOR_STDOUT'));
  final behaviorCases = <String, Map<String, dynamic>>{};
  for (final c in (behavior['test_cases'] as List? ?? const <dynamic>[])) {
    final m = _asMap(c);
    final id = (m['test_id'] ?? '').toString();
    if (id.isNotEmpty) behaviorCases[id] = m;
  }

  final staticFrag = _docJson(_env('GRADER_STATIC_FRAGMENT'));
  final unitFrag = _docJson(_env('GRADER_UNIT_FRAGMENT'));

  final tang = <String, _Tang>{
    'BEHAVIOR_REPLAY': _Tang('BEHAVIOR_REPLAY',
        chay: behavior.isNotEmpty,
        viSao: behavior.isNotEmpty
            ? ''
            : 'Tầng chấm hành vi không trả kết quả (mã thoát '
                '${_envInt('GRADER_BEHAVIOR_EXIT_CODE')}).',
        nguon: kSystem),
    'STATIC_ANALYSIS': _Tang('STATIC_ANALYSIS',
        chay: staticFrag.isNotEmpty,
        viSao: staticFrag.isNotEmpty ? '' : 'Tầng phân tích tĩnh chưa chạy.',
        nguon: kSystem),
    'UNIT_TEST': _Tang('UNIT_TEST',
        chay: unitFrag.isNotEmpty,
        viSao: unitFrag.isNotEmpty
            ? ''
            : 'Đề chưa cung cấp bộ kiểm thử đơn vị nên tiêu chí này chưa chấm được.',
        // Thiếu runner đơn vị là thiếu sót của ĐỀ, không phải sự cố hạ tầng.
        nguon: kTestcase),
  };

  Map<String, dynamic> _ketQuaCua(String runner, String id) {
    switch (runner) {
      case 'BEHAVIOR_REPLAY':
        final c = behaviorCases[id];
        if (c == null) return <String, dynamic>{};
        return <String, dynamic>{
          'passed': c['status'] == 'passed',
          'executed': c['executed'] == true,
          'actual': c['actual'],
          'actual_source': c['actual_source'],
          'observation': c['observation'],
          'not_run_origin': c['error_origin'],
        };
      case 'STATIC_ANALYSIS':
        return _asMap(_asMap(staticFrag['results'])[id]);
      case 'UNIT_TEST':
        return _asMap(_asMap(unitFrag['results'])[id]);
      default:
        return <String, dynamic>{};
    }
  }

  // ── Ghép: MỖI mã trong kho ra ĐÚNG MỘT dòng, bất kể tầng nào chết ─────────
  final cases = <Map<String, dynamic>>[];
  var passed = 0, failed = 0, notRun = 0;
  var earned = 0.0, mauSo = 0.0, trongSoBiLoai = 0.0;
  final loaiTheoTang = <String, double>{};

  store.forEach((id, raw) {
    final meta = _asMap(raw);
    final runner = (meta['runner'] ?? 'BEHAVIOR_REPLAY').toString();
    final weight = _double(meta['weight'], 1);
    final kq = _ketQuaCua(runner, id);
    final t = tang[runner];

    var status = 'not_run';
    var actual = '';
    String? nguonChuaChay;

    if (kq.isEmpty) {
      // Tầng không trả kết quả cho mã này.
      if (t == null) {
        // Kho khai một tầng không tồn tại trong pipeline → lỗi của đề.
        actual = 'Tiêu chí khai tầng chấm "$runner" không có trong hệ thống.';
        nguonChuaChay = kTestcase;
      } else if (!t.chay) {
        actual = t.viSao;
        nguonChuaChay = t.nguon;
      } else {
        // Tầng chạy nhưng bỏ sót mã này — sai lệch giữa kho và bộ đề.
        actual = 'Tầng ${t.ten} đã chạy nhưng không trả kết quả cho tiêu chí này.';
        nguonChuaChay = kTestcase;
      }
    } else if (kq['passed'] == true) {
      status = 'passed';
      actual = (kq['actual'] ?? 'Đã đáp ứng yêu cầu').toString();
    } else if (kq['executed'] == true) {
      status = 'failed';
      actual = (kq['actual'] ?? '').toString();
    } else {
      actual = (kq['actual'] ?? 'Tiêu chí chưa chạy.').toString();
      // Tầng tự khai nguồn thì tin tầng; không khai thì mặc định là sự cố hệ thống
      // — mặc định phải NGHIÊNG VỀ PHÍA SINH VIÊN, không quy lỗi khi chưa rõ.
      nguonChuaChay = (kq['not_run_origin'] ?? '').toString();
      if (nguonChuaChay.isEmpty || nguonChuaChay == 'UNDETERMINED') {
        nguonChuaChay = kSystem;
      }
    }

    // Chia mẫu số theo trách nhiệm.
    final vaoMauSo = status != 'not_run' || nguonChuaChay == kStudent;
    if (vaoMauSo) {
      mauSo += weight;
    } else {
      trongSoBiLoai += weight;
      loaiTheoTang[runner] = (loaiTheoTang[runner] ?? 0) + weight;
    }
    if (status == 'passed') {
      passed++;
      earned += weight;
    } else if (status == 'failed') {
      failed++;
    } else {
      notRun++;
    }

    cases.add(<String, dynamic>{
      'test_id': id,
      'runner': runner,
      'group_id': meta['group_id'],
      'group_name': meta['group_name'],
      'scenario_code': meta['scenario_code'],
      'name': meta['name'] ?? id,
      'status': status,
      'executed': status != 'not_run',
      'max_score': weight,
      'counted_in_score': vaoMauSo,
      'difficulty': meta['difficulty'] ?? 'intermediate',
      'skill_code': meta['skill_code'],
      // `actual` chỉ có nghĩa khi KHÔNG đạt. Dòng đạt mà vẫn kèm câu "đã đáp ứng yêu cầu"
      // chỉ là tiếng vọng của status, lặp lại ở mọi tiêu chí của mọi bài tốt.
      if (status != 'passed') 'actual': actual,
      // Bốn field dưới đây MÔ TẢ LỖI. Dòng đạt không có lỗi nào để mô tả, nên chúng
      // vắng mặt hẳn thay vì nằm đó với giá trị null ở mọi tiêu chí của mọi bài tốt.
      if (status != 'passed') ...<String, dynamic>{
        // Nguồn của câu `actual`. Có mặt = câu đã được tầng chấm viết sẵn bằng tiếng Việt;
        // backend KHÔNG được đem bộ bóc log flutter chạy lên nó rồi ghi đè bằng câu chung.
        'actual_source': (kq['actual_source'] ?? 'tier_message').toString(),
        'observation': kq['observation'],
        'violations': kq['violations'],
        'error_origin': status == 'failed' ? kStudent : nguonChuaChay,
        'error_stage': runner,
      },
      'requires_manual_review': status == 'not_run' && nguonChuaChay != kStudent,
    });
  });

  // ── Điểm ─────────────────────────────────────────────────────────────────
  final score = mauSo <= 0 ? 0.0 : earned / mauSo * 10;
  final rounded = (score * 10).round() / 10;
  final tongTrongSo = mauSo + trongSoBiLoai;
  final doPhu = tongTrongSo <= 0 ? 0.0 : mauSo / tongTrongSo;
  final doPhuLamTron = (doPhu * 1000).round() / 1000;

  // Bài chấm thiếu thì KHÔNG được công bố như bài chấm đủ — đẩy sang người.
  final canNguoiXem = cases.any((c) => c['requires_manual_review'] == true);
  final loi = <String>[];
  tang.forEach((k, v) {
    if (!v.chay && loaiTheoTang.containsKey(k)) loi.add(v.viSao);
  });

  _inKetQua(<String, dynamic>{
    'mode': 'multi_tier_merge',
    'diem': rounded,
    'soTestPass': passed,
    'soTestFail': failed + notRun,
    'tongSoTest': cases.length,
    'tongSoTieuChi': cases.length,
    'test_cases': cases,
    'chiTiet': cases
        .map((item) => <String, dynamic>{
              'name': item['test_id'],
              'status': item['status'] == 'passed'
                  ? 'PASS'
                  : (item['status'] == 'failed' ? 'FAILED' : 'NOT_RUN'),
              'message': item['actual'],
            })
        .toList(),
    // Công bố riêng phần máy KHÔNG chấm được, để người đọc biết con số điểm kia
    // đang nói về bao nhiêu phần của bài.
    'coverage': <String, dynamic>{
      'scored_weight': mauSo,
      'excluded_weight': trongSoBiLoai,
      'total_declared_weight': tongTrongSo,
      'ratio': doPhuLamTron,
      'excluded_by_runner': loaiTheoTang,
    },
    'grading_result': <String, dynamic>{
      'score': rounded,
      'passed_tests': passed,
      'failed_tests': failed + notRun,
      'total_tests': cases.length,
      'total_scenarios': cases
          .map((item) => item['scenario_code'])
          .where((v) => v != null)
          .toSet()
          .length,
      'total_criteria': cases.length,
      'not_run_tests': notRun,
      'earned_weight': earned,
      // total_weight là MẪU SỐ THẬT đã dùng để tính điểm, không phải tổng khai báo.
      // Giữ đúng bất biến score == earned_weight / total_weight * 10.
      'total_weight': mauSo,
      'declared_weight': tongTrongSo,
      'coverage_ratio': doPhuLamTron,
      'blocked': false,
      'runner_error': loi.isEmpty ? null : loi.join('\n'),
      'diagnostic_code': loi.isEmpty ? null : 'TIER_INCOMPLETE',
      'diagnostic_origin': loi.isEmpty ? null : kSystem,
      'diagnostic_stage': loi.isEmpty ? null : 'RESULT_MERGE',
      'diagnostic_message': loi.isEmpty ? null : loi.join('\n'),
      'requires_manual_review': canNguoiXem,
      'engine_version': kEngineVersion,
    },
  });
}

void _inKetQua(Map<String, dynamic> data) {
  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(data));
  stdout.writeln('--- GRADE_RESULT_END ---');
}
