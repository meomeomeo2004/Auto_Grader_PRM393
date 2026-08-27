import 'dart:async';
import 'dart:convert';
import 'dart:io';

const _checkpointMarker = '###RAR_CHECKPOINT###';
const _stageMarker = '###GRADER_STAGE###';
const _engineVersion = 'BEHAVIOR_RAR-1.0.0';

Future<void> main() async {
  final plan = _readObject('test/behavior_plan.json', 'behavior_plan.json');
  final matrix = _readObject('test/skills_matrix.json', 'skills_matrix.json');
  final planCases = _asList(plan['cases']).map(_asMap).toList();
  final scenarioCodes = <String>{
    for (final item in planCases)
      _text(item, 'execution_code', _text(item, 'scenario_code')),
  }..remove('');
  final processTimeout = Duration(
    seconds: _positiveEnvInt('GRADER_BATCH_TIMEOUT_SECONDS', 60),
  );
  final totalTimeout = Duration(
    seconds: _positiveEnvInt('GRADER_TOTAL_TIMEOUT_SECONDS', 210),
  );
  final totalWatch = Stopwatch()..start();
  final results = <String, Map<String, dynamic>>{};
  final runnerErrors = <String>[];

  for (final scenarioCode in scenarioCodes) {
    final remaining = totalTimeout - totalWatch.elapsed;
    if (remaining <= Duration.zero) {
      runnerErrors.add('Hết ngân sách toàn bộ ${totalTimeout.inSeconds}s.');
      break;
    }
    final limit = remaining < processTimeout ? remaining : processTimeout;
    final process = await _runScenario(scenarioCode, limit);
    stdout.write(process.stdout);
    stderr.write(process.stderr);
    results.addAll(_parseCheckpointResults(process.stdout.toString()));

    final expectedIds = planCases
        .where(
          (item) =>
              _text(item, 'execution_code', _text(item, 'scenario_code')) ==
              scenarioCode,
        )
        .map((item) => _text(item, 'test_id'));
    final missing = expectedIds.where((id) => !results.containsKey(id)).toList();
    if (missing.isEmpty) continue;

    final stage = _lastStage(process.stdout.toString());
    final timeout = process.timedOut;
    final message = timeout
        ? 'Scenario $scenarioCode vượt quá ${limit.inSeconds} giây ở ${stage ?? 'UNKNOWN'}.'
        : _shortProcessError(process);
    runnerErrors.add(message);
    // Bài không biên dịch thì flutter test chết TRƯỚC marker STUDENT_* đầu tiên,
    // stage rỗng và origin từng rơi về UNDETERMINED → merger loại khỏi mẫu số →
    // bài thiếu một dấu ; được 10.0 nhờ 4 tiêu chí kiến trúc còn chấm được (đo
    // thật 23/8). Lỗi biên dịch trỏ vào lib/ là lỗi BÀI LÀM: giữ nguyên mẫu số.
    final compileOrigin =
        timeout ? '' : _nguonLoiBienDich('${process.stdout}\n${process.stderr}');
    for (final id in missing) {
      results[id] = <String, dynamic>{
        'passed': false,
        'executed': timeout,
        'message': message,
        'observation': <String, dynamic>{
          'kind': timeout ? 'PROCESS_TIMEOUT' : 'SCENARIO_NOT_RUN',
          'scenario_code': scenarioCode,
          'stage': stage,
          'origin': stage?.startsWith('STUDENT_') == true
              ? 'STUDENT'
              : (compileOrigin.isNotEmpty ? compileOrigin : 'UNDETERMINED'),
        },
      };
    }
  }

  totalWatch.stop();
  final output = _assemble(matrix, results, runnerErrors);
  stdout.writeln('--- GRADE_RESULT_START ---');
  stdout.writeln(jsonEncode(output));
  stdout.writeln('--- GRADE_RESULT_END ---');
}

Future<_ProcessResult> _runScenario(String scenarioCode, Duration timeout) async {
  final process = await Process.start(
    'flutter',
    const <String>[
      'test',
      '--no-pub',
      '--machine',
      '--concurrency=1',
      'test/exam_test.dart',
    ],
    environment: <String, String>{
      ...Platform.environment,
      'GRADER_SCENARIO_CODE': scenarioCode,
    },
  );
  final stdoutFuture = process.stdout.transform(utf8.decoder).join();
  final stderrFuture = process.stderr.transform(utf8.decoder).join();
  var timedOut = false;
  final exitCode = await process.exitCode.timeout(timeout, onTimeout: () {
    timedOut = true;
    process.kill(ProcessSignal.sigkill);
    return -124;
  });
  return _ProcessResult(
    exitCode,
    await stdoutFuture,
    await stderrFuture,
    timedOut,
  );
}

Map<String, Map<String, dynamic>> _parseCheckpointResults(String output) {
  final results = <String, Map<String, dynamic>>{};
  for (final line in output.split('\n')) {
    String message = line;
    try {
      final event = jsonDecode(line);
      if (event is Map && event['type'] == 'print') {
        message = event['message']?.toString() ?? '';
      }
    } catch (_) {
      // Cho phép runner chạy không dùng --machine trong lúc debug.
    }
    final markerAt = message.indexOf(_checkpointMarker);
    if (markerAt < 0) continue;
    final encoded = message.substring(markerAt + _checkpointMarker.length).trim();
    try {
      final item = _asMap(jsonDecode(encoded));
      final id = _text(item, 'test_id');
      if (id.isEmpty) continue;
      results[id] = <String, dynamic>{
        'passed': item['passed'] == true,
        'executed': true,
        'message': item['message']?.toString() ?? '',
        'observation': item['passed'] == true
            ? null
            : <String, dynamic>{
                'kind': 'BEHAVIOR_CHECKPOINT_FAILED',
                'scenario_code': item['scenario_code'],
                'message': item['message'],
              },
      };
    } catch (_) {
      // Marker hỏng sẽ được tính là missing và báo runner error ở cấp scenario.
    }
  }
  return results;
}

String? _lastStage(String output) {
  String? stage;
  for (final line in output.split('\n')) {
    String message = line;
    try {
      final event = jsonDecode(line);
      if (event is Map && event['type'] == 'print') {
        message = event['message']?.toString() ?? '';
      }
    } catch (_) {}
    final at = message.lastIndexOf(_stageMarker);
    if (at < 0) continue;
    final candidate = message.substring(at + _stageMarker.length).trim();
    final match = RegExp(r'^[A-Z0-9_]+').firstMatch(candidate);
    if (match != null) stage = match.group(0);
  }
  return stage;
}

Map<String, dynamic> _assemble(
  Map<String, dynamic> matrix,
  Map<String, Map<String, dynamic>> results,
  List<String> runnerErrors,
) {
  final cases = <Map<String, dynamic>>[];
  var passed = 0;
  var notRun = 0;
  var earned = 0.0;
  var total = 0.0;
  for (final entry in matrix.entries) {
    final metadata = _asMap(entry.value);
    // CHỦ QUYỀN THEO TẦNG. Kho tiêu chí dùng chung cho cả ba tầng chấm, nên nó chứa
    // cả mã của tầng tĩnh và tầng đơn vị. Engine hành vi chỉ được phát kết quả cho mã
    // của CHÍNH NÓ: ôm luôn mã tầng khác thì mọi tiêu chí đó thành not_run và bị trừ
    // điểm dù chưa tầng nào chấm. Ghép ba tầng là việc của merge_grade_results.dart.
    // Thiếu trường runner = kho đời cũ, toàn bộ là hành vi.
    final runner = (metadata['runner'] ?? 'BEHAVIOR_REPLAY').toString();
    if (runner != 'BEHAVIOR_REPLAY') continue;
    final result = results[entry.key];
    final weight = _double(metadata['weight'], 1);
    final ok = result?['passed'] == true;
    final executed = result?['executed'] == true;
    if (ok) {
      passed++;
      earned += weight;
    } else if (!executed) {
      notRun++;
    }
    total += weight;
    final observation = _asMap(result?['observation']);
    cases.add(<String, dynamic>{
      'test_id': entry.key,
      'scenario_code': metadata['scenario_code'],
      'name': metadata['name'] ?? entry.key,
      'status': ok ? 'passed' : (executed ? 'failed' : 'not_run'),
      'executed': executed,
      'max_score': weight,
      'difficulty': metadata['difficulty'] ?? 'intermediate',
      'skill_code': metadata['skill_code'] ?? 'BEHAVIOR_REPLAY',
      if (!ok) 'actual': result?['message'] ?? 'Scenario chưa chạy.',
      // Chỉ dòng KHÔNG đạt mới có lỗi để mô tả; dòng đạt thì vắng mặt hẳn.
      if (!ok) ...<String, dynamic>{
        'observation': observation,
        'error_origin': observation['origin'] ?? (executed ? 'STUDENT' : 'UNDETERMINED'),
        'error_stage': observation['stage'] ?? 'BEHAVIOR_REPLAY',
      },
      'requires_manual_review': !ok && !executed,
    });
  }
  final score = total <= 0 ? 0.0 : earned / total * 10;
  final rounded = (score * 10).round() / 10;
  final runnerError = runnerErrors.isEmpty ? null : runnerErrors.join('\n');
  return <String, dynamic>{
    'mode': 'golden_behavior_record_replay',
    'diem': rounded,
    'soTestPass': passed,
    'soTestFail': cases.length - passed,
    'tongSoTest': cases.length,
    'tongSoTieuChi': cases.length,
    'test_cases': cases,
    'chiTiet': cases
        .map((item) => <String, dynamic>{
              'name': item['test_id'],
              'status': item['status'] == 'passed' ? 'PASS' : 'FAILED',
              'message': item['actual'],
            })
        .toList(),
    'grading_result': <String, dynamic>{
      'score': rounded,
      'passed_tests': passed,
      'failed_tests': cases.length - passed,
      'total_tests': cases.length,
      'total_scenarios': cases.map((item) => item['scenario_code']).toSet().length,
      'total_criteria': cases.length,
      'not_run_tests': notRun,
      'earned_weight': earned,
      'total_weight': total,
      'blocked': false,
      'runner_error': runnerError,
      'diagnostic_code': runnerError == null ? null : 'RAR_SCENARIO_INCOMPLETE',
      'diagnostic_origin': runnerError == null ? null : 'UNDETERMINED',
      'diagnostic_stage': runnerError == null ? null : 'BEHAVIOR_REPLAY',
      'diagnostic_message': runnerError,
      'requires_manual_review': notRun > 0,
      'engine_version': _engineVersion,
    },
  };
}

String _shortProcessError(_ProcessResult result) {
  final source = result.stderr.trim().isNotEmpty ? result.stderr : result.stdout;
  final compact = source.replaceAll(RegExp(r'\s+'), ' ').trim();
  if (compact.isEmpty) return 'Scenario dừng với exit code ${result.exitCode}.';
  return compact.length <= 800 ? compact : '${compact.substring(0, 800)}…';
}

int _positiveEnvInt(String name, int fallback) {
  final value = int.tryParse(Platform.environment[name] ?? '');
  return value == null || value <= 0 ? fallback : value;
}

Map<String, dynamic> _readObject(String primary, String fallback) {
  final file = File(primary).existsSync() ? File(primary) : File(fallback);
  if (!file.existsSync()) throw StateError('Không tìm thấy $primary.');
  return _asMap(jsonDecode(file.readAsStringSync()));
}

Map<String, dynamic> _asMap(Object? value) {
  if (value is! Map) return <String, dynamic>{};
  return <String, dynamic>{
    for (final entry in value.entries) '${entry.key}': entry.value,
  };
}

List<dynamic> _asList(Object? value) => value is List ? value : <dynamic>[];

String _text(Map<String, dynamic> source, String key, [String fallback = '']) {
  final value = source[key]?.toString().trim();
  return value == null || value.isEmpty ? fallback : value;
}

double _double(Object? value, double fallback) {
  if (value is num) return value.toDouble();
  return double.tryParse('$value') ?? fallback;
}

class _ProcessResult {
  const _ProcessResult(this.exitCode, this.stdout, this.stderr, this.timedOut);

  final int exitCode;
  final String stdout;
  final String stderr;
  final bool timedOut;
}

/// Lỗi biên dịch thuộc về ai. CFE in `đường/dẫn.dart:dòng:cột: Error:` — đường
/// dẫn trong lib/ là mã SINH VIÊN, trong test/ là mã BỘ ĐỀ. Bài hỏng thường kéo
/// lỗi lan sang test/ (import gãy) nên chỉ cần MỘT lỗi ở lib/ là quy cho bài.
/// Không thấy dạng lỗi này (chết vì lý do khác) thì trả rỗng để giữ UNDETERMINED.
String _nguonLoiBienDich(String output) {
  final matches = RegExp(r'([^\s:]+\.dart):\d+:\d+:\s*Error:').allMatches(output);
  var coTest = false;
  for (final m in matches) {
    final path = (m.group(1) ?? '').replaceAll('\\', '/');
    if (path.startsWith('lib/') || path.contains('/lib/')) return 'STUDENT';
    if (path.startsWith('test/') || path.contains('/test/')) coTest = true;
  }
  return coTest ? 'TESTCASE' : '';
}
