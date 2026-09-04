import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart'
    show FontLoader, MethodCall, SystemChannels;
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../lib/main.dart' as student_app;

const _observationMarker = '###GRADER_OBS###';
const _checkpointMarker = '###RAR_CHECKPOINT###';
const _captureMarker = '###RAR_CAPTURE###';
const _stageMarker = '###GRADER_STAGE###';

/// Mô hình history tối thiểu của platform web trong widget-test.
///
/// Router API báo URL ra `SystemChannels.navigation`; các action open/back/forward
/// lại đưa URL vào app qua `handlePushRoute`. Nhờ đứng đúng hai đầu public của Flutter,
/// bộ chấm không phụ thuộc GoRouter, AutoRoute hay Navigator do sinh viên chọn.
class _RouteTracker {
  _RouteTracker(String initialUri)
    : current = Uri.parse(initialUri),
      history = <Uri>[Uri.parse(initialUri)];

  Uri current;
  final List<Uri> history;
  int index = 0;
  int revision = 0;
  bool multiEntry = true;

  void install(WidgetTester tester) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.navigation,
      (MethodCall call) async {
        if (call.method == 'selectSingleEntryHistory') {
          multiEntry = false;
          return null;
        }
        if (call.method == 'selectMultiEntryHistory') {
          multiEntry = true;
          return null;
        }
        if (call.method == 'routeInformationUpdated' ||
            call.method == 'routeUpdated') {
          final args = _asMap(call.arguments);
          final raw = _text(args, 'uri', _text(args, 'location'));
          if (raw.isNotEmpty) {
            report(Uri.parse(raw), replace: _bool(args['replace'], false));
          }
        }
        return null;
      },
    );
  }

  void uninstall(WidgetTester tester) {
    tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(
      SystemChannels.navigation,
      null,
    );
  }

  void report(Uri uri, {bool replace = false}) {
    revision++;
    current = uri;
    if (!multiEntry || replace) {
      history[index] = uri;
      return;
    }
    if (history[index] == uri) return;
    if (index + 1 < history.length)
      history.removeRange(index + 1, history.length);
    history.add(uri);
    index = history.length - 1;
  }

  Uri? goBack() {
    if (index <= 0) return null;
    index--;
    current = history[index];
    revision++;
    return current;
  }

  Uri? goForward() {
    if (index + 1 >= history.length) return null;
    index++;
    current = history[index];
    revision++;
    return current;
  }

  bool get canBack => index > 0;
  bool get canForward => index + 1 < history.length;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final plan = _readObject('test/behavior_plan.json', 'behavior_plan.json');
  final cases = _asList(plan['cases']).map(_asMap).toList();
  final selectedScenario = Platform.environment['GRADER_SCENARIO_CODE'];
  final grouped = <String, List<Map<String, dynamic>>>{};
  for (final testCase in cases) {
    final executionCode = _text(
      testCase,
      'execution_code',
      _text(testCase, 'scenario_code'),
    );
    if (executionCode.isEmpty) continue;
    grouped.putIfAbsent(executionCode, () => []).add(testCase);
  }

  for (final entry in grouped.entries) {
    if (selectedScenario != null && selectedScenario != entry.key) continue;
    testWidgets(entry.key, (tester) async {
      await _runBehaviorScenario(tester, plan, entry.value);
    });
  }
}

Future<void> _runBehaviorScenario(
  WidgetTester tester,
  Map<String, dynamic> plan,
  List<Map<String, dynamic>> cases,
) async {
  final testCase = cases.first;
  final runtime = _asMap(plan['runtime_config']);
  final databaseContract = _asMap(plan['database_contract']);
  final timeout = Duration(
    milliseconds: _int(runtime['default_timeout_ms'], 5000),
  );
  // Mỗi luồng chụp lại màn hình cuối đúng một lần; xoá đệm của luồng trước.
  _anhCuoi = null;
  _daChupAnhCuoi = false;
  _mauNenAnh = null;

  final steps = _asList(testCase['steps']).map(_asMap).toList();
  final initialUri = _initialRouteUri(steps);
  final routeTracker = _RouteTracker(initialUri)..install(tester);

  try {
    sqfliteFfiInit();
    // Flutter widget tests run in a headless sandbox. The regular FFI factory
    // delegates work to a background isolate, which can remain pending forever
    // in constrained Docker environments. Keep all SQLite calls in the test
    // isolate so Golden and student replays have deterministic timeouts.
    databaseFactory = databaseFactoryFfiNoIsolate;
    _applyViewport(tester, _asMap(testCase['viewport']));
    tester.platformDispatcher.defaultRouteNameTestValue = initialUri;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
      tester.platformDispatcher.clearDefaultRouteNameTestValue();
      routeTracker.uninstall(tester);
    });
    if (_bool(_asMap(testCase['initial_state'])['reset_storage'], true)) {
      await tester.runAsync(() => _resetDatabase(databaseContract));
    }
    await tester.runAsync(_loadRealFonts);
    stdout.writeln('${_stageMarker}STUDENT_APP_BOOT');
    await _bootStudentApp(tester, timeout);

    for (final step in steps) {
      stdout.writeln('${_stageMarker}STUDENT_UI_ACTION');
      await _runStep(tester, step, timeout, routeTracker);
      await _allowExternalAsync(tester);
      await _boundedPump(tester, timeout);
      _throwPendingException(tester, _text(step, 'id', 'action'));
    }

    // Khi abstract một record, backend chạy đúng runner này trên Golden Solution
    // với Hidden DB rồi yêu cầu capture. Chấm bài sinh viên không đặt hai biến môi
    // trường bên dưới nên hoàn toàn không phát sinh file hoặc thay đổi cách assert.
    await tester.runAsync(() => _captureOutputDatabase(databaseContract));
    // CAPTURE MODE: chup man hinh cuoi luong lam ANH CHUAN cho tieu chi screen_match.
    // Cham bai sinh vien khong dat bien moi truong nay nen khong phat sinh file nao.
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty) {
      await _saveGoldenScreenshot(tester);
      // Đo luôn vị trí + màu chuẩn của mọi thành phần được chấm giao diện. Cùng một
      // khoảnh khắc với ảnh chuẩn nên hai thứ không thể lệch nhau.
      await _luuBoCucChuan(tester, cases);
    } else {
      // GRADING MODE: luu anh man hinh cuoi luong lam BANG CHUNG phuc khao — di vao
      // ho so ZIP cua sinh vien. Bao loi nuot: thieu anh chi mat mot dong bang chung,
      // khong duoc lam hong luot cham.
      await _saveEvidenceScreenshot(
        tester,
        _text(
          cases.first,
          'execution_code',
          _text(cases.first, 'scenario_code'),
        ),
      );
    }

    // Chạy hết mọi phép kiểm TRƯỚC rồi mới in kết quả: luật `requires` (điều kiện
    // tiên quyết) cần biết số phận của checkpoint khác trong cùng nhóm. Tiên quyết
    // trượt thì checkpoint phụ thuộc bị hạ xuống trượt dù tự nó đạt — UI hiện đúng
    // trong khi database không đổi là cái đạt vô nghĩa, người chấm tay cũng cho 0.
    final ketQua = <String, bool>{};
    final thongDiep = <String, String>{};
    for (final checkpointCase in cases) {
      final checkpoint = _asMap(checkpointCase['checkpoint']);
      final khoa = _text(checkpoint, 'id', _text(checkpointCase, 'test_id'));
      stdout.writeln('${_stageMarker}TESTCASE_ASSERTION');
      try {
        await _assertCheckpoint(
          tester,
          checkpoint,
          databaseContract,
          timeout,
          routeTracker: routeTracker,
          executionCode: _text(
            checkpointCase,
            'execution_code',
            _text(checkpointCase, 'scenario_code'),
          ),
        );
        _throwPendingException(tester, 'checkpoint');
        ketQua[khoa] = true;
        thongDiep[khoa] = 'Đã đáp ứng yêu cầu';
      } catch (error) {
        ketQua[khoa] = false;
        thongDiep[khoa] = error.toString();
      }
    }
    // Lan truyền tiên quyết theo chuỗi (A cần B, B cần C): lặp tới khi ổn định.
    var doi = true;
    while (doi) {
      doi = false;
      for (final checkpointCase in cases) {
        final checkpoint = _asMap(checkpointCase['checkpoint']);
        final khoa = _text(checkpoint, 'id', _text(checkpointCase, 'test_id'));
        final tienQuyet = _text(checkpoint, 'requires');
        if (tienQuyet.isEmpty || ketQua[khoa] != true) continue;
        if (ketQua[tienQuyet] == false) {
          ketQua[khoa] = false;
          thongDiep[khoa] =
              'Tự thân ĐẠT nhưng điều kiện tiên quyết "$tienQuyet" trượt '
              '(${thongDiep[tienQuyet] ?? ''}) nên không được tính điểm.';
          doi = true;
        }
      }
    }
    for (final checkpointCase in cases) {
      final checkpoint = _asMap(checkpointCase['checkpoint']);
      final khoa = _text(checkpoint, 'id', _text(checkpointCase, 'test_id'));
      _printCheckpoint(
        checkpointCase,
        ketQua[khoa] ?? false,
        thongDiep[khoa] ?? 'Không có kết quả',
      );
    }
  } catch (error, stackTrace) {
    for (final checkpointCase in cases) {
      _printCheckpoint(checkpointCase, false, error.toString());
    }
    stdout.writeln(
      '$_observationMarker${jsonEncode(<String, dynamic>{'kind': 'BEHAVIOR_REPLAY_FAILURE', 'scenario_code': testCase['scenario_code'], 'checkpoint_id': _asMap(testCase['checkpoint'])['id'], 'message': error.toString()})}',
    );
    Error.throwWithStackTrace(error, stackTrace);
  }
}

void _applyViewport(WidgetTester tester, Map<String, dynamic> viewport) {
  // Mặc định = máy Android tầm trung (Pixel): 412×915 dp — đúng cỡ Android Studio hiện
  // cho máy ảo. Sinh viên làm bài trên máy ảo Android nên khung này mới sát thực tế.
  //
  // Mật độ để 1: vị trí thành phần và sai số bố cục đều đo bằng dp nên mật độ KHÔNG đổi
  // một điểm nào; nó chỉ quyết định ảnh bằng chứng nét tới đâu và nặng bao nhiêu.
  final width = _double(viewport['width'], 412);
  final height = _double(viewport['height'], 915);
  final ratio = _double(viewport['device_pixel_ratio'], 1);
  if (width <= 0 || height <= 0 || ratio <= 0) {
    throw ArgumentError('Viewport không hợp lệ: $viewport');
  }
  tester.view.devicePixelRatio = ratio;
  tester.view.physicalSize = Size(width * ratio, height * ratio);
}

String _initialRouteUri(List<Map<String, dynamic>> steps) {
  if (steps.isNotEmpty && _text(steps.first, 'action') == 'boot_with_uri') {
    return _stepUri(steps.first);
  }
  return '/';
}

String _stepUri(Map<String, dynamic> step) {
  final value = _text(step, 'uri', step['value']?.toString() ?? '');
  if (value.isEmpty)
    throw ArgumentError('Action ${step['action']} thiếu URI/path.');
  return value;
}

Future<void> _deliverRoute(
  WidgetTester tester,
  _RouteTracker tracker,
  String uri,
  Duration timeout, {
  bool alreadyTracked = false,
}) async {
  final before = tracker.revision;
  final handled = await tester.binding.handlePushRoute(uri);
  await _boundedPump(tester, timeout);
  if (!handled) {
    throw StateError(
      'Ứng dụng không nhận RouteInformation "$uri". Hãy cấu hình MaterialApp.router, '
      'onGenerateRoute hoặc didPushRouteInformation theo yêu cầu đề.',
    );
  }
  // Router chuẩn thường phản ánh URL trở lại SystemNavigator. Navigator kiểu cũ có
  // thể chỉ nhận route mà không báo ngược; vẫn ghi route đầu vào để checkpoint không
  // phụ thuộc package routing cụ thể.
  if (!alreadyTracked && tracker.revision == before)
    tracker.report(Uri.parse(uri));
}

bool _routeMatches(Uri actual, String expected) {
  final wanted = Uri.parse(expected);
  if (wanted.hasScheme) return actual.toString() == wanted.toString();
  if (expected.startsWith('#')) return actual.fragment == wanted.fragment;
  if (wanted.hasQuery || wanted.hasFragment) {
    return actual.path == wanted.path &&
        _sameQuery(actual.queryParametersAll, wanted.queryParametersAll) &&
        actual.fragment == wanted.fragment;
  }
  return actual.path == wanted.path;
}

bool _sameQuery(Map<String, List<String>> actual, Map<String, List<String>> wanted) {
  if (actual.length != wanted.length) return false;
  for (final entry in wanted.entries) {
    final actualValues = actual[entry.key];
    if (actualValues == null || actualValues.length != entry.value.length) {
      return false;
    }
    for (var index = 0; index < entry.value.length; index++) {
      if (actualValues[index] != entry.value[index]) return false;
    }
  }
  return true;
}

void _printCheckpoint(
  Map<String, dynamic> testCase,
  bool passed,
  String message,
) {
  stdout.writeln(
    '$_checkpointMarker${jsonEncode(<String, dynamic>{'test_id': testCase['test_id'], 'scenario_code': testCase['scenario_code'], 'passed': passed, 'message': message})}',
  );
}

Future<void> _bootStudentApp(WidgetTester tester, Duration timeout) async {
  await tester.runAsync(() async {
    await Future<void>.sync(student_app.main).timeout(
      timeout,
      onTimeout: () {
        throw TimeoutException('student_app.main() không hoàn tất', timeout);
      },
    );
  });
  await tester.pump();
  await _boundedPump(tester, timeout);
  _throwPendingException(tester, 'boot');
  expect(
    find.byType(WidgetsApp),
    findsAtLeastNWidgets(1),
    reason: 'main() đã chạy nhưng không render WidgetsApp/MaterialApp.',
  );
}

Future<void> _runStep(
  WidgetTester tester,
  Map<String, dynamic> step,
  Duration defaultTimeout,
  _RouteTracker routeTracker,
) async {
  final action = _text(step, 'action');
  final timeout = Duration(
    milliseconds: _int(step['timeout_ms'], defaultTimeout.inMilliseconds),
  );
  switch (action) {
    case 'boot':
    case 'boot_with_uri':
      return;
    case 'tap':
      final finder = await _waitForTarget(
        tester,
        _asMap(step['target']),
        timeout,
      );
      await tester.ensureVisible(finder);
      await tester.tap(finder, warnIfMissed: false);
      return;
    case 'enter_text':
      final finder = await _waitForTarget(
        tester,
        _asMap(step['target']),
        timeout,
      );
      await tester.ensureVisible(finder);
      await tester.enterText(finder, (step['value']?.toString() ?? ''));
      return;
    case 'clear_text':
      final finder = await _waitForTarget(
        tester,
        _asMap(step['target']),
        timeout,
      );
      await tester.enterText(finder, '');
      return;
    case 'scroll':
      final target = _asMap(step['target']);
      final finder = target.isEmpty
          ? find.byType(Scrollable).first
          : await _waitForTarget(tester, target, timeout);
      final delta = _asMap(step['delta']);
      final dx = _double(delta['x'], 0);
      final dy = _double(delta['y'], -300);
      await tester.drag(finder, Offset(dx, dy));
      return;
    case 'back':
      await tester.pageBack();
      return;
    case 'open_uri':
      await _deliverRoute(tester, routeTracker, _stepUri(step), timeout);
      return;
    case 'browser_back':
      final previous = routeTracker.goBack();
      if (previous == null) {
        throw StateError('Browser history không còn route phía sau để back.');
      }
      await _deliverRoute(
        tester,
        routeTracker,
        previous.toString(),
        timeout,
        alreadyTracked: true,
      );
      return;
    case 'browser_forward':
      final next = routeTracker.goForward();
      if (next == null) {
        throw StateError(
          'Browser history không còn route phía trước để forward.',
        );
      }
      await _deliverRoute(
        tester,
        routeTracker,
        next.toString(),
        timeout,
        alreadyTracked: true,
      );
      return;
    case 'reload':
      // Widget-test không có process Chrome để F5. Phát lại RouteInformation hiện tại
      // là phép kiểm portable tương ứng: router phải phục hồi đúng màn từ URL hiện có.
      await _deliverRoute(
        tester,
        routeTracker,
        routeTracker.current.toString(),
        timeout,
        alreadyTracked: true,
      );
      return;
    case 'wait_until':
      final target = _asMap(step['target']);
      final visible = _bool(step['visible'], true);
      await _waitUntil(
        tester,
        () => _finder(target).evaluate().isNotEmpty == visible,
        timeout,
        'wait_until không đạt trạng thái mong đợi',
      );
      return;
    case 'wait_for_route':
      final expected = _stepUri(step);
      await _waitUntil(
        tester,
        () => _routeMatches(routeTracker.current, expected),
        timeout,
        'Route không đạt "$expected"; hiện tại là "${routeTracker.current}".',
      );
      return;
    case 'restart':
      throw UnsupportedError(
        'restart phải được tách thành scenario mới để bảo đảm cô lập process.',
      );
    default:
      throw ArgumentError('Action không được hỗ trợ: $action');
  }
}

Future<void> _assertCheckpoint(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Map<String, dynamic> databaseContract,
  Duration timeout, {
  String executionCode = '',
  required _RouteTracker routeTracker,
}) async {
  final kind = _text(checkpoint, 'kind');
  // SO BO CUC VOI ANH CHUAN. Anh chuan duoc chup tu chinh Golden Solution trong
  // cung container Docker luc capture oracle — cung renderer, cung font, nen khong
  // dinh sai so Windows/macOS. Nguong khop la chinh sach cua de (mac dinh 85%).
  if (kind == 'screen_match') {
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty) {
      return; // dang capture chinh anh chuan, chua co gi de so
    }
    await _assertScreenMatch(tester, checkpoint, executionCode);
    return;
  }
  if (kind == 'route_state') {
    _assertRouteState(tester, checkpoint, routeTracker);
    return;
  }
  if (kind == 'layout_relation') {
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty &&
        _text(checkpoint, 'relation', 'auto') == 'auto') {
      return;
    }
    await _assertLayoutRelation(tester, checkpoint, timeout);
    return;
  }
  if (kind == 'entity_consistency' ||
      _text(checkpoint, 'scope') == 'cross_layer') {
    await tester.runAsync(() => _assertDatabase(checkpoint, databaseContract));
    for (final raw in _asList(checkpoint['ui_values'])) {
      final value = (raw?.toString() ?? '');
      await _waitUntil(
        tester,
        () => find.text(value).evaluate().isNotEmpty,
        timeout,
        'SQLite co row dung nhung UI khong hien thi cung gia tri "$value".',
      );
    }
    return;
  }
  if (kind == 'database_observation' ||
      _text(checkpoint, 'scope') == 'database') {
    await tester.runAsync(() => _assertDatabase(checkpoint, databaseContract));
    return;
  }

  // THÀNH PHẦN GIAO DIỆN CÓ MẶT — dùng cho nhóm tiêu chí "Giao diện".
  // Chỉ NHÌN màn hình hiện tại, không thao tác gì. Mỗi tiêu chí một thành phần,
  // nhị phân; điểm lẻ của nhóm nổi lên từ số thành phần đạt, không từ điểm lẻ
  // của từng dòng. Thiếu target là lỗi ĐỀ, phải ném chứ không được im lặng.
  if (kind == 'component_present') {
    final target = _asMap(checkpoint['target']);
    if (target.isEmpty) {
      throw ArgumentError(
        'Checkpoint component_present thiếu target — không biết phải tìm thành phần nào.',
      );
    }
    final visible = _bool(
      checkpoint['visible'] ?? _asMap(checkpoint['expect'])['visible'],
      true,
    );
    final moTa = _moTaTarget(target);
    await _waitUntil(
      tester,
      () => _finder(target).evaluate().isNotEmpty == visible,
      timeout,
      visible
          ? 'Không thấy $moTa trên màn hình.'
          : 'Vẫn thấy $moTa trên màn hình dù lẽ ra phải ẩn.',
    );
    return;
  }

  // VỊ TRÍ THÀNH PHẦN. Lúc capture oracle chưa có giá trị chuẩn để so nên bỏ qua —
  // chính lượt capture đó sinh ra giá trị chuẩn.
  if (kind == 'component_position') {
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty) {
      return;
    }
    await _assertComponentPosition(tester, checkpoint, timeout);
    return;
  }

  // MÀU CHỦ ĐẠO CỦA APP — đọc thẳng ColorScheme, không lấy mẫu pixel.
  if (kind == 'theme_color') {
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty) {
      return;
    }
    final mauChuan = _text(_asMap(checkpoint['expect']), 'color');
    if (mauChuan.isEmpty) {
      throw StateError(
        'Tiêu chí màu chủ đạo chưa có giá trị chuẩn — hãy capture lại oracle rồi publish lại.',
      );
    }
    final mauBai = _mauChuDao(tester);
    if (mauBai == null) {
      throw StateError('Không đọc được bảng màu của app để so màu chủ đạo.');
    }
    _soMau(
      mauChuan,
      mauBai,
      _double(checkpoint['tolerance_pct'], 20),
      'Màu chủ đạo của app',
    );
    return;
  }

  // MÀU CHÍNH CỦA THÀNH PHẦN.
  if (kind == 'component_color') {
    if ((Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty) {
      return;
    }
    await _assertComponentColor(tester, checkpoint, timeout);
    return;
  }

  final expectValue = _asMap(checkpoint['expect']);
  // Đếm số phép kiểm THẬT SỰ chạy. Xem chốt chặn cuối hàm.
  var soPhepKiem = 0;
  for (final raw in _asList(expectValue['semantic_nodes'])) {
    await _assertSemanticNode(tester, _asMap(raw), timeout);
    soPhepKiem++;
  }
  final target = _asMap(checkpoint['target']);
  if (target.isNotEmpty) {
    final visible = _bool(
      checkpoint['visible'] ?? expectValue['visible'],
      true,
    );
    await _waitUntil(
      tester,
      () => _finder(target).evaluate().isNotEmpty == visible,
      timeout,
      'Widget không có trạng thái hiển thị mong đợi: $target',
    );
    soPhepKiem++;
  }

  for (final raw in _asList(expectValue['visible_texts'])) {
    final value = (raw?.toString() ?? '');
    await _waitUntil(
      tester,
      () => find.text(value).evaluate().isNotEmpty,
      timeout,
      'Không thấy nội dung "$value" trên UI.',
    );
    soPhepKiem++;
  }
  for (final raw in _asList(expectValue['hidden_texts'])) {
    final value = (raw?.toString() ?? '');
    expect(
      find.text(value),
      findsNothing,
      reason: 'Nội dung "$value" vẫn còn trên UI.',
    );
    soPhepKiem++;
  }

  final expectedText = checkpoint['text'] ?? expectValue['text'];
  if (expectedText != null) {
    final value = (expectedText?.toString() ?? '');
    expect(find.text(value), findsAtLeastNWidgets(1));
    soPhepKiem++;
  }
  final noException = checkpoint['no_exception'] ?? expectValue['no_exception'];
  if (_bool(noException, false)) {
    _throwPendingException(tester, 'checkpoint');
    soPhepKiem++;
  }

  // CHỐT CHẶN PASS CÂM. Trước đây một checkpoint mang `kind` mà engine không biết
  // sẽ rơi vào nhánh này, không khớp nhánh con nào, rồi TRẢ VỀ BÌNH THƯỜNG — tức
  // cho điểm mà chưa kiểm gì. Đó là kiểu hỏng tệ nhất: im lặng và có lợi cho bài nộp.
  if (soPhepKiem == 0) {
    throw StateError(
      'Checkpoint không kiểm điều gì (kind="$kind"). Bộ đề khai sai hoặc engine '
      'trong bộ đề cũ hơn engine đã sinh ra checkpoint này — xuất bản lại bộ đề.',
    );
  }
}

/// Mô tả target bằng tiếng Việt cho người đọc log phúc khảo, thay vì in Map thô.
String _moTaTarget(Map<String, dynamic> target) {
  for (final khoa in const <String>['label', 'hint', 'text', 'tooltip']) {
    final v = _text(target, khoa);
    if (v.isNotEmpty) return '"$v"';
  }
  return target.toString();
}

Future<void> _assertSemanticNode(
  WidgetTester tester,
  Map<String, dynamic> node,
  Duration timeout,
) async {
  final target = _asMap(node['target']);
  if (target.isEmpty) {
    throw ArgumentError('Semantic node thiếu target nhận diện.');
  }
  final visible = _bool(node['visible'], true);
  await _waitUntil(
    tester,
    () => _finder(target).evaluate().isNotEmpty == visible,
    timeout,
    'Thành phần semantic không có trạng thái hiển thị mong đợi: $target',
  );
  if (!visible) return;

  final finder = _finder(target);
  final role = _text(node, 'role').toLowerCase();
  if (role.isNotEmpty && role != 'generic') {
    expect(
      _widgetsNear(finder).any((widget) => _matchesSemanticRole(widget, role)),
      isTrue,
      reason: 'Thành phần $target không đúng loại semantic "$role".',
    );
  }

  if (node.containsKey('value')) {
    final expected = (node['value']?.toString() ?? '');
    final actual = _semanticValue(_widgetsNear(finder));
    expect(actual, expected, reason: 'Giá trị của $target không đúng.');
  }
  if (node.containsKey('enabled')) {
    final actual = _semanticEnabled(_widgetsNear(finder));
    expect(
      actual,
      _bool(node['enabled'], true),
      reason: 'Trạng thái enabled của $target không đúng.',
    );
  }
  if (node.containsKey('checked')) {
    final actual = _semanticChecked(_widgetsNear(finder));
    expect(
      actual,
      _bool(node['checked'], false),
      reason: 'Trạng thái checked của $target không đúng.',
    );
  }
}

List<Widget> _widgetsNear(Finder finder) {
  final widgets = <Widget>[];
  final seen = <Widget>{};
  void add(Element element) {
    if (seen.add(element.widget)) widgets.add(element.widget);
  }

  for (final element in finder.evaluate()) {
    add(element);
    element.visitAncestorElements((ancestor) {
      add(ancestor);
      return widgets.length < 40;
    });
    void descendants(Element current, int depth) {
      if (depth <= 0 || widgets.length >= 80) return;
      current.visitChildElements((child) {
        add(child);
        descendants(child, depth - 1);
      });
    }

    descendants(element, 5);
  }
  return widgets;
}

bool _matchesSemanticRole(Widget widget, String role) => switch (role) {
  'text_field' => widget is TextField || widget is EditableText,
  'button' =>
    widget is ButtonStyleButton ||
        widget is IconButton ||
        widget is FloatingActionButton ||
        (widget is InkWell && widget.onTap != null) ||
        (widget is GestureDetector && widget.onTap != null),
  'checkbox' => widget is Checkbox,
  'switch' => widget is Switch,
  'radio' => widget is Radio,
  'text' => widget is Text || widget is RichText || widget is SelectableText,
  'image' => widget is Image || widget is Icon,
  'link' =>
    (widget is InkWell && widget.onTap != null) ||
        (widget is GestureDetector && widget.onTap != null),
  _ => false,
};

String? _semanticValue(List<Widget> widgets) {
  for (final widget in widgets) {
    if (widget is TextField) return widget.controller?.text ?? '';
    if (widget is EditableText) return widget.controller.text;
  }
  return null;
}

bool? _semanticEnabled(List<Widget> widgets) {
  for (final widget in widgets) {
    if (widget is TextField) return widget.enabled ?? true;
    if (widget is ButtonStyleButton) return widget.onPressed != null;
    if (widget is IconButton) return widget.onPressed != null;
    if (widget is FloatingActionButton) return widget.onPressed != null;
    if (widget is Checkbox) return widget.onChanged != null;
    if (widget is Switch) return widget.onChanged != null;
    if (widget is Radio) return widget.onChanged != null;
    if (widget is InkWell) return widget.onTap != null;
    if (widget is GestureDetector) return widget.onTap != null;
  }
  return null;
}

bool? _semanticChecked(List<Widget> widgets) {
  for (final widget in widgets) {
    if (widget is Checkbox) return widget.value ?? false;
    if (widget is Switch) return widget.value;
    if (widget is Radio) return widget.value == widget.groupValue;
  }
  return null;
}

Future<void> _assertDatabase(
  Map<String, dynamic> checkpoint,
  Map<String, dynamic> contract,
) async {
  if (!_bool(contract['enabled'], false)) {
    throw StateError(
      'Checkpoint DB tồn tại nhưng database_contract.enabled=false.',
    );
  }
  final path = await _databasePath(contract);
  if (!File(path).existsSync()) {
    throw StateError('Không tìm thấy SQLite database theo contract: $path');
  }
  final database = await databaseFactoryFfiNoIsolate.openDatabase(path);
  try {
    final table = (checkpoint['table']?.toString() ?? '');
    if (table.isEmpty || !RegExp(r'^[A-Za-z_][A-Za-z0-9_]*$').hasMatch(table)) {
      throw ArgumentError('Tên bảng SQLite không hợp lệ: $table');
    }
    final expected = <String, dynamic>{
      for (final entry in _asMap(checkpoint['row']).entries)
        entry.key: entry.value,
    };
    final rows = await database.query(table);
    final matches = rows.where((row) => _rowContains(row, expected)).toList();
    final operation = _text(checkpoint, 'operation').toUpperCase();
    if (operation == 'DELETE' || _bool(checkpoint['absent'], false)) {
      expect(
        matches,
        isEmpty,
        reason: 'SQLite vẫn còn row phải được xóa: $expected',
      );
    } else {
      expect(
        matches,
        isNotEmpty,
        reason: 'SQLite không có row mong đợi: $expected',
      );
    }
    if (checkpoint['count'] != null) {
      expect(rows.length, _int(checkpoint['count'], -1));
    }
  } finally {
    await database.close();
  }
}

bool _rowContains(Map<String, Object?> actual, Map<String, dynamic> expected) {
  for (final entry in expected.entries) {
    if (!actual.containsKey(entry.key)) return false;
    if ('${actual[entry.key]}' != '${entry.value}') return false;
  }
  return true;
}

Future<String> _databasePath(Map<String, dynamic> contract) async {
  final configured = (contract['path']?.toString() ?? '');
  if (configured.isNotEmpty) {
    return p.isAbsolute(configured)
        ? configured
        : p.normalize(p.join('/app', configured));
  }
  final name =
      (contract['database_name'] ?? contract['name']?.toString() ?? '');
  if (name.isEmpty)
    throw StateError('database_contract thiếu path hoặc database_name.');
  final root = await databaseFactoryFfiNoIsolate.getDatabasesPath();
  return p.join(root, name);
}

Future<void> _resetDatabase(Map<String, dynamic> contract) async {
  if (!_bool(contract['enabled'], false)) return;
  final path = await _databasePath(contract);
  final target = File(path);
  if (target.existsSync())
    await databaseFactoryFfiNoIsolate.deleteDatabase(path);
  final fixturePath = (contract['hidden_fixture_path']?.toString() ?? '');
  if (fixturePath.isEmpty) return;
  final fixture = File(fixturePath);
  if (!fixture.existsSync()) {
    throw StateError('Không tìm thấy database ẩn: $fixturePath');
  }
  await target.parent.create(recursive: true);
  await fixture.copy(path);
}

/// Chụp cây widget hiện tại thành ảnh RGBA. Chạy BÊN TRONG tester.runAsync vì
/// toImage là I/O thật. Trả null nếu cây chưa có layer (app chưa vẽ được khung nào).
Future<ui.Image?> _captureScreenImage(WidgetTester tester) async {
  final elements = find.byType(WidgetsApp).evaluate();
  if (elements.isEmpty) return null;
  RenderObject? node = elements.first.renderObject;
  while (node != null && !node.isRepaintBoundary) {
    node = node.parent;
  }
  final layer = node?.debugLayer;
  if (node == null || layer is! OffsetLayer) return null;
  return layer.toImage(node.paintBounds);
}

/// Nạp font thật (Roboto trong Flutter SDK) để chữ trên ảnh là chữ thật thay vì
/// khối vuông Ahem. Roboto phủ đầy đủ tiếng Việt. Nạp CÙNG font cho cả lúc chụp
/// ảnh chuẩn lẫn lúc chấm nên hai bên luôn công bằng; SDK thiếu font thì bỏ qua —
/// hai bên cùng Ahem, phép so vẫn đúng.
bool _fontsLoaded = false;
Future<void> _loadRealFonts() async {
  if (_fontsLoaded) return;
  _fontsLoaded = true;
  final root = Platform.environment['FLUTTER_ROOT'] ?? '';
  if (root.isEmpty) return;
  final dir = Directory(
    p.join(root, 'bin', 'cache', 'artifacts', 'material_fonts'),
  );
  if (!dir.existsSync()) return;
  try {
    final loader = FontLoader('Roboto');
    for (final file in dir.listSync().whereType<File>()) {
      final name = p.basename(file.path);
      if (name.startsWith('Roboto-') && name.endsWith('.ttf')) {
        loader.addFont(
          Future.value(ByteData.view(file.readAsBytesSync().buffer)),
        );
      }
    }
    await loader.load();
  } catch (_) {
    // Thiếu font không được làm hỏng lượt chấm.
  }
}

/// Ghi ảnh chuẩn lúc capture oracle: nằm cạnh captured-output.db để
/// GoldenOracleCaptureService nhặt về cùng một chỗ.
Future<void> _saveGoldenScreenshot(WidgetTester tester) async {
  await tester.runAsync(() async {
    final outputPath = Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '';
    if (outputPath.isEmpty) return;
    final image = await _captureScreenImage(tester);
    if (image == null) return;
    final png = await image.toByteData(format: ui.ImageByteFormat.png);
    if (png == null) return;
    final target = File(
      p.join(File(outputPath).parent.path, 'captured-screen.png'),
    );
    target.parent.createSync(recursive: true);
    target.writeAsBytesSync(png.buffer.asUint8List());
    stdout.writeln('Đã chụp ảnh chuẩn: ${target.path}');
  });
}

/// Ảnh bằng chứng lúc CHẤM: mỗi luồng một tệp <execution_code>.png trong thư mục
/// GRADER_EVIDENCE_DIR do backend mount riêng cho từng bài. Không đặt biến = không ghi gì.
Future<void> _saveEvidenceScreenshot(
  WidgetTester tester,
  String executionCode,
) async {
  final dir = Platform.environment['GRADER_EVIDENCE_DIR'] ?? '';
  if (dir.isEmpty || executionCode.isEmpty) return;
  try {
    await tester.runAsync(() async {
      final image = await _captureScreenImage(tester);
      if (image == null) return;
      final png = await image.toByteData(format: ui.ImageByteFormat.png);
      if (png == null) return;
      final target = File(p.join(dir, '$executionCode.png'));
      target.parent.createSync(recursive: true);
      target.writeAsBytesSync(png.buffer.asUint8List());
    });
  } catch (e) {
    // Bang chung la phu, diem la chinh — nhung PHAI de lai dau vet, khong duoc cam.
    stdout.writeln('Không chụp được ảnh bằng chứng ($executionCode): $e');
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// CHẤM GIAO DIỆN THEO TỪNG THÀNH PHẦN
//
// Ảnh màn hình cuối luồng, chụp MỘT LẦN rồi dùng lại cho mọi tiêu chí màu của
// luồng đó — chụp lại cho từng tiêu chí thì 16 tiêu chí là 16 lần toImage.
Uint8List? _anhCuoi;
int _anhCuoiW = 0;
int _anhCuoiH = 0;
bool _daChupAnhCuoi = false;
List<int>? _mauNenAnh;

/// Bảo đảm đã có ảnh màn hình cuối luồng trong bộ đệm. Trả false nếu không chụp được.
Future<bool> _baoDamAnhCuoi(WidgetTester tester) async {
  if (_daChupAnhCuoi) return _anhCuoi != null;
  _daChupAnhCuoi = true;
  await tester.runAsync(() async {
    final image = await _captureScreenImage(tester);
    if (image == null) return;
    final data = await image.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (data == null) return;
    _anhCuoi = data.buffer.asUint8List();
    _anhCuoiW = image.width;
    _anhCuoiH = image.height;
  });
  return _anhCuoi != null;
}

/// Màu nền của ảnh = màu xuất hiện nhiều nhất trên toàn màn hình. Dùng để loại nền
/// ra khỏi phép lấy "màu chính" — nếu không loại thì mọi thành phần trên nền trắng
/// đều trả về màu trắng và tiêu chí màu thành vô nghĩa.
List<int> _timMauNen() {
  if (_mauNenAnh != null) return _mauNenAnh!;
  final bytes = _anhCuoi!;
  final dem = <int, int>{};
  final tong = _anhCuoiW * _anhCuoiH;
  // Lấy mẫu thưa: đủ để tìm màu chiếm đa số mà không quét 300k pixel.
  final buoc = tong > 20000 ? tong ~/ 20000 : 1;
  for (var i = 0; i < tong; i += buoc) {
    final o = i * 4;
    final khoa = (bytes[o] << 16) | (bytes[o + 1] << 8) | bytes[o + 2];
    dem[khoa] = (dem[khoa] ?? 0) + 1;
  }
  var tot = 0;
  var soLan = -1;
  dem.forEach((k, v) {
    if (v > soLan) {
      soLan = v;
      tot = k;
    }
  });
  return _mauNenAnh = [(tot >> 16) & 0xFF, (tot >> 8) & 0xFF, tot & 0xFF];
}

/// MÀU CHÍNH của một vùng: màu xuất hiện nhiều nhất trong số các pixel KHÁC NỀN.
///
/// Vì sao bỏ nền: chip viền trắng hay dòng chữ trên nền trắng thì phần lớn pixel
/// trong khung là nền — lấy màu trội thô sẽ ra màu nền cho mọi bài, kể cả bài tô
/// sai màu. Bỏ nền đi thì chip tô đặc trả về màu tô, chữ trả về màu chữ.
/// Vì sao lấy màu TRỘI chứ không lấy trung bình: trung bình của chữ đen khử răng
/// cưa trên nền trắng ra màu xám, không phải màu sinh viên đặt.
String? _mauChinhTrongVung(Rect rect, double tiLe) {
  final bytes = _anhCuoi;
  if (bytes == null) return null;
  final nen = _timMauNen();
  final x0 = (rect.left * tiLe).round().clamp(0, _anhCuoiW - 1);
  final x1 = (rect.right * tiLe).round().clamp(0, _anhCuoiW);
  final y0 = (rect.top * tiLe).round().clamp(0, _anhCuoiH - 1);
  final y1 = (rect.bottom * tiLe).round().clamp(0, _anhCuoiH);
  final dem = <int, int>{};
  for (var y = y0; y < y1; y++) {
    for (var x = x0; x < x1; x++) {
      final o = (y * _anhCuoiW + x) * 4;
      final r = bytes[o], g = bytes[o + 1], b = bytes[o + 2];
      if ((r - nen[0]).abs() <= 16 &&
          (g - nen[1]).abs() <= 16 &&
          (b - nen[2]).abs() <= 16) {
        continue; // là nền
      }
      final khoa = (r << 16) | (g << 8) | b;
      dem[khoa] = (dem[khoa] ?? 0) + 1;
    }
  }
  if (dem.isEmpty) return null;
  var tot = 0;
  var soLan = -1;
  dem.forEach((k, v) {
    if (v > soLan) {
      soLan = v;
      tot = k;
    }
  });
  return '#' + tot.toRadixString(16).padLeft(6, '0').toUpperCase();
}

/// MÀU CHỦ ĐẠO của app: `colorScheme.primary` của theme ĐANG CÓ HIỆU LỰC.
///
/// Đọc từ cây widget chứ không lấy mẫu pixel, nên đây là giá trị CHÍNH XÁC sinh viên
/// đặt — không dính khử răng cưa, không lẫn màu nền, không phụ thuộc thành phần nào
/// có được tô màu hay không.
///
/// Lấy context SÂU trong cây (Scaffold/Material) chứ không lấy chính element của
/// MaterialApp: Theme.of tra ngược LÊN trên, đứng ngay tại MaterialApp thì trượt qua
/// chính theme mà app khai và trả về theme mặc định.
String? _mauChuDao(WidgetTester tester) {
  for (final finder in <Finder>[find.byType(Scaffold), find.byType(Material)]) {
    if (finder.evaluate().isEmpty) continue;
    try {
      final scheme = Theme.of(tester.element(finder.first)).colorScheme;
      return _hex(scheme.primary);
    } catch (_) {
      // Thử nguồn kế tiếp.
    }
  }
  // Dự phòng: đọc thẳng ThemeData sinh viên truyền cho MaterialApp.
  final app = find.byType(MaterialApp);
  if (app.evaluate().isEmpty) return null;
  final scheme = tester.widget<MaterialApp>(app.first).theme?.colorScheme;
  return scheme == null ? null : _hex(scheme.primary);
}

String _hex(Color c) =>
    '#' +
    (c.toARGB32() & 0xFFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase();

/// So hai màu theo SAI SỐ % CỦA 255 trên từng kênh R/G/B. Ném lỗi khi vượt ngưỡng.
/// Dùng chung cho cả màu thành phần lẫn màu chủ đạo để hai bên không lệch cách tính.
void _soMau(String mauChuan, String mauBai, double saiSoPct, String moTa) {
  final a = _mauTuChuoi(mauChuan);
  final b = _mauTuChuoi(mauBai);
  final choPhep = (saiSoPct / 100 * 255).round();
  final lech = [
    (((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)).abs(),
    (((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)).abs(),
    ((a & 0xFF) - (b & 0xFF)).abs(),
  ].reduce((x, y) => x > y ? x : y);
  if (lech > choPhep) {
    throw StateError(
      '$moTa là $mauBai, lệch $lech/255 so với màu mẫu $mauChuan — '
      'quá mức cho phép $choPhep/255 (${saiSoPct.toStringAsFixed(0)}%).',
    );
  }
}

/// Khoá của một mục trong manifest bố cục: `id` của CHÍNH CHECKPOINT.
///
/// KHÔNG dùng test_id: test_id do materializer sinh lúc bung ma trận nên không tồn tại
/// ở tầng soạn đề — nướng số chuẩn ngược lại sẽ không tìm thấy dòng nào để gán.
String _khoaBoCuc(Map<String, dynamic> c, Map<String, dynamic> checkpoint) {
  final id = _text(checkpoint, 'id');
  return id.isNotEmpty ? id : _text(c, 'test_id');
}

/// Khung của thành phần theo pixel LOGIC. Trả null khi không tìm thấy thành phần —
/// đó là lỗi thiếu nội dung, tiêu chí nội dung đã bắt riêng.
Rect? _khungThanhPhan(WidgetTester tester, Map<String, dynamic> target) {
  final finder = _finder(target);
  if (finder.evaluate().isEmpty) return null;
  try {
    return tester.getRect(finder.first);
  } catch (_) {
    return null;
  }
}

int _mauTuChuoi(String hex) {
  final s = hex.replaceAll('#', '').trim();
  return int.parse(s.length == 8 ? s.substring(2) : s, radix: 16);
}

/// Kích thước màn hình theo pixel logic.
Size _coManHinh(WidgetTester tester) {
  final r = tester.view.devicePixelRatio;
  final kt = tester.view.physicalSize;
  return Size(kt.width / r, kt.height / r);
}

/// VỊ TRÍ: so tâm thành phần với tâm trên ảnh mẫu. Sai số khai theo % chiều rộng
/// (trục X) và % chiều cao (trục Y) của màn hình, nên đổi viewport không làm lệch
/// chính sách chấm.
Future<void> _assertComponentPosition(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Duration timeout,
) async {
  final target = _asMap(checkpoint['target']);
  if (target.isEmpty) {
    throw ArgumentError('Checkpoint component_position thiếu target.');
  }
  final mongDoi = _asMap(checkpoint['expect']);
  if (mongDoi['center_x'] == null || mongDoi['center_y'] == null) {
    throw StateError(
      'Tiêu chí vị trí chưa có giá trị chuẩn — hãy capture lại oracle rồi publish lại.',
    );
  }
  final moTa = _moTaTarget(target);
  await _waitUntil(
    tester,
    () => _finder(target).evaluate().isNotEmpty,
    timeout,
    'Không thấy $moTa trên màn hình nên không chấm được vị trí.',
  );
  final khung = _khungThanhPhan(tester, target);
  if (khung == null) {
    throw StateError('Không đo được vị trí của $moTa.');
  }
  final man = _coManHinh(tester);
  final saiSo = _double(checkpoint['tolerance_pct'], 5) / 100;
  final choPhepX = man.width * saiSo;
  final choPhepY = man.height * saiSo;
  final lechX = (khung.center.dx - _double(mongDoi['center_x'], 0)).abs();
  final lechY = (khung.center.dy - _double(mongDoi['center_y'], 0)).abs();
  if (lechX > choPhepX || lechY > choPhepY) {
    throw StateError(
      'Vị trí $moTa lệch ${lechX.toStringAsFixed(0)}px ngang và '
      '${lechY.toStringAsFixed(0)}px dọc so với ảnh mẫu, quá mức cho phép '
      '${choPhepX.toStringAsFixed(0)}x${choPhepY.toStringAsFixed(0)}px '
      '(${(saiSo * 100).toStringAsFixed(0)}% màn hình).',
    );
  }
}

/// MÀU SẮC: so màu chính của thành phần với màu trên ảnh mẫu. Sai số khai theo %
/// của 255 trên TỪNG kênh R/G/B — 5% ~ ±13, đủ chặt để lệch một nấc Material shade
/// vẫn bị bắt (green.shade500 -> shade600 lệch 5.9%).
Future<void> _assertComponentColor(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Duration timeout,
) async {
  final target = _asMap(checkpoint['target']);
  if (target.isEmpty) {
    throw ArgumentError('Checkpoint component_color thiếu target.');
  }
  final mauChuan = _text(_asMap(checkpoint['expect']), 'color');
  if (mauChuan.isEmpty) {
    throw StateError(
      'Tiêu chí màu chưa có giá trị chuẩn — hãy capture lại oracle rồi publish lại.',
    );
  }
  final moTa = _moTaTarget(target);
  await _waitUntil(
    tester,
    () => _finder(target).evaluate().isNotEmpty,
    timeout,
    'Không thấy $moTa trên màn hình nên không chấm được màu.',
  );
  final khung = _khungThanhPhan(tester, target);
  if (khung == null) throw StateError('Không đo được khung của $moTa.');
  if (!await _baoDamAnhCuoi(tester)) {
    throw StateError('Không chụp được màn hình để lấy màu của $moTa.');
  }
  final man = _coManHinh(tester);
  final tiLe = man.width > 0 ? _anhCuoiW / man.width : 1.0;
  final mauBai = _mauChinhTrongVung(khung, tiLe);
  if (mauBai == null) {
    throw StateError(
      'Vùng của $moTa không có pixel nào khác màu nền để lấy màu.',
    );
  }
  _soMau(
    mauChuan,
    mauBai,
    _double(checkpoint['tolerance_pct'], 5),
    'Màu của $moTa',
  );
}

/// Đo vị trí + màu chuẩn của mọi thành phần được chấm giao diện, ghi cạnh
/// captured-output.db. GoldenOracleCaptureService nhặt về rồi nướng vào chính
/// checkpoint trong behavior_plan.json — người ra đề không phải gõ tay toạ độ nào.
Future<void> _luuBoCucChuan(
  WidgetTester tester,
  List<Map<String, dynamic>> cases,
) async {
  final outputPath = Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '';
  if (outputPath.isEmpty) return;
  const loaiGiaoDien = {
    'component_present',
    'component_position',
    'layout_relation',
    'component_color',
  };
  // LUÔN chụp ảnh khi capture oracle, kể cả khi đề chưa có tiêu chí màu nào: capture
  // vốn đã chụp một tấm cho ảnh chuẩn nên thêm phép đo màu gần như miễn phí, mà nhờ
  // vậy người ra đề bật chấm màu sau này thì số chuẩn đã nằm sẵn trong manifest.
  await _baoDamAnhCuoi(tester);
  final man = _coManHinh(tester);
  final tiLe = man.width > 0 && _anhCuoiW > 0 ? _anhCuoiW / man.width : 1.0;

  final thanhPhan = <String, dynamic>{};
  final mauApp = _mauChuDao(tester);
  for (final c in cases) {
    final checkpoint = _asMap(c['checkpoint']);
    final kind = _text(checkpoint, 'kind');
    final khoa = _khoaBoCuc(c, checkpoint);
    if (khoa.isEmpty) continue;
    // Màu chủ đạo không gắn với thành phần nào nên không có khung để đo.
    if (kind == 'theme_color') {
      if (mauApp != null) thanhPhan[khoa] = <String, dynamic>{'color': mauApp};
      continue;
    }
    if (!loaiGiaoDien.contains(kind)) continue;
    if (kind == 'layout_relation') {
      final first = _khungThanhPhan(tester, _asMap(checkpoint['target']));
      final second = _khungThanhPhan(tester, _asMap(checkpoint['relative_to']));
      if (first == null || second == null) continue;
      final configured = _text(checkpoint, 'relation', 'auto');
      thanhPhan[khoa] = <String, dynamic>{
        'test_id': _text(c, 'test_id'),
        'relation': configured == 'auto'
            ? _deriveLayoutRelation(first, second, man)
            : configured,
      };
      continue;
    }
    final khung = _khungThanhPhan(tester, _asMap(checkpoint['target']));
    if (khung == null) continue;
    thanhPhan[khoa] = <String, dynamic>{
      'test_id': _text(c, 'test_id'),
      'left': khung.left,
      'top': khung.top,
      'width': khung.width,
      'height': khung.height,
      'center_x': khung.center.dx,
      'center_y': khung.center.dy,
      if (_anhCuoi != null) 'color': _mauChinhTrongVung(khung, tiLe),
    };
  }
  if (thanhPhan.isEmpty && _moTaNhanDaThu.isEmpty) return;
  final tep = File(
    p.join(File(outputPath).parent.path, 'captured-layout.json'),
  );
  tep.parent.createSync(recursive: true);
  tep.writeAsStringSync(
    jsonEncode(<String, dynamic>{
      'schema_version': '1.0',
      'execution_code': _text(
        cases.first,
        'execution_code',
        _text(cases.first, 'scenario_code'),
      ),
      'screen': <String, dynamic>{
        'width': man.width,
        'height': man.height,
        'device_pixel_ratio': tester.view.devicePixelRatio,
      },
      'components': thanhPhan,
      'targets': _moTaNhanDaThu,
    }),
  );
  stdout.writeln(
    'Đã đo bố cục chuẩn: ${thanhPhan.length} thành phần, '
    '${_moTaNhanDaThu.length} nhãn có đường dự phòng.',
  );
}

/// So màn hình hiện tại với ảnh chuẩn của luồng. Mỗi pixel lệch quá 16/255 ở bất kỳ
/// kênh màu nào tính là KHÁC; tỉ lệ pixel giống phải đạt ngưỡng của checkpoint.
Future<void> _assertScreenMatch(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  String executionCode,
) async {
  final goldenFile = File(
    p.join('test', 'fixtures', 'screens', '$executionCode.png'),
  );
  if (!goldenFile.existsSync()) {
    throw StateError(
      'Bộ đề thiếu ảnh chuẩn ${goldenFile.path} — hãy capture lại oracle rồi publish lại.',
    );
  }
  final threshold = _double(checkpoint['threshold'], 0.85);
  double? ratio;
  await tester.runAsync(() async {
    final golden = await _decodeRgba(goldenFile.readAsBytesSync());
    final current = await _captureScreenImage(tester);
    if (current == null) {
      throw StateError('Không chụp được màn hình bài làm để so với ảnh mẫu.');
    }
    final actual = await current.toByteData(format: ui.ImageByteFormat.rawRgba);
    if (actual == null)
      throw StateError('Không đọc được ảnh màn hình bài làm.');
    if (golden.$2 != current.width || golden.$3 != current.height) {
      throw StateError(
        'Kích thước màn hình ${current.width}x${current.height} khác ảnh mẫu '
        '${golden.$2}x${golden.$3} — viewport của bộ đề đã đổi sau khi chụp ảnh chuẩn.',
      );
    }
    ratio = _matchRatio(golden.$1, actual.buffer.asUint8List());
  });
  final measured = ratio ?? 0;
  if (measured < threshold) {
    throw StateError(
      'Bố cục khớp ${(measured * 100).toStringAsFixed(1)}% với ảnh mẫu, '
      'dưới ngưỡng ${(threshold * 100).toStringAsFixed(0)}%.',
    );
  }
}

/// Giải mã PNG về (bytes RGBA, rộng, cao).
Future<(Uint8List, int, int)> _decodeRgba(Uint8List png) async {
  final codec = await ui.instantiateImageCodec(png);
  final frame = await codec.getNextFrame();
  final data = await frame.image.toByteData(format: ui.ImageByteFormat.rawRgba);
  if (data == null) throw StateError('Không giải mã được ảnh mẫu.');
  return (data.buffer.asUint8List(), frame.image.width, frame.image.height);
}

double _matchRatio(Uint8List a, Uint8List b) {
  final length = a.length < b.length ? a.length : b.length;
  final pixels = length ~/ 4;
  if (pixels == 0) return 0;
  var same = 0;
  for (var i = 0; i < pixels; i++) {
    final o = i * 4;
    if ((a[o] - b[o]).abs() <= 16 &&
        (a[o + 1] - b[o + 1]).abs() <= 16 &&
        (a[o + 2] - b[o + 2]).abs() <= 16) {
      same++;
    }
  }
  return same / pixels;
}

Future<void> _captureOutputDatabase(Map<String, dynamic> contract) async {
  final outputPath = Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '';
  if (outputPath.isEmpty) return;
  if (!_bool(contract['enabled'], false)) {
    throw StateError(
      'Không thể capture Output DB khi database_contract.enabled=false.',
    );
  }

  final sourcePath = await _databasePath(contract);
  if (!File(sourcePath).existsSync()) {
    throw StateError(
      'Không tìm thấy SQLite sau khi replay Golden: $sourcePath',
    );
  }
  final output = File(outputPath);
  await output.parent.create(recursive: true);
  if (output.existsSync()) await output.delete();

  // VACUUM INTO tạo một snapshot nhất quán ngay cả khi Golden App vẫn giữ kết nối
  // SQLite/WAL. Copy file thô ở thời điểm này có thể bỏ sót dữ liệu trong WAL.
  final database = await databaseFactoryFfiNoIsolate.openDatabase(sourcePath);
  try {
    final escaped = output.absolute.path.replaceAll("'", "''");
    await database.execute("VACUUM INTO '$escaped'");
  } finally {
    await database.close();
  }
  if (!output.existsSync() || output.lengthSync() == 0) {
    throw StateError('Runner không sinh được Output DB tại $outputPath');
  }

  final metadataPath =
      Platform.environment['GRADER_CAPTURE_METADATA_PATH'] ?? '';
  if (metadataPath.isNotEmpty) {
    final metadata = File(metadataPath);
    await metadata.parent.create(recursive: true);
    await metadata.writeAsString(
      jsonEncode(<String, dynamic>{
        'schema_version': '1.0',
        'source_database': sourcePath,
        'output_database': output.absolute.path,
      }),
    );
  }
  stdout.writeln(
    '$_captureMarker${jsonEncode(<String, dynamic>{'captured': true, 'output_database': output.absolute.path})}',
  );
}

Future<Finder> _waitForTarget(
  WidgetTester tester,
  Map<String, dynamic> target,
  Duration timeout,
) async {
  if (target.isEmpty) throw ArgumentError('Action thiếu semantic target.');
  await _waitUntil(
    tester,
    () => _finder(target, duPhong: true).evaluate().isNotEmpty,
    timeout,
    'Không tìm thấy semantic target: $target',
  );
  final finder = _finder(target, duPhong: true);
  if (_dangThuOracle) {
    final nhan = _text(target, 'label');
    if (nhan.isNotEmpty && !_moTaNhanDaThu.containsKey(nhan)) {
      final moTa = _moTaDuPhong(nhan);
      if (moTa != null) _moTaNhanDaThu[nhan] = moTa;
    }
  }
  final index = _int(target['index'], 0);
  return index <= 0 ? finder.first : finder.at(index);
}

/// Mô tả dự phòng thu được trong lúc chạy Golden, gom theo nhãn.
///
/// Phải thu ĐÚNG LÚC bấm, không thu ở cuối kịch bản: cuối kịch bản màn hình đã chuyển
/// đi, nhãn của màn trước không còn trên cây nên đo ra rỗng.
final Map<String, dynamic> _moTaNhanDaThu = <String, dynamic>{};
final bool _dangThuOracle =
    (Platform.environment['GRADER_CAPTURE_OUTPUT_PATH'] ?? '').isNotEmpty;

/// Tìm theo mô tả DỰ PHÒNG khi nhãn của đề không khớp widget nào.
///
/// TỪ CHỐI khi khớp nhiều hơn một. Nhãn chính được phép lấy `.first` vì nhãn đã khoanh
/// đúng nhóm rồi, thứ tự trong nhóm còn nghĩa; còn ở đây thứ tự phụ thuộc cây widget
/// của từng bài nộp nên lấy bừa là chấm SAI ÂM THẦM — tệ hơn hẳn báo không thấy.
Finder? _timDuPhong(List<dynamic> danhSach) {
  for (final raw in danhSach) {
    final m = _asMap(raw);
    if (m.isEmpty) continue;
    // So bằng TÊN kiểu chứ không tra bảng Type: đề sau dùng widget nào cũng chạy,
    // khỏi phải sửa engine rồi dựng lại ảnh nền.
    final kieu = _text(m, 'type');
    final ma = _int(m['icon'], -1);
    final chuCon = _text(m, 'child_text');
    Finder? f;
    if (kieu.isNotEmpty) {
      f = find.byWidgetPredicate((w) => w.runtimeType.toString() == kieu);
    }
    if (ma >= 0) {
      final icon = find.byWidgetPredicate(
        (w) => w is Icon && w.icon?.codePoint == ma,
      );
      f = f == null ? icon : find.ancestor(of: icon, matching: f);
    }
    if (chuCon.isNotEmpty) {
      final chu = find.text(chuCon);
      f = f == null ? chu : find.ancestor(of: chu, matching: f);
    }
    if (f == null) continue;
    if (f.evaluate().length == 1) return f;
  }
  return null;
}

/// Mô tả dự phòng của một nhãn, đo trên Golden lúc thu oracle.
///
/// Chỉ trả về khi mô tả khớp ĐÚNG MỘT widget. Nhập nhằng ngay ở bài chuẩn thì đừng đẻ
/// ra đường lui — nút xóa lặp theo từng dòng danh sách rơi vào đúng ca này và tự bị loại.
Map<String, dynamic>? _moTaDuPhong(String nhan) {
  final goc = find.bySemanticsLabel(nhan);
  if (goc.evaluate().length != 1) return null;
  final ungVien = <String>[];
  int? maIcon;
  void thu(Element e) {
    final w = e.widget;
    final ten = w.runtimeType.toString();
    if (w is Icon) maIcon ??= w.icon?.codePoint;
    // Bỏ kiểu riêng tư (_Abc) và generic (Foo<Bar>): tên bị méo nên so chuỗi không chắc.
    if (!ten.startsWith('_') && !ten.contains('<')) ungVien.add(ten);
    e.visitChildren(thu);
  }

  goc.evaluate().single.visitChildren(thu);
  Finder theoKieu(String ten) =>
      find.byWidgetPredicate((w) => w.runtimeType.toString() == ten);
  // Nấc 1: một mình kiểu đã duy nhất.
  for (final ten in ungVien) {
    if (theoKieu(ten).evaluate().length == 1) {
      return <String, dynamic>{'type': ten};
    }
  }
  // Nấc 2: kiểu chưa đủ hẹp thì kèm mã icon con (hai FAB khác icon vẫn tách được).
  final ma = maIcon;
  if (ma != null) {
    final icon = find.byWidgetPredicate(
      (w) => w is Icon && w.icon?.codePoint == ma,
    );
    for (final ten in ungVien) {
      if (find.ancestor(of: icon, matching: theoKieu(ten)).evaluate().length ==
          1) {
        return <String, dynamic>{'type': ten, 'icon': ma};
      }
    }
  }
  return null;
}

bool _locatorDungDuoc(
  Finder finder,
  Map<String, dynamic> target, {
  required bool action,
}) {
  final count = finder.evaluate().length;
  if (!action) return count > 0;
  // Action khong duoc ngam lay .first khi locator khop nhieu widget. Chi cho phep
  // nhieu ket qua neu bo cham chu dong khai index va index do ton tai.
  if (target.containsKey('index')) {
    final index = _int(target['index'], 0);
    return index >= 0 && index < count;
  }
  return count == 1;
}

void _assertRouteState(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  _RouteTracker tracker,
) {
  final expected = <String, dynamic>{
    ...checkpoint,
    ..._asMap(checkpoint['expect']),
  };
  var checked = 0;
  if (expected['uri'] != null) {
    final wanted = expected['uri'].toString();
    expect(
      _routeMatches(tracker.current, wanted),
      isTrue,
      reason: 'Route hiện tại "${tracker.current}" không khớp URI "$wanted".',
    );
    checked++;
  }
  if (expected['path'] != null) {
    expect(
      tracker.current.path,
      expected['path'].toString(),
      reason: 'Path hiện tại không đúng.',
    );
    checked++;
  }
  if (expected['fragment'] != null) {
    expect(
      tracker.current.fragment,
      expected['fragment'].toString(),
      reason: 'Fragment hiện tại không đúng.',
    );
    checked++;
  }
  if (expected['query'] != null) {
    final wanted = _asMap(
      expected['query'],
    ).map((key, value) => MapEntry(key, value?.toString() ?? ''));
    expect(
      tracker.current.queryParameters,
      wanted,
      reason: 'Query parameters của route không đúng.',
    );
    checked++;
  }
  if (expected['can_pop'] != null) {
    final navigatorCanPop = find.byType(Navigator).evaluate().any((element) {
      final state = element is StatefulElement ? element.state : null;
      return state is NavigatorState && state.canPop();
    });
    expect(
      tracker.canBack || navigatorCanPop,
      _bool(expected['can_pop'], false),
      reason: 'Trạng thái canPop của route không đúng.',
    );
    checked++;
  }
  if (expected['history_length'] != null) {
    expect(
      tracker.history.length,
      _int(expected['history_length'], 0),
      reason: 'Độ dài browser history không đúng.',
    );
    checked++;
  }
  if (expected['history_index'] != null) {
    expect(
      tracker.index,
      _int(expected['history_index'], 0),
      reason: 'Vị trí hiện tại trong browser history không đúng.',
    );
    checked++;
  }
  if (expected['can_forward'] != null) {
    expect(
      tracker.canForward,
      _bool(expected['can_forward'], false),
      reason: 'Trạng thái có thể browser-forward không đúng.',
    );
    checked++;
  }
  if (checked == 0) {
    throw StateError(
      'Checkpoint route_state không có giá trị nào để kiểm tra.',
    );
  }
}

Future<void> _assertLayoutRelation(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Duration timeout,
) async {
  final firstTarget = _asMap(checkpoint['target']);
  final secondTarget = _asMap(checkpoint['relative_to']);
  if (firstTarget.isEmpty || secondTarget.isEmpty) {
    throw ArgumentError(
      'Checkpoint layout_relation thiếu target hoặc relative_to.',
    );
  }
  await _waitUntil(
    tester,
    () =>
        _finder(firstTarget).evaluate().isNotEmpty &&
        _finder(secondTarget).evaluate().isNotEmpty,
    timeout,
    'Không tìm đủ hai thành phần để chấm quan hệ bố cục.',
  );
  final first = _khungThanhPhan(tester, firstTarget);
  final second = _khungThanhPhan(tester, secondTarget);
  if (first == null || second == null) {
    throw StateError('Không đo được khung của hai thành phần bố cục.');
  }
  var relation = _text(checkpoint, 'relation', 'auto');
  if (relation == 'auto')
    relation = _text(_asMap(checkpoint['expect']), 'relation');
  if (relation.isEmpty || relation == 'auto') {
    throw StateError(
      'Quan hệ bố cục tự động chưa có oracle — hãy capture lại Golden rồi publish lại.',
    );
  }
  final screen = _coManHinh(tester);
  final tolerance = _double(checkpoint['tolerance_pct'], 5) / 100;
  final tolX = screen.width * tolerance;
  final tolY = screen.height * tolerance;
  final intersection = first.intersect(second);
  final overlapArea = intersection.isEmpty
      ? 0.0
      : intersection.width * intersection.height;
  final smallerArea = mathMin(
    first.width * first.height,
    second.width * second.height,
  );
  final overlapRatio = smallerArea <= 0 ? 0.0 : overlapArea / smallerArea;

  final passed = switch (relation) {
    'above' => first.bottom <= second.top + tolY,
    'below' => first.top >= second.bottom - tolY,
    'left_of' => first.right <= second.left + tolX,
    'right_of' => first.left >= second.right - tolX,
    'same_row' => (first.center.dy - second.center.dy).abs() <= tolY,
    'same_column' => (first.center.dx - second.center.dx).abs() <= tolX,
    'inside' =>
      second.inflate(mathMax(tolX, tolY)).contains(first.topLeft) &&
          second.inflate(mathMax(tolX, tolY)).contains(first.bottomRight),
    'contains' =>
      first.inflate(mathMax(tolX, tolY)).contains(second.topLeft) &&
          first.inflate(mathMax(tolX, tolY)).contains(second.bottomRight),
    'overlap' =>
      overlapRatio >= _double(checkpoint['min_overlap_pct'], 10) / 100,
    'not_overlap' =>
      overlapRatio <= _double(checkpoint['max_overlap_pct'], 5) / 100,
    'wider_than' => first.width + tolX >= second.width,
    'taller_than' => first.height + tolY >= second.height,
    _ => throw ArgumentError('Quan hệ bố cục không hỗ trợ: $relation'),
  };
  if (!passed) {
    throw StateError(
      '${_moTaTarget(firstTarget)} không đạt quan hệ "$relation" với '
      '${_moTaTarget(secondTarget)} (sai số ${(tolerance * 100).toStringAsFixed(1)}%).',
    );
  }
}

double mathMin(double a, double b) => a < b ? a : b;
double mathMax(double a, double b) => a > b ? a : b;

String _deriveLayoutRelation(Rect first, Rect second, Size screen) {
  final tolX = screen.width * 0.05;
  final tolY = screen.height * 0.05;
  if (second.inflate(mathMax(tolX, tolY)).contains(first.topLeft) &&
      second.inflate(mathMax(tolX, tolY)).contains(first.bottomRight)) {
    return 'inside';
  }
  if (first.inflate(mathMax(tolX, tolY)).contains(second.topLeft) &&
      first.inflate(mathMax(tolX, tolY)).contains(second.bottomRight)) {
    return 'contains';
  }
  if (first.bottom <= second.top &&
      first.right >= second.left &&
      first.left <= second.right) {
    return 'above';
  }
  if (first.top >= second.bottom &&
      first.right >= second.left &&
      first.left <= second.right) {
    return 'below';
  }
  if (first.right <= second.left &&
      first.bottom >= second.top &&
      first.top <= second.bottom) {
    return 'left_of';
  }
  if (first.left >= second.right &&
      first.bottom >= second.top &&
      first.top <= second.bottom) {
    return 'right_of';
  }
  if ((first.center.dy - second.center.dy).abs() <= tolY) return 'same_row';
  if ((first.center.dx - second.center.dx).abs() <= tolX) return 'same_column';
  return first.overlaps(second)
      ? 'overlap'
      : (first.center.dy < second.center.dy ? 'above' : 'below');
}

Finder _finder(Map<String, dynamic> target, {bool duPhong = false}) {
  // Semantics.identifier la dinh danh on dinh cua semantics tree va duoc Flutter Web
  // phoi thanh flt-semantics-identifier. Thu semantics finder truoc; fallback ValueKey
  // giu tuong thich voi cac bo cham cu tung dung semanticId lam key cua widget.
  for (final keyName in const ['semanticId', 'semantic_id']) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final semantics = find.bySemanticsIdentifier(value);
      if (_locatorDungDuoc(semantics, target, action: duPhong))
        return semantics;
      final finder = find.byKey(ValueKey<String>(value));
      if (_locatorDungDuoc(finder, target, action: duPhong)) return finder;
    }
  }
  for (final keyName in const ['valueKey', 'value_key', 'key']) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final finder = find.byKey(ValueKey<String>(value));
      if (_locatorDungDuoc(finder, target, action: duPhong)) return finder;
    }
  }
  final label = _text(target, 'label');
  final hint = _text(target, 'hint');
  if (label.isNotEmpty) {
    final semantics = find.bySemanticsLabel(label);
    if (_locatorDungDuoc(semantics, target, action: duPhong)) return semantics;
  }
  // NHÃN HAI DÒNG. Dòng danh sách (ListTile) gộp title + subtitle thành một nhãn
  // ngăn bởi xuống dòng; recorder đời cũ tách nhầm thành label + hint (phép tách đó
  // chỉ đúng cho ô nhập). Ghép lại để so tuyệt đối — thiếu nhánh này thì bấm vào
  // dòng danh sách không bao giờ tìm thấy đích, cả kịch bản EDIT chết theo.
  if (label.isNotEmpty && hint.isNotEmpty) {
    final gop = find.bySemanticsLabel('$label\n$hint');
    if (_locatorDungDuoc(gop, target, action: duPhong)) return gop;
  }
  if (label.isNotEmpty || hint.isNotEmpty) {
    final finder = find.byWidgetPredicate((widget) {
      final decoration = switch (widget) {
        TextField field => field.decoration,
        _ => null,
      };
      // `label` ghi được từ web phải khớp CẢ hintText: Flutter web phơi hintText thành
      // aria-label nên recorder ghi là `label`, còn flutter_test thì ô ĐÃ CÓ CHỮ không
      // hiện hint trong semantics nữa — chỉ decoration còn giữ. Thiếu vế này thì mọi
      // enter_text trên màn Sửa (ô có sẵn nội dung) đều không tìm thấy đích.
      return decoration != null &&
          (label.isEmpty ||
              decoration.labelText == label ||
              decoration.hintText == label) &&
          (hint.isEmpty ||
              decoration.hintText == hint ||
              decoration.labelText == hint);
    });
    if (_locatorDungDuoc(finder, target, action: duPhong)) return finder;
  }
  final text = _text(target, 'text');
  if (text.isNotEmpty) {
    final finder = find.text(text);
    if (_locatorDungDuoc(finder, target, action: duPhong)) return finder;
  }
  // TIỀN TỐ VĂN BẢN. Hợp đồng nhãn của đề khai `text_prefix` cho những dòng mà phần
  // đuôi thay đổi theo dữ liệu — ví dụ "Tổng tháng: 608.000 ₫". find.text so khớp
  // TUYỆT ĐỐI nên không dùng được ở đây; thiếu nhánh này thì đúng những mục hợp đồng
  // ấy không có cách nào kiểm.
  final textPrefix = _text(target, 'text_prefix');
  if (textPrefix.isNotEmpty) {
    final finder = find.byWidgetPredicate(
      (widget) => widget is Text && (widget.data ?? '').startsWith(textPrefix),
    );
    if (_locatorDungDuoc(finder, target, action: duPhong)) return finder;
  }
  // CÓ khóa nhận diện nhưng CHƯA khớp widget nào ở nhịp poll này (ví dụ label khai
  // đúng mà màn hình chưa mở, hoặc app dùng hint thay label). Đây là "không thấy",
  // KHÔNG phải "target khai thiếu khóa" — phải trả finder rỗng để _waitUntil còn
  // poll tiếp và hết giờ báo đúng bản chất. Trước đây nhánh này rơi xuống throw
  // bên dưới: câu lỗi đổ oan cho đề ("Target không có ...") và vòng chờ chết ngay
  // nhịp đầu — đúng ca UI_ADD_CHECKPOINT_2/3/9 của PE_PRM393_SP27.
  // ĐƯỜNG DỰ PHÒNG. Chỉ mở cho action (bấm/gõ để ĐI TỚI màn kế), KHÔNG mở cho
  // checkpoint: thiếu nhãn thì tiêu chí nhãn phải trượt, nhưng phần chức năng phía sau
  // vẫn phải chấm được. Thiếu nhánh này thì một cái nút quên nhãn kéo sập cả màn phía
  // sau — 14/34 trọng số của PE_PRM393_SP27 nằm sau đúng một nút.
  if (duPhong) {
    final f = _timDuPhong(_asList(target['fallback']));
    if (f != null) return f;
  }
  const khoaNhanDien = <String>[
    'semanticId',
    'semantic_id',
    'valueKey',
    'value_key',
    'key',
    'label',
    'hint',
    'text',
    'text_prefix',
  ];
  if (khoaNhanDien.any((k) => _text(target, k).isNotEmpty)) {
    return find.byWidgetPredicate((_) => false);
  }
  throw ArgumentError(
    'Target không có semanticId/key/label/hint/text/text_prefix: $target',
  );
}

Future<void> _waitUntil(
  WidgetTester tester,
  bool Function() condition,
  Duration timeout,
  String message,
) async {
  final watch = Stopwatch()..start();
  while (watch.elapsed < timeout) {
    if (condition()) return;
    await _allowExternalAsync(tester);
    await tester.pump(const Duration(milliseconds: 50));
    _throwPendingException(tester, 'wait');
  }
  throw TimeoutException(message, timeout);
}

Future<void> _boundedPump(WidgetTester tester, Duration timeout) async {
  final watch = Stopwatch()..start();
  var quietFrames = 0;
  while (watch.elapsed < timeout && quietFrames < 3) {
    await _allowExternalAsync(tester);
    await tester.pump(const Duration(milliseconds: 50));
    _throwPendingException(tester, 'pump');
    if (tester.binding.hasScheduledFrame) {
      quietFrames = 0;
    } else {
      quietFrames++;
    }
  }
}

/// Lets file and SQLite futures advance outside the fake clock owned by
/// testWidgets, then returns control to deterministic widget pumping.
Future<void> _allowExternalAsync(WidgetTester tester) async {
  await tester.runAsync(
    () => Future<void>.delayed(const Duration(milliseconds: 15)),
  );
}

void _throwPendingException(WidgetTester tester, String stage) {
  final error = tester.takeException();
  if (error != null) throw StateError('$stage: $error');
}

Map<String, dynamic> _readObject(String primary, String fallback) {
  final file = File(primary).existsSync() ? File(primary) : File(fallback);
  if (!file.existsSync())
    throw StateError('Không tìm thấy behavior_plan.json.');
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

bool _bool(Object? value, bool fallback) {
  if (value == null) return fallback;
  if (value is bool) return value;
  return value.toString().toLowerCase() == 'true';
}

int _int(Object? value, int fallback) {
  if (value is num) return value.toInt();
  return int.tryParse('$value') ?? fallback;
}

double _double(Object? value, double fallback) {
  if (value is num) return value.toDouble();
  return double.tryParse('$value') ?? fallback;
}
