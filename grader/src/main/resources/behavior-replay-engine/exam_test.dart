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

  // Chan overflow TRUOC khi no toi binding cua flutter_test: binding se bien no thanh
  // pending exception, _throwPendingException nem ra, va ca nhom checkpoint chet theo.
  // Moi loi khac van chuyen tiep nguyen ven cho binding.
  _loiTranBoCuc.clear();
  final void Function(FlutterErrorDetails)? xuLyCu = FlutterError.onError;
  FlutterError.onError = (FlutterErrorDetails chiTiet) {
    final String chuoi = chiTiet.exceptionAsString();
    if (chuoi.contains('overflowed by')) {
      _loiTranBoCuc.add(chuoi.split(String.fromCharCode(10)).first.trim());
      return;
    }
    xuLyCu?.call(chiTiet);
  };
  addTearDown(() => FlutterError.onError = xuLyCu);

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
      tester.platformDispatcher.clearPlatformBrightnessTestValue();
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
  // CHE DO TOI theo kich ban. Dat TRUOC khi boot: MaterialApp doc platformBrightness
  // luc build de chon theme/darkTheme, nen dat sau la muon. Capture cung chay qua day
  // nen gia tri chuan mau/kieu chu tu la gia tri cua che do toi.
  final doSang = _text(viewport, 'brightness').toLowerCase();
  if (doSang == 'dark') {
    tester.platformDispatcher.platformBrightnessTestValue = Brightness.dark;
  } else {
    tester.platformDispatcher.clearPlatformBrightnessTestValue();
  }
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
    case 'drag':
      // KEO mot widget — dai truot, keo-tha. Khac `scroll` o ba cho: BAT BUOC co
      // target (keo cai gi?), khong co mac dinh ngam theo truc y, va cuon cho widget
      // hien ra truoc khi keo. Do o sa ban: tester.drag doi Slider 40 -> 60 voi dx 80.
      final finder = await _waitForTarget(
        tester,
        _asMap(step['target']),
        timeout,
      );
      await tester.ensureVisible(finder);
      final delta = _asMap(step['delta']);
      final dx = _double(delta['x'], 0);
      final dy = _double(delta['y'], 0);
      if (dx == 0 && dy == 0) {
        throw ArgumentError(
          'Action drag phai khai do doi: delta.x hoac delta.y khac 0.',
        );
      }
      await tester.drag(finder, Offset(dx, dy), warnIfMissed: false);
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
      if (value.isEmpty) continue;
      // KHOP CON, khong khop tuyet doi: mot dong danh sach thuong gop nhieu truong
      // vao MOT widget Text ("29.300 d · KHAC · 2026-09-09"), nen find.text('KHAC')
      // khong bao gio thay du chu do hien ro tren man hinh. So theo cach VE thi tieu
      // chi moi kiem duoc dung thu no dinh kiem.
      await _waitUntil(
        tester,
        () => find
            .byWidgetPredicate(
              (w) =>
                  w is Text &&
                  _chuNhinThay(w.data ?? '').contains(_chuNhinThay(value)),
            )
            .evaluate()
            .isNotEmpty,
        timeout,
        'SQLite co row dung nhung khong dong chu nao tren UI chua gia tri "$value".',
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

  // KHONG VO BO CUC — ca luong (boot + moi buoc) khong co RenderFlex overflow nao.
  // Khong can gia tri chuan: tren Golden ma tran thi tieu chi nay truot ngay luc
  // capture, tuc nguoi ra de biet bai mau cua minh hong truoc khi sinh vien nop.
  if (kind == 'no_overflow') {
    if (_loiTranBoCuc.isNotEmpty) {
      throw StateError(
        'Bo cuc bi tran ${_loiTranBoCuc.length} cho trong luong nay — ${_loiTranBoCuc.first}',
      );
    }
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
    // THÀNH PHẦN LẶP: Golden có nhiều thể hiện thì "có mặt" nghĩa là có ở MỌI dòng,
    // không phải có ở một dòng. Thiếu chốt này thì bài vẽ nút Xóa đúng dòng đầu vẫn đạt.
    final lap = _asMap(_asMap(checkpoint['expect'])['repeat']);
    if (visible && lap.isNotEmpty) {
      _kiemDuMoiDong(_finder(target).evaluate().toList(), lap, moTa);
    }
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
      () => _coChu(value),
      timeout,
      'Không thấy nội dung "$value" trên UI.',
    );
    soPhepKiem++;
  }
  // TIEN TO cho noi dung: dong ma phan duoi doi theo du lieu ("Tong thang: 608.000 d").
  //
  // MAN SOAN DE KHONG PHOI RA cho nay, co y: du lieu cham chay tren hidden.db co dinh
  // nen moi con so deu tat dinh, go dung chuoi day du la xong — them mot o nua chi tao
  // duong thu tu de noi "chu X co tren man hinh". Giu nhanh nay trong engine vi no re
  // va vi de nao that su can (gia tri nguoi ra de khong doan truoc duoc) thi chi phai
  // mo lai form, khong phai sua ca ba tang.
  //
  // Con `text_prefix` trong TARGET thi khac han va VAN duoc phoi ra: no de TRO VAO mot
  // widget cho tieu chi khac do (co chu, mau, vi tri cua dong tong), cho ma khop tuyet
  // doi khong the dung vi noi dung doi sau moi lan Them/Sua/Xoa.
  for (final raw in _asList(expectValue['visible_text_prefixes'])) {
    final tienTo = (raw?.toString() ?? '');
    if (tienTo.isEmpty) continue;
    await _waitUntil(
      tester,
      () => find
          .byWidgetPredicate(
            (w) => w is Text && _chuNhinThay(w.data ?? '').startsWith(_chuNhinThay(tienTo)),
          )
          .evaluate()
          .isNotEmpty,
      timeout,
      'Không thấy dòng chữ nào bắt đầu bằng "$tienTo" trên UI.',
    );
    soPhepKiem++;
  }
  for (final raw in _asList(expectValue['hidden_text_prefixes'])) {
    final tienTo = (raw?.toString() ?? '');
    if (tienTo.isEmpty) continue;
    expect(
      find.byWidgetPredicate(
        (w) => w is Text && _chuNhinThay(w.data ?? '').startsWith(_chuNhinThay(tienTo)),
      ),
      findsNothing,
      reason: 'Vẫn còn dòng chữ bắt đầu bằng "$tienTo" trên UI.',
    );
    soPhepKiem++;
  }
  for (final raw in _asList(expectValue['hidden_texts'])) {
    final value = (raw?.toString() ?? '');
    expect(
      _coChu(value),
      isFalse,
      reason: 'Nội dung "$value" vẫn còn trên UI.',
    );
    soPhepKiem++;
  }

  final expectedText = checkpoint['text'] ?? expectValue['text'];
  if (expectedText != null) {
    final value = (expectedText?.toString() ?? '');
    expect(_coChu(value), isTrue, reason: 'Không thấy nội dung "$value" trên UI.');
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
  for (final khoa in const <String>[
    'semantic_id',
    'semanticId',
    'label',
    'hint',
    'text',
    'tooltip',
  ]) {
    final v = _text(target, khoa);
    if (v.isNotEmpty) return '"$v"';
  }
  final icon = _text(target, 'icon');
  if (icon.isNotEmpty) {
    final ma = _maIcon(icon);
    return 'nút icon ${ma == null ? icon : _tenIcon(ma)}';
  }
  final tienTo = _text(target, 'text_prefix');
  if (tienTo.isNotEmpty) return '"$tienTo…"';
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
    // FONT ICON. Không nạp thì mọi Icon vẽ thành ô vuông tofu: ảnh chuẩn lưu cho phúc
    // khảo sai hình, và tiêu chí màu đo trên vùng icon lấy phải màu ô vuông. Nằm cùng
    // thư mục với Roboto nên không thêm phụ thuộc nào — đo 6/9/2026: nạp xong ra đúng
    // glyph (thùng rác, bút, dấu cộng), còn màu chủ đạo của nút thì KHÔNG đổi vì màu
    // lấy trên nền nút, glyph chỉ chiếm phần nhỏ giữa nút.
    final tepIcon = File(p.join(dir.path, 'MaterialIcons-Regular.otf'));
    if (tepIcon.existsSync()) {
      final iconLoader = FontLoader('MaterialIcons')
        ..addFont(
          Future.value(ByteData.view(tepIcon.readAsBytesSync().buffer)),
        );
      await iconLoader.load();
    }
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

// ============================ ICON ============================
// Vì sao chấm theo ICON: nút chỉ có hình (Thêm, Xóa) không có chữ nào để bám, nên trước
// đây đề phải bắt sinh viên gắn Semantics(label:) chỉ để máy tìm được. Không tiêu chí nào
// kiểm nội dung nhãn ấy — tức bắt làm một việc thừa rồi trừ 5,05 điểm nếu quên (đo
// 5/9/2026). Icon mới là thứ người chấm nhìn: đúng hình, đúng chỗ, đúng màu.
//
// Đo 6/9/2026 (sa bàn icon): tìm theo mã icon ra đúng đích ở cả ba kiểu bọc — bọc
// Semantics(label), nút trần trong Card, nút có tooltip — và khung của NÚT BAO NGOÀI
// trùng từng pixel với khung mà đường tìm theo nhãn trả về. Nhờ vậy bộ đề cũ đổi tiêu
// chí sang icon không phải sửa một con số oracle nào.

/// Bảng tên icon -> mã, đọc từ file `codepoints` của SDK (dòng dạng "add_baseline e047").
/// Đọc từ SDK chứ không nhúng bảng cứng: đổi bản Flutter thì bảng tự đúng theo.
Map<String, int>? _bangIcon;

Map<String, int> _docBangIcon() {
  final ra = <String, int>{};
  final root = Platform.environment['FLUTTER_ROOT'] ?? '';
  if (root.isEmpty) return ra;
  final tep = File(
    p.join(root, 'bin', 'cache', 'artifacts', 'material_fonts', 'codepoints'),
  );
  if (!tep.existsSync()) return ra;
  try {
    for (final dong in tep.readAsLinesSync()) {
      final cot = dong.trim().split(' ');
      if (cot.length != 2) continue;
      final ma = int.tryParse(cot[1], radix: 16);
      if (ma != null) ra[cot[0].toLowerCase()] = ma;
    }
  } catch (_) {
    // Thiếu bảng chỉ mất đường khai icon bằng TÊN; khai bằng mã vẫn chạy.
  }
  return ra;
}

/// Mã icon từ chuỗi đề khai: "delete_outline", "Icons.delete_outline", "0xe1bb", "57787".
///
/// Tên trong `codepoints` có đuôi biến thể: `Icons.add` nằm ở dòng `add_baseline`, còn
/// `Icons.add_outlined` nằm ở dòng `add_outlined`. Thử tên nguyên trước rồi mới thêm
/// `_baseline` thì cả hai kiểu khai đều ra đúng.
int? _maIcon(String khai) {
  final s = khai.trim();
  if (s.isEmpty) return null;
  if (s.toLowerCase().startsWith('0x')) {
    return int.tryParse(s.substring(2), radix: 16);
  }
  final so = int.tryParse(s);
  if (so != null) return so;
  _bangIcon ??= _docBangIcon();
  final ten = s
      .replaceFirst(RegExp(r'^Icons\.'), '')
      .replaceAll('-', '_')
      .toLowerCase();
  return _bangIcon![ten] ?? _bangIcon!['${ten}_baseline'];
}

/// Tên icon từ mã, để in ra log phúc khảo cho người đọc hiểu ("delete_outline" thay vì
/// "0xe1bb"). Ưu tiên biến thể `_baseline` vì đó là `Icons.xxx` mặc định.
String _tenIcon(int ma) {
  _bangIcon ??= _docBangIcon();
  String? tot;
  _bangIcon!.forEach((ten, giaTri) {
    if (giaTri != ma) return;
    if (ten.endsWith('_baseline')) {
      tot ??= ten.substring(0, ten.length - '_baseline'.length);
    } else {
      tot ??= ten;
    }
  });
  return tot ?? '0x${ma.toRadixString(16)}';
}

/// Lớp nút thật sự của Material: khung của nó mới là vùng người dùng nhìn và chạm
/// (IconButton 48x48, FloatingActionButton 56x56), còn glyph chỉ 24x24 nằm giữa.
const Set<String> _nutBac1 = <String>{
  'FloatingActionButton',
  'IconButton',
  'ElevatedButton',
  'FilledButton',
  'OutlinedButton',
  'TextButton',
  'ChoiceChip',
  'FilterChip',
  'ActionChip',
  'Chip',
};

/// Lớp nhận chạm tự viết tay — chỉ dùng khi không có lớp nút Material nào.
const Set<String> _nutBac2 = <String>{
  'InkWell',
  'InkResponse',
  'GestureDetector',
};

double _dienTich(Element e) {
  final ro = e.renderObject;
  return ro is RenderBox && ro.hasSize ? ro.size.width * ro.size.height : 0;
}

/// Nút bao ngoài gần nhất của một widget. So bằng TÊN kiểu chứ không tra bảng Type để
/// đề sau dùng nút nào cũng chạy mà khỏi dựng lại ảnh nền.
///
/// Vì sao leo tới 80 tầng: cây Material 3 giữa Icon và IconButton dày hơn 20 tầng (đo
/// 6/9/2026) — chặn ở 20 thì trả về GestureDetector bên trong và khung đo ra sai.
///
/// Vì sao lớp bậc 2 phải nhỏ hơn 8 lần diện tích icon: InkWell/GestureDetector hay bọc
/// CẢ DÒNG danh sách. Nhận cả dòng làm "nút của icon" thì tiêu chí vị trí đo nhầm sang
/// khung dòng. Nút thật gấp ~4 lần icon (48x48 so với 24x24), còn dòng gấp ~57 lần.
Element? _nutBaoNgoai(Element goc) {
  Element? bac1;
  Element? bac2;
  var sau = 0;
  goc.visitAncestorElements((Element a) {
    sau++;
    final ten = a.widget.runtimeType.toString();
    if (_nutBac1.contains(ten)) {
      bac1 = a;
      return false;
    }
    if (_nutBac2.contains(ten) && bac2 == null) {
      if (_dienTich(a) <= _dienTich(goc) * 8) bac2 = a;
    }
    return sau < 80;
  });
  return bac1 ?? bac2;
}

/// Finder trỏ đúng MỘT element đã biết.
Finder _laPhanTu(Element x) => find.byElementPredicate(
  (Element y) => identical(y, x),
  description: 'phần tử ${x.widget.runtimeType}',
);

Finder _timIconThuan(int ma) => find.byWidgetPredicate(
  (Widget w) => w is Icon && w.icon?.codePoint == ma,
  description: 'Icon 0x${ma.toRadixString(16)}',
);

/// Các NÚT mang icon có mã đã cho — trả nút bao ngoài chứ không trả chính Icon, vì tiêu
/// chí vị trí/màu phải đo trên vùng người dùng nhìn và chạm.
List<Element> _cacNutIcon(int ma) {
  final ra = <Element>[];
  for (final e in _timIconThuan(ma).evaluate()) {
    ra.add(_nutBaoNgoai(e) ?? e);
  }
  return ra;
}

/// Tìm theo icon. Danh sách nút tính MỘT LẦN lúc dựng finder; `_waitUntil` gọi lại
/// `_finder` mỗi nhịp poll nên vẫn bắt kịp màn hình đang đổi.
Finder _timTheoIcon(int ma) {
  final nut = _cacNutIcon(ma);
  if (nut.isEmpty) return find.byWidgetPredicate((_) => false);
  return find.byElementPredicate(
    (Element e) => nut.any((Element n) => identical(n, e)),
    description: 'nút mang icon ${_tenIcon(ma)}',
  );
}

bool _laConChau(Element con, Element toTien) {
  var ra = false;
  con.visitAncestorElements((Element a) {
    if (identical(a, toTien)) {
      ra = true;
      return false;
    }
    return true;
  });
  return ra;
}

/// DÒNG của một phần tử: cây con LỚN NHẤT chứa nó mà KHÔNG chứa phần tử "cùng loại" nào
/// khác, và không leo qua khung cuộn.
///
/// Vì sao định nghĩa theo DỮ LIỆU chứ không theo kiểu widget: bản đầu lấy "con trực tiếp
/// của ListView" thì hỏng ngay ở bài vẽ dòng bằng Card và ở bài dùng Column trong
/// SingleChildScrollView (đo 6/9/2026: 0/6 dòng). Luật này đo đủ sáu tình huống —
/// ListTile, Card, có header nằm trong ListView, danh sách dài chỉ dựng một phần, dòng
/// thiếu icon, Column cuộn — đều ra đúng "mỗi dòng một icon".
Element _dongCua(Element x, List<Element> khac) {
  Element dong = x;
  x.visitAncestorElements((Element a) {
    // Chặn ở khung cuộn: danh sách chỉ còn MỘT dòng thì không có phần tử cùng loại nào
    // để chặn, thiếu chốt này là "dòng" phình ra cả màn hình.
    if (a is SliverMultiBoxAdaptorElement ||
        a.widget is Scrollable ||
        a.widget is SingleChildScrollView) {
      return false;
    }
    if (khac.any((Element k) => _laConChau(k, a))) return false;
    dong = a;
    return true;
  });
  return dong;
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
  // Thành phần lặp: khung tuyệt đối trên chỉ nói về thể hiện đầu. Phần này soát chỗ đứng
  // của TỪNG cái trong dòng của nó, nên nút Xóa nhảy sang trái ở dòng thứ tư cũng bị bắt.
  final lap = _asMap(mongDoi['repeat']);
  if (lap.isNotEmpty) {
    _kiemViTriLap(
      tester,
      _finder(target).evaluate().toList(),
      lap,
      _double(checkpoint['tolerance_pct'], 5),
      moTa,
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
  if (!await _baoDamAnhCuoi(tester)) {
    throw StateError('Không chụp được màn hình để lấy màu của $moTa.');
  }
  final man = _coManHinh(tester);
  final tiLe = man.width > 0 ? _anhCuoiW / man.width : 1.0;
  // Thành phần lặp thì đo MỌI thể hiện: tô đúng nút Xóa dòng đầu rồi bỏ quên các dòng
  // sau là lỗi người chấm nhìn thấy ngay, máy cũng phải thấy.
  final khungs = <Rect>[];
  final cac = _asMap(_asMap(checkpoint['expect'])['repeat']).isEmpty
      ? const <Element>[]
      : _finder(target).evaluate().toList();
  if (cac.length >= 2) {
    for (final e in cac) {
      khungs.add(tester.getRect(_laPhanTu(e)));
    }
  } else {
    final mot = _khungThanhPhan(tester, target);
    if (mot != null) khungs.add(mot);
  }
  if (khungs.isEmpty) throw StateError('Không đo được khung của $moTa.');
  for (var i = 0; i < khungs.length; i++) {
    final mauBai = _mauChinhTrongVung(khungs[i], tiLe);
    if (mauBai == null) {
      throw StateError(
        'Vùng của $moTa không có pixel nào khác màu nền để lấy màu.',
      );
    }
    _soMau(
      mauChuan,
      mauBai,
      _double(checkpoint['tolerance_pct'], 5),
      khungs.length > 1
          ? 'Màu của $moTa ở dòng thứ ${i + 1}'
          : 'Màu của $moTa',
    );
  }
}

// ==================== THÀNH PHẦN LẶP THEO DÒNG ====================
// Nút Xóa của bài Quản lý chi tiêu nằm ở MỖI dòng: sáu khoản chi thì sáu nút. Trước đây
// tiêu chí giao diện chỉ đo `.first`, nên bài vẽ nút ở đúng một dòng vẫn đạt cả ba mặt
// (có mặt, vị trí, màu) — máy chấm không khớp với thứ người chấm nhìn thấy.
//
// Luật mới: capture trên Golden thấy từ hai thể hiện trở lên thì tiêu chí chấm CẢ NHÓM.
// Vị trí đo TƯƠNG ĐỐI TRONG DÒNG nên dòng đầu và dòng cuối đều đạt; màu đo trên mọi thể
// hiện; và khi đề có định danh dòng thì kiểm đủ "mỗi dòng đúng một cái".
//
// Vì sao thiếu định danh dòng thì KHÔNG so số lượng với Golden: số dòng phụ thuộc dữ liệu
// bài làm dựng ra. Bài hiển thị thiếu một khoản chi đã trượt tiêu chí nội dung rồi, trừ
// thêm ở tiêu chí icon là phạt hai lần cho cùng một lỗi.

/// Định danh ngữ nghĩa của một element, đọc từ cây semantics đã dựng.
String _dinhDanhCua(Element e) {
  try {
    return e.renderObject?.debugSemantics?.identifier ?? '';
  } catch (_) {
    return '';
  }
}

/// Các DÒNG mang định danh bắt đầu bằng tiền tố (ví dụ "dong_" cho dong_1..dong_6).
/// Đây là nguồn DUY NHẤT cho biết màn hình đang có bao nhiêu dòng mà không phải suy
/// ngược từ chính các thể hiện đang đếm.
List<Element> _cacDongTheoDinhDanh(String tienTo) {
  if (tienTo.isEmpty) return const <Element>[];
  List<Element> tho;
  try {
    // Neo hai đầu: bySemanticsIdentifier dùng hasMatch nên không neo thì "chi_tieu.dong."
    // nuốt luôn "chi_tieu.dong.3.xoa" — nút Xóa bị đếm thành một dòng.
    tho = find
        .bySemanticsIdentifier(RegExp('^${RegExp.escape(tienTo)}' + r'\d+$'))
        .evaluate()
        .toList();
  } catch (_) {
    // Semantics chưa bật thì không có đường nào đếm dòng; bỏ phép kiểm còn hơn báo sai.
    return const <Element>[];
  }
  // Nhiều element cùng báo MỘT nút semantics khi cây bị gộp. Giữ đúng một element cho
  // mỗi định danh, chọn cái RỘNG NHẤT vì đó là gốc dòng — giữ cả chùm thì những element
  // con không chứa icon sẽ bị tính là "dòng thiếu icon" và bài đúng trượt oan.
  final tot = <String, Element>{};
  for (final e in tho) {
    final id = _dinhDanhCua(e);
    if (id.isEmpty) continue;
    final cu = tot[id];
    if (cu == null || _dienTich(e) > _dienTich(cu)) tot[id] = e;
  }
  return tot.values.toList();
}

/// Số thể hiện nằm trong một dòng. Đếm theo QUAN HỆ CÂY chứ không theo hình chữ nhật:
/// lớp phủ (hộp thoại, snackbar) có thể chồng lên khung dòng.
int _soTrongDong(List<Element> cac, Element dong) => cac
    .where((Element e) => identical(e, dong) || _laConChau(e, dong))
    .length;

/// Định danh của DÒNG chứa một nút: định danh gần nhất trên đường leo lên mà khung của
/// nó rộng ít nhất gấp đôi nút. Vế "gấp đôi" để không nhặt nhầm chính định danh bọc
/// quanh nút (xoa_dong_3) làm định danh dòng (dong_3).
String? _dinhDanhDongCua(WidgetTester tester, Element nut) {
  double rongNut;
  try {
    rongNut = tester.getRect(_laPhanTu(nut)).width;
  } catch (_) {
    return null;
  }
  String? ra;
  var sau = 0;
  nut.visitAncestorElements((Element a) {
    sau++;
    final w = a.widget;
    if (w is Semantics) {
      final id = w.properties.identifier ?? '';
      if (id.isNotEmpty) {
        try {
          if (tester.getRect(_laPhanTu(a)).width >= rongNut * 2) {
            ra = id;
            return false;
          }
        } catch (_) {
          // Không đo được thì bỏ qua lớp này, tiếp tục leo.
        }
      }
    }
    return sau < 80;
  });
  return ra;
}

/// Tiền tố định danh dòng chung của cả nhóm ("dong_1".."dong_6" -> "dong_"). Rỗng khi
/// đề không đặt định danh theo dòng — lúc đó bỏ hẳn phép kiểm "đủ mọi dòng".
String _tienToDinhDanhDong(WidgetTester tester, List<Element> cac) {
  String? chung;
  for (final e in cac) {
    final id = _dinhDanhDongCua(tester, e);
    if (id == null || id.isEmpty) return '';
    final t = id.replaceFirst(RegExp(r'\d+$'), '');
    // Không có phần số ở cuối nghĩa là định danh này dùng chung cho cả nhóm, không
    // phân biệt được dòng nào với dòng nào.
    if (t == id || t.isEmpty) return '';
    if (chung == null) {
      chung = t;
    } else if (chung != t) {
      return '';
    }
  }
  return chung ?? '';
}

/// Vị trí TƯƠNG ĐỐI của một thể hiện trong dòng chứa nó: cách mép phải bao nhiêu phần
/// trăm bề rộng dòng, và tâm dọc ở bao nhiêu phần trăm chiều cao dòng.
List<double>? _viTriTrongDong(
  WidgetTester tester,
  Element e,
  List<Element> cac,
) {
  final dong = _dongCua(
    e,
    cac.where((Element k) => !identical(k, e)).toList(),
  );
  try {
    final rD = tester.getRect(_laPhanTu(dong));
    final rE = tester.getRect(_laPhanTu(e));
    if (rD.width <= 0 || rD.height <= 0) return null;
    return <double>[
      (rD.right - rE.right) / rD.width * 100,
      (rE.center.dy - rD.top) / rD.height * 100,
    ];
  } catch (_) {
    return null;
  }
}

/// Đo đặc trưng LẶP trên Golden lúc capture. Trả null khi chỉ có một thể hiện.
Map<String, dynamic>? _doLap(WidgetTester tester, Finder finder) {
  final cac = finder.evaluate().toList();
  if (cac.length < 2) return null;
  final ra = <String, dynamic>{'count': cac.length};
  final tienTo = _tienToDinhDanhDong(tester, cac);
  if (tienTo.isNotEmpty) ra['row_id_prefix'] = tienTo;

  final dongs = <Element>[];
  for (final e in cac) {
    dongs.add(
      _dongCua(e, cac.where((Element k) => !identical(k, e)).toList()),
    );
  }
  // "Theo dòng" khi: mỗi thể hiện một dòng riêng, dòng rộng hơn hẳn nút (dòng danh sách
  // chứ không phải ba nút cạnh nhau trên thanh công cụ), và các dòng xếp dọc không chồng.
  var theoDong = dongs.toSet().length == cac.length;
  final phai = <double>[];
  final doc = <double>[];
  final khung = <Rect>[];
  if (theoDong) {
    for (var i = 0; i < cac.length; i++) {
      try {
        final rD = tester.getRect(_laPhanTu(dongs[i]));
        final rE = tester.getRect(_laPhanTu(cac[i]));
        if (rD.width < rE.width * 2 || rD.height <= 0) {
          theoDong = false;
          break;
        }
        khung.add(rD);
        phai.add((rD.right - rE.right) / rD.width * 100);
        doc.add((rE.center.dy - rD.top) / rD.height * 100);
      } catch (_) {
        theoDong = false;
        break;
      }
    }
  }
  if (theoDong) {
    final sap = <Rect>[...khung]..sort((a, b) => a.top.compareTo(b.top));
    for (var i = 1; i < sap.length; i++) {
      if (sap[i].top < sap[i - 1].bottom - 1) {
        theoDong = false;
        break;
      }
    }
  }
  ra['per_row'] = theoDong;
  if (theoDong) {
    double tb(List<double> l) => l.reduce((a, b) => a + b) / l.length;
    ra['row_right_pct'] = tb(phai);
    ra['row_center_y_pct'] = tb(doc);
  }
  return ra;
}

/// "Mỗi dòng đúng một cái". Chỉ chạy khi đề có định danh dòng và bài làm cũng gắn —
/// bài quên định danh đã trượt ở tiêu chí định danh rồi, không phạt thêm ở đây.
void _kiemDuMoiDong(
  List<Element> cac,
  Map<String, dynamic> lap,
  String moTa,
) {
  final tienTo = _text(lap, 'row_id_prefix');
  if (tienTo.isEmpty) return;
  final dongs = _cacDongTheoDinhDanh(tienTo);
  if (dongs.isEmpty) return;
  final sai = <String>[];
  for (final d in dongs) {
    final so = _soTrongDong(cac, d);
    if (so != 1) {
      final ten = _dinhDanhCua(d);
      sai.add('${ten.isEmpty ? 'dòng' : ten} có $so');
    }
  }
  if (sai.isNotEmpty) {
    throw StateError(
      'Danh sách có ${dongs.length} dòng nhưng $moTa không đúng một cái mỗi dòng: '
      '${sai.length} dòng sai (${sai.take(3).join('; ')}).',
    );
  }
}

/// Vị trí tương đối trong dòng của MỌI thể hiện. Nhờ đo tương đối mà dòng đầu và dòng
/// cuối cùng một chuẩn, khỏi phải sinh oracle riêng cho từng dòng.
void _kiemViTriLap(
  WidgetTester tester,
  List<Element> cac,
  Map<String, dynamic> lap,
  double saiSoPhanTram,
  String moTa,
) {
  if (!_bool(lap['per_row'], false)) return;
  final chuanPhai = _double(lap['row_right_pct'], -1);
  final chuanDoc = _double(lap['row_center_y_pct'], -1);
  if (chuanPhai < 0 || chuanDoc < 0) return;
  for (var i = 0; i < cac.length; i++) {
    final do_ = _viTriTrongDong(tester, cac[i], cac);
    if (do_ == null) continue;
    if ((do_[0] - chuanPhai).abs() > saiSoPhanTram ||
        (do_[1] - chuanDoc).abs() > saiSoPhanTram) {
      throw StateError(
        'Ở dòng thứ ${i + 1}, $moTa đặt lệch trong dòng: cách mép phải '
        '${do_[0].toStringAsFixed(1)}% (mẫu ${chuanPhai.toStringAsFixed(1)}%), '
        'tâm dọc ${do_[1].toStringAsFixed(1)}% (mẫu ${chuanDoc.toStringAsFixed(1)}%), '
        'quá mức cho phép ${saiSoPhanTram.toStringAsFixed(0)}%.',
      );
    }
  }
}

/// KIỂM KÊ ICON trên màn hình lúc capture, để màn soạn đề bày ra cho người ra đề tick.
///
/// Vì sao phải đo ở đây chứ không quét DOM như phần còn lại của "Quét UI": nút chỉ có
/// hình, không nhãn, thì trên web KHÔNG có aria-label nào để quét — đúng những nút mà
/// gói này muốn chấm lại là những nút DOM không thấy. Máy chấm nhìn thẳng cây widget nên
/// thấy đủ.
List<Map<String, dynamic>> _kiemKeIcon(WidgetTester tester) {
  final theoMa = <int, List<Element>>{};
  for (final e in find.byType(Icon).evaluate()) {
    final ma = (e.widget as Icon).icon?.codePoint;
    if (ma == null) continue;
    theoMa.putIfAbsent(ma, () => <Element>[]).add(e);
  }
  final ra = <Map<String, dynamic>>[];
  theoMa.forEach((int ma, List<Element> cac) {
    final nut = _timTheoIcon(ma);
    final cacNut = nut.evaluate().toList();
    Rect? khung;
    try {
      if (cacNut.isNotEmpty) khung = tester.getRect(_laPhanTu(cacNut.first));
    } catch (_) {
      // Icon nằm ngoài khung nhìn thì không có toạ độ; vẫn liệt kê để người ra đề thấy.
    }
    final lap = cacNut.length >= 2 ? _doLap(tester, nut) : null;
    ra.add(<String, dynamic>{
      'icon': _tenIcon(ma),
      'code': ma,
      'count': cacNut.length,
      if (cacNut.isNotEmpty)
        'button_type': cacNut.first.widget.runtimeType.toString(),
      if (khung != null) 'center_x': khung.center.dx,
      if (khung != null) 'center_y': khung.center.dy,
      if (khung != null) 'width': khung.width,
      if (khung != null) 'height': khung.height,
      if (lap != null) 'per_row': _bool(lap['per_row'], false),
    });
  });
  ra.sort((a, b) => _int(a['code'], 0).compareTo(_int(b['code'], 0)));
  return ra;
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
    final target = _asMap(checkpoint['target']);
    final khung = _khungThanhPhan(tester, target);
    if (khung == null) continue;
    // Thành phần LẶP (nút Xóa ở mỗi dòng): đo luôn đặc trưng nhóm để lúc chấm biết phải
    // soát cả nhóm chứ không chỉ cái đầu. Đo ở đây vì đây đúng khoảnh khắc mà tiêu chí
    // sẽ nhìn thấy, không có thao tác nào xen giữa.
    final lap = _doLap(tester, _finder(target));
    thanhPhan[khoa] = <String, dynamic>{
      'test_id': _text(c, 'test_id'),
      'left': khung.left,
      'top': khung.top,
      'width': khung.width,
      'height': khung.height,
      'center_x': khung.center.dx,
      'center_y': khung.center.dy,
      if (_anhCuoi != null) 'color': _mauChinhTrongVung(khung, tiLe),
      if (lap != null) 'repeat': lap,
    };
  }
  final kiemKeIcon = _kiemKeIcon(tester);
  if (thanhPhan.isEmpty &&
      _moTaNhanDaThu.isEmpty &&
      _dinhDanhDaThu.isEmpty &&
      kiemKeIcon.isEmpty) {
    return;
  }
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
      // Định danh theo khoá cũ của bước (Gói 1 kế hoạch Định danh Semantics); backend
      // nướng vào target bước, giữ nhãn cạnh bên làm đường lui.
      'identifiers': _dinhDanhDaThu,
      // Kiểm kê icon cho màn soạn đề: nút chỉ có hình không quét được qua DOM web.
      'icons': kiemKeIcon,
    }),
  );
  stdout.writeln(
    'Đã đo bố cục chuẩn: ${thanhPhan.length} thành phần, '
    '${_moTaNhanDaThu.length} nhãn có đường dự phòng, '
    '${_dinhDanhDaThu.length} bước có định danh, '
    '${kiemKeIcon.length} loại icon.',
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
    // Định danh của đúng widget vừa tìm được theo nhãn/chữ — để backend nướng vào bước,
    // nhờ đó bộ đề cũ nhận định danh sau một lần "Sinh lại testcase", không ghi hình lại.
    _thuDinhDanh(target, finder);
  }
  final index = _int(target['index'], 0);
  return index <= 0 ? finder.first : finder.at(index);
}

/// Định danh thu được trong lúc chạy Golden: mỗi phần tử một cặp {khoá cũ: giá trị,
/// semantic_id: ...}, khoá cũ là label/text/hint/tooltip/text_prefix của bước.
final List<Map<String, String>> _dinhDanhDaThu = <Map<String, String>>[];
final Set<String> _dinhDanhDaGhi = <String>{};

void _thuDinhDanh(Map<String, dynamic> target, Finder finder) {
  // Bước đã có định danh (gõ tay ở đường 2, hoặc plan đã nâng cấp) thì không cần thu.
  if (_text(target, 'semantic_id').isNotEmpty ||
      _text(target, 'semanticId').isNotEmpty) {
    return;
  }
  String khoa = '';
  String giaTri = '';
  for (final k in const ['label', 'text', 'hint', 'tooltip', 'text_prefix']) {
    final v = _text(target, k);
    if (v.isNotEmpty) {
      khoa = k;
      giaTri = v;
      break;
    }
  }
  if (khoa.isEmpty) return;
  final dau = '$khoa=$giaTri';
  if (_dinhDanhDaGhi.contains(dau)) return;
  final id = _docDinhDanhTaiDich(finder);
  if (id.isEmpty) return;
  _dinhDanhDaGhi.add(dau);
  _dinhDanhDaThu.add(<String, String>{khoa: giaTri, 'semantic_id': id});
}

/// Định danh của widget mà finder trỏ tới — ba nấc rồi leo cha, đo ở sa bàn 4/9/2026:
///  - chính nó: render object của phần tử sở hữu nút (dòng ListTile: định danh đã gộp
///    vào nút dòng; Card bọc Semantics: nút cha riêng nhãn rỗng);
///  - con cháu gần nhất có nút: wrapper Semantics không tự sở hữu nút khi đã gộp vào con;
///  - tổ tiên gần nhất có nút: Text "Lưu" trong nút bấm không có nút riêng, nút của nó
///    là nút bấm bao ngoài.
/// Sau đó chỉ leo lên cha CHƯA CÓ NHÃN (wrapper trần) tối đa vài bậc: nút Xoá nằm trong
/// dòng, cha nó có nhãn dòng, dừng ngay — kẻo gán nhầm định danh dòng cho nút Xoá.
String _docDinhDanhTaiDich(Finder finder) {
  final phanTu = finder.evaluate();
  if (phanTu.isEmpty) return '';
  final Element goc = phanTu.first;
  SemanticsNode? nut = goc.renderObject?.debugSemantics;
  if (nut == null) {
    final con = find
        .descendant(
          of: finder.first,
          matching: find.byElementPredicate(
            (e) => e is RenderObjectElement && e.renderObject.debugSemantics != null,
          ),
        )
        .evaluate();
    if (con.isNotEmpty) nut = con.first.renderObject?.debugSemantics;
  }
  if (nut == null) {
    goc.visitAncestorElements((Element a) {
      final n = a.renderObject?.debugSemantics;
      if (n != null) {
        nut = n;
        return false;
      }
      return true;
    });
  }
  var n = nut;
  var buoc = 0;
  while (n != null) {
    if (n.identifier.isNotEmpty) return n.identifier;
    final cha = n.parent;
    if (cha == null || cha.label.isNotEmpty || buoc >= 4) break;
    n = cha;
    buoc++;
  }
  return '';
}

/// Mô tả dự phòng thu được trong lúc chạy Golden, gom theo nhãn.
///
/// Phải thu ĐÚNG LÚC bấm, không thu ở cuối kịch bản: cuối kịch bản màn hình đã chuyển
/// đi, nhãn của màn trước không còn trên cây nên đo ra rỗng.
final Map<String, dynamic> _moTaNhanDaThu = <String, dynamic>{};

/// Loi TRAN BO CUC bat duoc trong luong hien tai, khu trung theo dong dau (overflow
/// bao lai moi frame nen khong khu thi phinh vo han).
///
/// Vi sao ghi lai thay vi de no giet kich ban: do that tren sa ban, mot RenderFlex
/// tran 388px lam CA kich ban thanh BEHAVIOR_REPLAY_FAILURE — mot loi cosmetic tren
/// man Them la mat trang ham ADD. Tran bo cuc dang la mot DAU DIEM RIENG (no_overflow),
/// khong dang la an tu cho moi tieu chi khac tren cung man.
final Set<String> _loiTranBoCuc = <String>{};

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
      var chu = find.text(chuCon);
      if (chu.evaluate().isEmpty) chu = _timChuGan(chuCon);
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

/// Chữ NHÌN THẤY của một chuỗi: gộp mọi khoảng trắng liên tiếp (kể cả xuống dòng,
/// khoảng trắng Unicode) thành một dấu cách, cắt hai đầu.
///
/// Vì sao: nhãn ngữ nghĩa của một dòng danh sách là các nhãn con NỐI BẰNG XUỐNG DÒNG,
/// nên số dấu xuống dòng phụ thuộc cây widget chứ không phụ thuộc chữ hiện ra. Golden
/// viết subtitle bằng MỘT Text → "tiêu đề⏎phụ đề"; bài HE230112 viết subtitle bằng Row
/// hai Text → "tiêu đề⏎số tiền⏎ · loại · ngày". Màn hình vẽ y hệt nhau mà so tuyệt đối
/// thì trượt 17/112 tiêu chí — đo 4/9/2026, mất 2.9 điểm trên một bài đúng hoàn toàn.
/// Phép so với giao diện phải khớp thứ người dùng NHÌN THẤY, không khớp cách lồng widget.
String _chuNhinThay(String s) => s.replaceAll(RegExp(r'\s+'), ' ').trim();

/// Phần tử mà NHÃN nút ngữ nghĩa của nó, sau chuẩn hoá khoảng trắng, khớp chuỗi —
/// dùng khi bySemanticsLabel (khớp tuyệt đối) không thấy.
///
/// Chỉ so nhãn CỦA CHÍNH nút, không cộng nhãn con. Bản đầu (4/9/2026) đọc gộp cả cây
/// con, đo trên HE230112 vẫn 17 trượt: nút Xoá cuối ListTile là nút con riêng, cộng
/// nhãn nó vào là lệch khỏi nhãn Golden. Nhãn thu ở Golden và nhãn so ở bài làm phải
/// là CÙNG một khái niệm — nhãn của một nút.
///
/// Hai mức khớp, đo ở sa bàn 4/9/2026 (dòng nào cũng có onTap + nút Xoá như app thật):
///  - BẰNG: ListTile 1 Text, ListTile + Row 2 Text (HE230112), Card + IconButton trần,
///    khoảng trắng thừa, bọc Semantics(container) → mỗi kiểu 1 đích, chạm tới onTap.
///  - Nhãn NHIỀU DÒNG (cụm dòng danh sách) được đứng NGUYÊN VẸN bên trong nhãn nút:
///    Card/InkWell/GestureDetector hút luôn nhãn nút Xoá bọc Semantics vào dòng, ra
///    "title⏎sub⏎Xóa khoản chi" — ListTile thì tách nút đó thành con riêng. Cùng một
///    bài đúng, hai cách vẽ, không được lệch điểm.
///  - Nhãn MỘT DÒNG (nút, ô nhập) chỉ được BẰNG, không "chứa": "Xóa khoản chi" nằm
///    trong nhãn dòng mà chạm vào dòng là mở form sửa, không phải xoá.
/// Đổi thứ tự trường hay dòng khác số tiền → 0 đích ở mọi mức. Chỉ xét
/// RenderObjectElement như bySemanticsLabel: nhiều Element cùng trỏ một render object.
Finder _timDocNhu(String chuoi) {
  final dich = _chuNhinThay(chuoi);
  if (dich.isEmpty) return find.byWidgetPredicate((_) => false);
  final laCum = chuoi.trim().contains('\n');
  return find.byElementPredicate((Element e) {
    if (e is! RenderObjectElement) return false;
    final SemanticsNode? nut = e.renderObject.debugSemantics;
    if (nut == null) return false;
    final nhan = _chuNhinThay(nut.label);
    if (nhan == dich) return true;
    return laCum && ' $nhan '.contains(' $dich ');
  }, description: 'nhãn đọc như "$dich"');
}

/// Widget `Semantics` khai đúng nhãn (đã chuẩn hoá) — đường tìm KHÔNG phụ thuộc Flutter
/// gộp nhãn vào nút cha thế nào (xem chú thích "NHÃN BỊ HÚT VÀO NÚT CHA" trong _finder).
Finder _timWidgetNhan(String chuoi) {
  final dich = _chuNhinThay(chuoi);
  return find.byWidgetPredicate(
    (w) => w is Semantics && _chuNhinThay(w.properties.label ?? '') == dich,
  );
}

/// Widget Text có nội dung bằng chuỗi SAU KHI chuẩn hoá khoảng trắng — nấc giữa
/// find.text (tuyệt đối) và _timDocNhu (ngữ nghĩa).
Finder _timChuGan(String chuoi) {
  final dich = _chuNhinThay(chuoi);
  return find.byWidgetPredicate(
    (w) =>
        w is Text &&
        _chuNhinThay(w.data ?? w.textSpan?.toPlainText() ?? '') == dich,
  );
}

/// "Chữ X có trên màn hình không?" — ba nấc từ rẻ tới đắt, nấc nào thấy thì dừng:
/// Text khớp tuyệt đối (đường cũ, giữ nguyên), Text khớp sau chuẩn hoá, rồi nút ngữ
/// nghĩa đọc ra đúng X (dòng danh sách bị tách/gộp Text khác Golden).
bool _coChu(String value) {
  if (find.text(value).evaluate().isNotEmpty) return true;
  final dich = _chuNhinThay(value);
  if (dich.isEmpty) return false;
  if (_timChuGan(dich).evaluate().isNotEmpty) return true;
  return _timDocNhu(dich).evaluate().isNotEmpty;
}

Finder _finder(Map<String, dynamic> target, {bool duPhong = false}) {
  // ĐỊNH DANH — Semantics(identifier:) do sinh viên khai theo hợp đồng đề, không phụ
  // thuộc cách vẽ. Trước đây năm khoá dưới đều quy về find.byKey, tức "semantic_id"
  // bị hiểu nhầm là ValueKey; mà ValueKey không bao giờ ra tới DOM nên recorder không
  // ghi được, còn identifier thì ra tới DOM (flt-semantics-identifier) — đo 4/9/2026.
  // Không thấy định danh thì rơi xuống nhãn/chữ như cũ: quên định danh không mất thêm
  // điểm nào so với trước, nó là bảo hiểm cho bài vẽ khác Golden, không phải bẫy mới.
  for (final keyName in const ['semanticId', 'semantic_id']) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final finder = find.bySemanticsIdentifier(value);
      if (finder.evaluate().isNotEmpty) return finder;
    }
  }
  for (final keyName in const ['valueKey', 'value_key', 'key']) {
    final value = _text(target, keyName);
    if (value.isNotEmpty) {
      final finder = find.byKey(ValueKey<String>(value));
      if (finder.evaluate().isNotEmpty) return finder;
    }
  }
  final label = _text(target, 'label');
  final hint = _text(target, 'hint');
  // ICON — hình dạng khai thẳng trong hợp đồng đề ("Icons.delete_outline"). Đặt TRƯỚC
  // nhãn: tiêu chí icon nói về HÌNH, không được để nhãn của bài làm dẫn sang widget
  // khác rồi báo đạt. Trả về nút bao ngoài nên chạm và đo đều đúng chỗ.
  final khaiIcon = _text(target, 'icon');
  if (khaiIcon.isNotEmpty) {
    final ma = _maIcon(khaiIcon);
    if (ma != null) {
      final finder = _timTheoIcon(ma);
      if (finder.evaluate().isNotEmpty) return finder;
      // Chưa thấy icon mà target KHÔNG khai gì khác thì trả finder rỗng để `_waitUntil`
      // còn poll tiếp — chứ không rơi xuống nhãn, rơi xuống là chấm nhầm sang thứ khác.
      if (label.isEmpty && hint.isEmpty && _text(target, 'text').isEmpty) {
        return find.byWidgetPredicate((_) => false);
      }
    }
  }
  if (label.isNotEmpty) {
    final semantics = find.bySemanticsLabel(label);
    if (semantics.evaluate().isNotEmpty) return semantics;
    // Không khớp tuyệt đối thì khớp theo CHỮ NHÌN THẤY (xem _timDocNhu).
    final docNhu = _timDocNhu(label);
    if (docNhu.evaluate().isNotEmpty) return docNhu;
  }
  // NHÃN HAI DÒNG. Dòng danh sách (ListTile) gộp title + subtitle thành một nhãn
  // ngăn bởi xuống dòng; recorder đời cũ tách nhầm thành label + hint (phép tách đó
  // chỉ đúng cho ô nhập). Ghép lại để so tuyệt đối — thiếu nhánh này thì bấm vào
  // dòng danh sách không bao giờ tìm thấy đích, cả kịch bản EDIT chết theo.
  if (label.isNotEmpty && hint.isNotEmpty) {
    final gop = find.bySemanticsLabel('$label\n$hint');
    if (gop.evaluate().isNotEmpty) return gop;
    final gopDocNhu = _timDocNhu('$label\n$hint');
    if (gopDocNhu.evaluate().isNotEmpty) return gopDocNhu;
  }
  // NHÃN BỊ HÚT VÀO NÚT CHA. Card/InkWell/GestureDetector hút nhãn của nút Xoá bọc
  // Semantics vào nhãn dòng ("title⏎sub⏎Xóa khoản chi"), còn chính nút Xoá thành nút
  // con KHÔNG nhãn — không còn nút ngữ nghĩa nào mang riêng "Xóa khoản chi". ListTile
  // thì giữ nút đó tách riêng nên hai đường trên đủ. Tìm theo WIDGET Semantics khai nhãn
  // thì không phụ thuộc Flutter gộp thế nào; chạm vào tâm nó là chạm nút bên trong.
  // Đo 4/9/2026: ở Card lẫn GestureDetector chạm tới đúng onPressed của nút Xoá, không
  // mở form sửa. Thiếu nhánh này thì kịch bản DELETE chết ở mọi bài không dùng ListTile.
  if (label.isNotEmpty) {
    final widgetNhan = _timWidgetNhan(label);
    if (widgetNhan.evaluate().isNotEmpty) return widgetNhan;
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
      // So sau chuẩn hoá khoảng trắng: nhãn ô nhập thừa một dấu cách cuối là thứ
      // người chấm không nhìn thấy, không được thành lý do trượt.
      final nhan = _chuNhinThay(label);
      final goiY = _chuNhinThay(hint);
      final labelText = _chuNhinThay(decoration?.labelText ?? '');
      final hintText = _chuNhinThay(decoration?.hintText ?? '');
      return decoration != null &&
          (label.isEmpty || labelText == nhan || hintText == nhan) &&
          (hint.isEmpty || hintText == goiY || labelText == goiY);
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
  if (text.isNotEmpty) {
    final chinhXac = find.text(text);
    if (chinhXac.evaluate().isNotEmpty) return chinhXac;
    final gan = _timChuGan(text);
    if (gan.evaluate().isNotEmpty) return gan;
    // Có thể rỗng — _waitUntil gọi lại _finder mỗi nhịp nên vẫn poll được.
    return _timDocNhu(text);
  }
  // TIỀN TỐ VĂN BẢN. Hợp đồng nhãn của đề khai `text_prefix` cho những dòng mà phần
  // đuôi thay đổi theo dữ liệu — ví dụ "Tổng tháng: 608.000 ₫". find.text so khớp
  // TUYỆT ĐỐI nên không dùng được ở đây; thiếu nhánh này thì đúng những mục hợp đồng
  // ấy không có cách nào kiểm.
  final textPrefix = _text(target, 'text_prefix');
  if (textPrefix.isNotEmpty) {
    final tienTo = _chuNhinThay(textPrefix);
    return find.byWidgetPredicate(
      (widget) =>
          widget is Text && _chuNhinThay(widget.data ?? '').startsWith(tienTo),
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
    'icon',
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
