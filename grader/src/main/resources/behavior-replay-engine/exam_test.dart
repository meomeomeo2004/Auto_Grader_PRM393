import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'dart:ui' as ui;
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart' show FontLoader;
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import '../lib/main.dart' as student_app;

const _observationMarker = '###GRADER_OBS###';
const _checkpointMarker = '###RAR_CHECKPOINT###';
const _captureMarker = '###RAR_CAPTURE###';
const _stageMarker = '###GRADER_STAGE###';

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

  try {
    sqfliteFfiInit();
    // Flutter widget tests run in a headless sandbox. The regular FFI factory
    // delegates work to a background isolate, which can remain pending forever
    // in constrained Docker environments. Keep all SQLite calls in the test
    // isolate so Golden and student replays have deterministic timeouts.
    databaseFactory = databaseFactoryFfiNoIsolate;
    _applyViewport(tester, _asMap(testCase['viewport']));
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });
    if (_bool(_asMap(testCase['initial_state'])['reset_storage'], true)) {
      await tester.runAsync(() => _resetDatabase(databaseContract));
    }
    await tester.runAsync(_loadRealFonts);
    stdout.writeln('${_stageMarker}STUDENT_APP_BOOT');
    await _bootStudentApp(tester, timeout);

    for (final raw in _asList(testCase['steps'])) {
      final step = _asMap(raw);
      stdout.writeln('${_stageMarker}STUDENT_UI_ACTION');
      await _runStep(tester, step, timeout);
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
) async {
  final action = _text(step, 'action');
  final timeout = Duration(
    milliseconds: _int(step['timeout_ms'], defaultTimeout.inMilliseconds),
  );
  switch (action) {
    case 'boot':
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

  // TRẠNG THÁI WIDGET — công tắc bật hay tắt, dải trượt bao nhiêu, ô nhập có dùng
  // bàn phím số không, nút có đúng loại không. Khác component_present ở chỗ: cái kia
  // chỉ hỏi "có trên màn hình không", cái này đọc giá trị thật bên trong widget.
  if (kind == 'widget_state') {
    await _assertWidgetState(tester, checkpoint, timeout);
    return;
  }

  // KIEU CHU cua mot dong chu cu the, va GIA TRI trong bang chu de cua app.
  if (kind == 'text_style' || kind == 'theme_value') {
    await _assertKieuChuHoacTheme(
      tester,
      checkpoint,
      timeout,
      laKieuChu: kind == 'text_style',
    );
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
    // TRẠNG THÁI WIDGET: đọc ngay tại đây, cùng khoảnh khắc với vị trí và màu.
    // Không có thao tác nào xen giữa chỗ này và vòng assert nên giá trị đo được
    // đúng bằng giá trị mà tiêu chí sẽ nhìn thấy lúc chấm.
    if (kind == 'widget_state' || kind == 'text_style' || kind == 'theme_value') {
      try {
        final giaTri = switch (kind) {
          'text_style' => _docKieuChu(tester, checkpoint),
          'theme_value' => _docGiaTriTheme(tester, _text(checkpoint, 'property')),
          _ => _docTrangThai(tester, checkpoint),
        };
        if (giaTri != null) {
          thanhPhan[khoa] = <String, dynamic>{
            'test_id': _text(c, 'test_id'),
            'observed': _chuanHoaTrangThai(giaTri),
          };
        }
      } catch (_) {
        // Tiêu chí hỏng thì vòng assert ngay sau sẽ báo lỗi tử tế. Ở đây chỉ cần
        // KHÔNG ghi gì, để lúc chấm báo "chưa có giá trị chuẩn" thay vì nướng bừa.
      }
      continue;
    }
    if (!loaiGiaoDien.contains(kind)) continue;
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

/// Tìm widget MANG THUỘC TÍNH từ một điểm neo — ba nấc: chính nó → con cháu → tổ tiên.
///
/// Vì sao phải cả hai chiều: điểm neo hầu như không bao giờ LÀ widget cần đọc. Nhãn ngữ
/// nghĩa do đề bọc ngoài thì widget nằm BÊN DƯỚI (Slider, Image); nhãn do chính widget
/// sinh ra thì điểm neo lại nằm bên trong nên widget ở BÊN TRÊN (Icon, TextField, các
/// loại nút, các dòng ListTile). Đo ở sa bàn: cần đủ hai chiều, và chỉ hai chiều là đủ.
///
/// KHÔNG leo từng nấc rồi quét cả cây con của mỗi nấc. Cách đó đã thử và nó biến
/// "không thấy ở gần" thành "tóm được cái gì đó ở xa": hỏi "nút này có phải TextButton
/// không" thì nó leo 39 nấc, vớ một TextButton ở góc màn hình khác rồi trả lời CÓ —
/// đúng kiểu chấm sai âm thầm. Tìm theo tổ tiên thuần thì không bao giờ lạc sang nhánh
/// anh em, nên chính xác mà không cần ngưỡng độ sâu nào.
Finder _timWidgetMangThuocTinh(Finder neo, String kieu, String moTaNeo) {
  final chinh = find.byWidgetPredicate(
    (widget) => widget.runtimeType.toString() == kieu,
  );
  final goc = neo.evaluate();
  if (goc.isEmpty) {
    throw StateError('Không thấy $moTaNeo trên màn hình nên không đọc được trạng thái.');
  }
  if (goc.first.widget.runtimeType.toString() == kieu) return neo.first;

  final duoi = find.descendant(of: neo, matching: chinh);
  final soDuoi = duoi.evaluate().length;
  if (soDuoi == 1) return duoi;
  if (soDuoi > 1) {
    throw StateError(
      'Có $soDuoi widget $kieu bên trong $moTaNeo — tiêu chí phải trỏ vào một cái cụ thể hơn.',
    );
  }

  final tren = find.ancestor(of: neo, matching: chinh);
  final soTren = tren.evaluate().length;
  if (soTren == 1) return tren;
  if (soTren > 1) {
    throw StateError(
      'Có $soTren widget $kieu bao quanh $moTaNeo — tiêu chí phải trỏ vào một cái cụ thể hơn.',
    );
  }
  throw StateError(
    'Không tìm thấy widget $kieu nào ở $moTaNeo (đã tìm cả bên trong lẫn bao quanh). '
    'Bài làm có thể đã dùng loại widget khác.',
  );
}

/// Đọc MỘT thuộc tính của widget. Bảng trắng theo tên thuộc tính.
///
/// Thuộc tính không có trong bảng là lỗi ĐỀ nên phải ném: trả null rồi so với null sẽ
/// thành một tiêu chí luôn đạt, tức là thêm một nguồn hỏng-im-lặng mới.
Object? _docThuocTinhWidget(Widget w, String ten) {
  switch (ten) {
    case 'ton_tai':
      // Chỉ cần tìm được widget đúng kiểu là đạt — dùng cho "phải dùng đúng loại nút".
      return true;
    case 'value':
      if (w is Switch) return w.value;
      if (w is SwitchListTile) return w.value;
      if (w is Checkbox) return w.value;
      if (w is CheckboxListTile) return w.value;
      if (w is Slider) return w.value;
      if (w is Radio) return w.value;
      if (w is RadioListTile) return w.value;
      if (w is Text) return w.data;
      break;
    case 'group_value':
      if (w is Radio) return (w as dynamic).groupValue;
      if (w is RadioListTile) return (w as dynamic).groupValue;
      break;
    // CHI chip va radio. Switch/Checkbox co ngay `value` la dung nghia roi; de
    // chung o ca hai cho thi mot cong tac co hai ten thuoc tinh doc ra cung mot
    // gia tri, va nguoi soan de co the dat hai dau diem cho cung mot phep do.
    case 'da_chon':
    case 'selected':
      if (w is FilterChip) return w.selected;
      if (w is ChoiceChip) return w.selected;
      if (w is RadioListTile) return w.value == (w as dynamic).groupValue;
      if (w is Radio) return w.value == (w as dynamic).groupValue;
      break;
    case 'min':
      if (w is Slider) return w.min;
      break;
    case 'max':
      if (w is Slider) return w.max;
      break;
    case 'divisions':
      if (w is Slider) return w.divisions;
      break;
    case 'keyboard_type':
      if (w is TextField) {
        final tho = w.keyboardType?.toString() ?? '';
        // Chuỗi thô là 'TextInputType(name: TextInputType.number, signed: ...)' —
        // rút gọn về 'number' cho người soạn đề đọc được và cho giá trị chuẩn ổn định
        // qua các bản Flutter.
        final khop = RegExp('name: TextInputType[.](' r'\w+' ')').firstMatch(tho);
        return khop == null ? tho : khop.group(1);
      }
      break;
    case 'obscure_text':
      if (w is TextField) return w.obscureText;
      break;
    case 'max_lines':
      if (w is TextField) return w.maxLines;
      break;
    case 'icon_code':
      if (w is Icon) return w.icon?.codePoint;
      break;
    case 'image_source':
      if (w is Image) {
        final nguon = w.image;
        if (nguon is AssetImage) return nguon.assetName;
        if (nguon is NetworkImage) return nguon.url;
        if (nguon is MemoryImage) return 'memory';
        return nguon.runtimeType.toString();
      }
      break;
    case 'current_index':
      if (w is BottomNavigationBar) return w.currentIndex;
      if (w is NavigationBar) return w.selectedIndex;
      break;
    // BAT / KHOA. Nut co onPressed == null la nut xam — kien thuc Chuong 4 that
    // (khoa nut Luu khi form trong). Ke thua cach doc cua _semanticEnabled de hai
    // duong khong bao gio cho ket qua khac nhau.
    case 'enabled':
      if (w is TextField) return w.enabled ?? true;
      if (w is ButtonStyleButton) return w.onPressed != null;
      if (w is IconButton) return w.onPressed != null;
      if (w is FloatingActionButton) return w.onPressed != null;
      if (w is Checkbox) return w.onChanged != null;
      if (w is Switch) return w.onChanged != null;
      if (w is Radio) return w.onChanged != null;
      if (w is Slider) return w.onChanged != null;
      break;
    // NOI DUNG DANG CO trong o nhap (man Sua phai hien san gia tri cu). Nhanh nay
    // chi chay khi _docTrangThai KHONG tim thay EditableText ben duoi — TextField
    // khong khai controller thi w.controller la null, EditableText moi cam controller
    // that.
    case 'noi_dung':
      if (w is EditableText) return w.controller.text;
      if (w is TextField) return w.controller?.text ?? '';
      break;
    default:
      throw ArgumentError(
        'Tiêu chí trạng thái dùng thuộc tính "$ten" mà engine chưa biết đọc.',
      );
  }
  throw ArgumentError(
    'Không đọc được thuộc tính "$ten" trên widget ${w.runtimeType} — '
    'thuộc tính này chưa hỗ trợ cho loại widget đó.',
  );
}

/// Chuẩn hoá giá trị về chuỗi để so: 40.0 và 40 phải được coi là một.
String _chuanHoaTrangThai(Object? v) {
  if (v == null) return '';
  if (v is double && v == v.roundToDouble() && v.abs() < 1e15) {
    return v.toStringAsFixed(0);
  }
  return v.toString();
}

/// Đọc trạng thái hiện tại của widget mà tiêu chí trỏ tới.
///
/// Dùng chung cho cả lúc CHẤM lẫn lúc THU giá trị chuẩn, để hai đường không thể đọc
/// lệch nhau. Tiêu chí khai sai (thiếu tên kiểu, trỏ nhập nhằng, bài dùng widget khác)
/// thì ném — không nuốt thành null rồi biến sai thành đạt.
Object? _docTrangThai(WidgetTester tester, Map<String, dynamic> checkpoint) {
  final target = _asMap(checkpoint['target']);
  final kieu = _text(checkpoint, 'widget');
  final thuocTinh = _text(checkpoint, 'property');
  if (kieu.isEmpty || thuocTinh.isEmpty) {
    throw ArgumentError(
      'Tiêu chí trạng thái widget phải khai cả "widget" (tên kiểu) lẫn "property".',
    );
  }
  final moTa = target.isEmpty ? 'widget $kieu' : _moTaTarget(target);
  final neo = target.isEmpty
      ? find.byWidgetPredicate((w) => w.runtimeType.toString() == kieu)
      : _finder(target);
  final f = _timWidgetMangThuocTinh(neo, kieu, moTa);
  // Noi dung o nhap: doc tu EditableText ben trong, vi TextField khong khai
  // controller thi `TextField.controller` la null con EditableText luon co.
  if (thuocTinh == 'noi_dung') {
    final et = find.descendant(of: f, matching: find.byType(EditableText));
    if (et.evaluate().length == 1) {
      return tester.widget<EditableText>(et.first).controller.text;
    }
  }
  return _docThuocTinhWidget(tester.widget(f.first), thuocTinh);
}

/// TRẠNG THÁI WIDGET — đọc một thuộc tính thật rồi so với giá trị đo trên Golden.
Future<void> _assertWidgetState(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Duration timeout,
) async {
  final target = _asMap(checkpoint['target']);
  final kieu = _text(checkpoint, 'widget');
  final thuocTinh = _text(checkpoint, 'property');
  final moTa = target.isEmpty ? 'widget $kieu' : _moTaTarget(target);
  if (target.isNotEmpty) {
    await _waitUntil(
      tester,
      () => _finder(target).evaluate().isNotEmpty,
      timeout,
      'Không thấy $moTa trên màn hình nên không đọc được trạng thái.',
    );
  }
  final doDuoc = _docTrangThai(tester, checkpoint);

  // Lúc thu oracle thì chính lượt này sinh ra giá trị chuẩn nên không có gì để so.
  // Vẫn ĐỌC ở trên chứ không thoát sớm: nhờ vậy tiêu chí khai sai nổ ngay lúc soạn
  // đề chứ không đợi tới lúc chấm bài thật.
  if (_dangThuOracle) return;

  final mongDoi = _asMap(checkpoint['expect'])['value'];
  if (mongDoi == null) {
    throw StateError(
      'Tiêu chí "$thuocTinh" của $moTa chưa có giá trị chuẩn — '
      'hãy capture lại oracle rồi publish lại.',
    );
  }
  _soGiaTriChuan(
    mongDoi,
    doDuoc,
    _double(checkpoint['tolerance_pct'], 0),
    moTa,
    thuocTinh,
  );
}

/// So gia tri do duoc voi gia tri chuan. Dung chung cho trang thai widget, kieu chu
/// va chu de — ba loai tieu chi phai so theo CUNG mot luat, neu khong thi cung mot
/// con so lai dat o cho nay ma truot o cho kia.
void _soGiaTriChuan(
  Object? mongDoi,
  Object? doDuoc,
  double saiSo,
  String moTa,
  String thuocTinh,
) {
  final chuan = _chuanHoaTrangThai(mongDoi);
  final bai = _chuanHoaTrangThai(doDuoc);
  final soChuan = double.tryParse(chuan);
  final soBai = double.tryParse(bai);
  if (saiSo > 0 && soChuan != null && soBai != null) {
    final nguong = (soChuan.abs() < 1e-9 ? 1.0 : soChuan.abs()) * saiSo / 100;
    if ((soChuan - soBai).abs() > nguong) {
      throw StateError(
        '$moTa: $thuocTinh mong doi $chuan (sai so $saiSo%) nhung bai lam cho $bai.',
      );
    }
    return;
  }
  if (chuan != bai) {
    throw StateError(
      '$moTa: $thuocTinh mong doi "$chuan" nhung bai lam cho "$bai".',
    );
  }
}

/// Thuoc tinh tra ve mau thi phai so bang phep so mau (sai so theo kenh R/G/B),
/// khong so chuoi.
bool _laThuocTinhMau(String thuocTinh) =>
    thuocTinh == 'color' ||
    thuocTinh == 'app_bar_background' ||
    thuocTinh.startsWith('color_scheme.');

/// KIEU CHU THUC SU DUOC VE — doc tu RenderParagraph chu khong tu `Text.style`.
///
/// Vi sao: `Text.style` chi chua phan nguoi viet dat THANG tren widget, con phan thua
/// ke tu theme thi khong co o do. Do o sa ban: chu dung `titleLarge` cua theme co
/// `Text.style == null` nhung thuc te duoc ve o 22px/w700 — doc sai cho thi moi tieu
/// chi kieu chu cua de dung theme deu thanh "khong co gia tri".
TextStyle? _kieuChuThuc(Finder neo) {
  final els = neo.evaluate();
  if (els.isEmpty) return null;
  Finder doan;
  if (els.first.widget is RichText) {
    doan = neo.first;
  } else {
    final duoi = find.descendant(of: neo, matching: find.byType(RichText));
    final so = duoi.evaluate().length;
    if (so == 0) return null;
    if (so > 1) {
      throw StateError(
        'Cho nay co $so doan chu khac nhau — tieu chi kieu chu phai tro vao dung mot dong.',
      );
    }
    doan = duoi;
  }
  final ro = doan.evaluate().first.renderObject;
  return ro is RenderParagraph ? ro.text.style : null;
}

/// Doc mot mat cua kieu chu. Do dam tra ve SO (400, 700...) chu khong phai chuoi
/// 'FontWeight.w700': so thi on dinh qua cac ban Flutter va nguoi soan de doc duoc.
Object? _docKieuChu(WidgetTester tester, Map<String, dynamic> checkpoint) {
  final target = _asMap(checkpoint['target']);
  final thuocTinh = _text(checkpoint, 'property');
  if (target.isEmpty || thuocTinh.isEmpty) {
    throw ArgumentError(
      'Tieu chi kieu chu phai khai ca target (chu can do) lan "property".',
    );
  }
  final moTa = _moTaTarget(target);
  final kieu = _kieuChuThuc(_finder(target));
  if (kieu == null) {
    throw StateError('Khong doc duoc kieu chu cua $moTa — cho do khong phai mot dong chu.');
  }
  switch (thuocTinh) {
    case 'font_size':
      return kieu.fontSize;
    case 'font_weight':
      return kieu.fontWeight?.value;
    case 'font_family':
      return kieu.fontFamily;
    case 'color':
      final mau = kieu.color;
      return mau == null ? null : _hex(mau);
    default:
      throw ArgumentError(
        'Tieu chi kieu chu chua biet thuoc tinh "$thuocTinh" '
        '(chi co font_size, font_weight, font_family, color).',
      );
  }
}

/// Doc mot gia tri trong ThemeData cua app.
///
/// Lay context SAU trong cay nhu `_mauChuDao` dang lam: dung ngay tai MaterialApp thi
/// `Theme.of` truot qua chinh theme ma app khai va tra ve theme mac dinh.
Object? _docGiaTriTheme(WidgetTester tester, String thuocTinh) {
  ThemeData? chuDe;
  for (final f in <Finder>[find.byType(Scaffold), find.byType(Material)]) {
    if (f.evaluate().isEmpty) continue;
    try {
      chuDe = Theme.of(tester.element(f.first));
      break;
    } catch (_) {
      // Thu nguon ke tiep.
    }
  }
  if (chuDe == null) {
    final app = find.byType(MaterialApp);
    if (app.evaluate().isNotEmpty) {
      chuDe = tester.widget<MaterialApp>(app.first).theme;
    }
  }
  if (chuDe == null) {
    throw StateError('Khong doc duoc bang chu de (ThemeData) cua app.');
  }
  final t = chuDe;
  switch (thuocTinh) {
    case 'use_material3':
      return t.useMaterial3;
    case 'brightness':
      return t.brightness == Brightness.dark ? 'dark' : 'light';
    case 'font_family':
      return t.textTheme.bodyMedium?.fontFamily ??
          t.textTheme.titleLarge?.fontFamily;
    case 'app_bar_background':
      final mau = t.appBarTheme.backgroundColor;
      return mau == null ? null : _hex(mau);
    case 'app_bar_center_title':
      return t.appBarTheme.centerTitle;
  }
  if (thuocTinh.startsWith('color_scheme.')) {
    final s = t.colorScheme;
    final bang = <String, Color>{
      'primary': s.primary,
      'onPrimary': s.onPrimary,
      'primaryContainer': s.primaryContainer,
      'secondary': s.secondary,
      'onSecondary': s.onSecondary,
      'secondaryContainer': s.secondaryContainer,
      'tertiary': s.tertiary,
      'error': s.error,
      'onError': s.onError,
      'surface': s.surface,
      'onSurface': s.onSurface,
      'outline': s.outline,
    };
    final vai = thuocTinh.substring('color_scheme.'.length);
    final mau = bang[vai];
    if (mau == null) {
      throw ArgumentError('Bang mau khong co vai tro "$vai".');
    }
    return _hex(mau);
  }
  if (thuocTinh.startsWith('text_theme.')) {
    final tt = t.textTheme;
    final bang = <String, TextStyle?>{
      'displayLarge': tt.displayLarge,
      'displayMedium': tt.displayMedium,
      'displaySmall': tt.displaySmall,
      'headlineLarge': tt.headlineLarge,
      'headlineMedium': tt.headlineMedium,
      'headlineSmall': tt.headlineSmall,
      'titleLarge': tt.titleLarge,
      'titleMedium': tt.titleMedium,
      'titleSmall': tt.titleSmall,
      'bodyLarge': tt.bodyLarge,
      'bodyMedium': tt.bodyMedium,
      'bodySmall': tt.bodySmall,
      'labelLarge': tt.labelLarge,
      'labelMedium': tt.labelMedium,
      'labelSmall': tt.labelSmall,
    };
    final phan = thuocTinh.substring('text_theme.'.length).split('.');
    final cap = phan.first;
    if (!bang.containsKey(cap)) {
      throw ArgumentError('TextTheme khong co cap chu "$cap".');
    }
    final kieu = bang[cap];
    if (kieu == null) return null;
    final mat = phan.length > 1 ? phan[1] : 'font_size';
    if (mat == 'font_size') return kieu.fontSize;
    if (mat == 'font_weight') return kieu.fontWeight?.value;
    if (mat == 'font_family') return kieu.fontFamily;
    throw ArgumentError(
      'Cap chu chi doc duoc font_size, font_weight hoac font_family.',
    );
  }
  throw ArgumentError('Tieu chi chu de chua biet thuoc tinh "$thuocTinh".');
}

/// KIEU CHU va CHU DE — cung mot khuon: doc gia tri that roi so voi gia tri do tren
/// Golden. Tach khoi widget_state vi nguon doc khac han (RenderParagraph / ThemeData).
Future<void> _assertKieuChuHoacTheme(
  WidgetTester tester,
  Map<String, dynamic> checkpoint,
  Duration timeout, {
  required bool laKieuChu,
}) async {
  final target = _asMap(checkpoint['target']);
  final thuocTinh = _text(checkpoint, 'property');
  final moTa = laKieuChu ? 'chu ${_moTaTarget(target)}' : 'chu de app';
  if (laKieuChu && target.isNotEmpty) {
    await _waitUntil(
      tester,
      () => _finder(target).evaluate().isNotEmpty,
      timeout,
      'Khong thay ${_moTaTarget(target)} tren man hinh nen khong doc duoc kieu chu.',
    );
  }
  final doDuoc = laKieuChu
      ? _docKieuChu(tester, checkpoint)
      : _docGiaTriTheme(tester, thuocTinh);
  if (_dangThuOracle) return;

  final mongDoi = _asMap(checkpoint['expect'])['value'];
  if (mongDoi == null) {
    throw StateError(
      'Tieu chi "$thuocTinh" cua $moTa chua co gia tri chuan — '
      'hay capture lai oracle roi publish lai.',
    );
  }
  if (_laThuocTinhMau(thuocTinh)) {
    _soMau(
      _chuanHoaTrangThai(mongDoi),
      _chuanHoaTrangThai(doDuoc),
      _double(checkpoint['tolerance_pct'], 20),
      '$moTa — $thuocTinh',
    );
    return;
  }
  _soGiaTriChuan(
    mongDoi,
    doDuoc,
    _double(checkpoint['tolerance_pct'], 0),
    moTa,
    thuocTinh,
  );
}

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

Finder _finder(Map<String, dynamic> target, {bool duPhong = false}) {
  for (final keyName in const [
    'semanticId',
    'semantic_id',
    'valueKey',
    'value_key',
    'key',
  ]) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final finder = find.byKey(ValueKey<String>(value));
      if (finder.evaluate().isNotEmpty) return finder;
    }
  }
  final label = _text(target, 'label');
  final hint = _text(target, 'hint');
  if (label.isNotEmpty) {
    final semantics = find.bySemanticsLabel(label);
    if (semantics.evaluate().isNotEmpty) return semantics;
  }
  // NHÃN HAI DÒNG. Dòng danh sách (ListTile) gộp title + subtitle thành một nhãn
  // ngăn bởi xuống dòng; recorder đời cũ tách nhầm thành label + hint (phép tách đó
  // chỉ đúng cho ô nhập). Ghép lại để so tuyệt đối — thiếu nhánh này thì bấm vào
  // dòng danh sách không bao giờ tìm thấy đích, cả kịch bản EDIT chết theo.
  if (label.isNotEmpty && hint.isNotEmpty) {
    final gop = find.bySemanticsLabel('$label\n$hint');
    if (gop.evaluate().isNotEmpty) return gop;
  }
  // TOOLTIP KHAI THẲNG. Flutter KHÔNG biến tooltip thành nhãn ngữ nghĩa: đo ở sa bàn
  // thì bySemanticsLabel('Thêm mới') ra 0 trong khi byTooltip('Thêm mới') ra 1. Thiếu
  // nhánh này thì FloatingActionButton và IconButton — hai widget đề nào cũng có —
  // không còn đường định vị nào.
  final tooltip = _text(target, 'tooltip');
  if (tooltip.isNotEmpty) {
    final finder = find.byTooltip(tooltip);
    if (finder.evaluate().isNotEmpty) return finder;
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
    if (finder.evaluate().isNotEmpty) return finder;
  }
  // NHÃN GÕ NHẦM SANG CHỮ TOOLTIP. Người soạn chỉ thấy một chuỗi chữ trên màn hình
  // nên hay điền tooltip vào ô `label`. Đặt SAU phép khớp decoration chứ không trước:
  // ô nhập đã có chữ chỉ còn decoration giữ nhãn, mà thử tooltip trước thì rủi ro
  // cướp mất lookup ấy nếu tình cờ có widget khác mang đúng chuỗi đó làm tooltip.
  if (label.isNotEmpty) {
    final finder = find.byTooltip(label);
    if (finder.evaluate().isNotEmpty) return finder;
  }
  final text = _text(target, 'text');
  if (text.isNotEmpty) return find.text(text);
  // TIỀN TỐ VĂN BẢN. Hợp đồng nhãn của đề khai `text_prefix` cho những dòng mà phần
  // đuôi thay đổi theo dữ liệu — ví dụ "Tổng tháng: 608.000 ₫". find.text so khớp
  // TUYỆT ĐỐI nên không dùng được ở đây; thiếu nhánh này thì đúng những mục hợp đồng
  // ấy không có cách nào kiểm.
  final textPrefix = _text(target, 'text_prefix');
  if (textPrefix.isNotEmpty) {
    return find.byWidgetPredicate(
      (widget) => widget is Text && (widget.data ?? '').startsWith(textPrefix),
    );
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
