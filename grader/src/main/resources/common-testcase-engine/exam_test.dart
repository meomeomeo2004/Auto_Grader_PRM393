import 'dart:convert';
import 'dart:io';
import 'dart:ui' show Size;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

// Engine chung chỉ nhìn vào semantic key công khai, không import model/repository của bài.
import '../lib/main.dart' as student_app;

/// Progress marker consumed only by grader.dart when a process is force-stopped.
/// It lets the backend tell whether the last risky operation belonged to the
/// student app or to the testcase engine, without exposing implementation keys.
/// 3.6.0: thêm tám runner Ch.7 — SCROLL_DIRECTION, SCROLL_TO_END, STACK_LAYERS,
/// INDEXED_STACK_SWITCH, BOTTOM_SHEET_FLOW, TABLE_ROWS, SLIVER_SCROLL_COLLAPSE,
/// EXPANDED_WIDGET. Mở rộng _assertTargetType với 8 loại widget mới.
/// Cả tám runner định vị bằng Semantics(identifier:) qua `_byIdentifier`, không
/// dùng ValueKey — đồng bộ với cách `behavior-replay-engine/exam_test.dart` (engine
/// đang dùng thật) đã chuyển sang từ commit bb0ab32. Điểm đề cũ KHÔNG ĐỔI.
const String kEngineVersion = 'COMMON_V1-3.6.0';
const String kStageMarker = '###GRADER_STAGE###';

void _stage(String value) => print('$kStageMarker$value');

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  // Máy chấm chạy Linux headless, không có platform channel Android/iOS của sqflite.
  // Khởi tạo factory FFI một lần ở runner để mọi bài dùng sqflite đều mở DB thật được.
  sqfliteFfiInit();
  // noIsolate tránh isolate nền của sqflite_common_ffi bị treo trong Docker headless.
  databaseFactory = createDatabaseFactoryFfi(noIsolate: true);
  final matrix = _loadMatrix();
  final mode = Platform.environment['GRADER_CASE_MODE'] ?? 'all';
  final selectedCase = Platform.environment['GRADER_CASE_ID'];
  final selectedBatch = (Platform.environment['GRADER_CASE_IDS'] ?? '')
      .split(',')
      .where((id) => id.isNotEmpty)
      .toSet();

  for (final entry in matrix.entries) {
    final testId = entry.key;
    if (mode == 'case' && selectedCase != testId) continue;
    if (mode == 'batch' && !selectedBatch.contains(testId)) continue;
    final metadata = _asMap(entry.value);
    // Testcase code tay được sinh thành testWidgets riêng, nhưng vẫn đăng ký ĐÚNG VỊ TRÍ
    // trong đề: một testcase chuẩn bị dữ liệu đặt đầu danh sách phải chạy trước phần sau.
    if (_text(metadata, 'runner') == 'CUSTOM_CODE') {
      _registerCustomTestcase(testId);
      continue;
    }
    testWidgets(testId, (tester) async {
      await _runCase(tester, testId, metadata);
    });
  }
}

// ─────────────────── CUSTOM_TESTCASES_BEGIN ───────────────────
// Vùng này do backend sinh lại mỗi lần lưu cấu hình testcase (phần "Tự viết code"
// của giáo viên). Sửa tay ở đây sẽ bị ghi đè ở lần lưu kế tiếp.
void _registerCustomTestcase(String testId) {}
// ──────────────────── CUSTOM_TESTCASES_END ────────────────────

/// Bỏ comment trước khi các testcase source-contract tìm token.
///
/// Không dùng regex đơn giản vì `//` và `/*` có thể nằm trong chuỗi
/// (URL là trường hợp phổ biến). State machine này giữ nguyên nội dung chuỗi,
/// xuống dòng và chỉ loại comment thật. YAML dùng `#` thay cho comment Dart.
String _sourceWithoutComments(String input, String path) {
  final yaml = path.endsWith('.yaml') || path.endsWith('.yml');
  final output = StringBuffer();
  var inLineComment = false;
  var inBlockComment = false;
  var inSingleQuote = false;
  var inDoubleQuote = false;
  var escaped = false;

  for (var index = 0; index < input.length; index++) {
    final current = input[index];
    final next = index + 1 < input.length ? input[index + 1] : '';

    if (inLineComment) {
      if (current == '\n') {
        inLineComment = false;
        output.write(current);
      }
      continue;
    }
    if (inBlockComment) {
      if (current == '*' && next == '/') {
        inBlockComment = false;
        index++;
      } else if (current == '\n') {
        output.write(current);
      }
      continue;
    }
    if (inSingleQuote || inDoubleQuote) {
      output.write(current);
      if (escaped) {
        escaped = false;
      } else if (current == '\\') {
        escaped = true;
      } else if (inSingleQuote && current == "'") {
        inSingleQuote = false;
      } else if (inDoubleQuote && current == '"') {
        inDoubleQuote = false;
      }
      continue;
    }

    if (current == "'") {
      inSingleQuote = true;
      output.write(current);
    } else if (current == '"') {
      inDoubleQuote = true;
      output.write(current);
    } else if (yaml && current == '#') {
      inLineComment = true;
    } else if (!yaml && current == '/' && next == '/') {
      inLineComment = true;
      index++;
    } else if (!yaml && current == '/' && next == '*') {
      inBlockComment = true;
      index++;
    } else {
      output.write(current);
    }
  }
  return output.toString();
}

/// So khớp token của public contract mà không phụ thuộc cách format mã nguồn.
///
/// Ví dụ `int? id`, `int ? id` và xuống dòng quanh dấu `?` đều là cùng một
/// khai báo Dart. Testcase contract không được trừ điểm chỉ vì sinh viên chạy
/// formatter khác, nhưng vẫn phải phân biệt đúng tên class/field/method.
bool _sourceContainsToken(
  String source,
  String token, {
  required bool caseSensitive,
}) {
  String normalize(String value) => value
      .replaceAll(RegExp(r'\s+'), ' ')
      .replaceAllMapped(
        RegExp(r'\s*([?(),;{}\[\]:])\s*'),
        (match) => match.group(1)!,
      )
      .trim();

  var normalizedSource = normalize(source);
  var normalizedToken = normalize(token);
  if (!caseSensitive) {
    normalizedSource = normalizedSource.toLowerCase();
    normalizedToken = normalizedToken.toLowerCase();
  }
  return normalizedSource.contains(normalizedToken);
}

Future<void> _runCase(
  WidgetTester tester,
  String testId,
  Map<String, dynamic> metadata,
) async {
  _stage('TESTCASE_DISPATCH');
  final runner = (metadata['runner'] ?? '').toString();
  final parameters = _asMap(metadata['parameters']);

  switch (runner) {
    case 'APP_BOOT':
      await _boot(tester);
      final rootKey = _text(parameters, 'rootKey');
      if (rootKey.isNotEmpty) {
        _expectPresent(
          _byKey(rootKey),
          'screen',
          'Không tìm thấy màn hình gốc: $rootKey',
        );
      }
      expect(_takeRelevantException(tester), isNull);
      return;
    case 'WIDGET_VISIBLE':
      await _boot(tester);
      final widgetKey = _requiredText(parameters, 'widgetKey');
      final widgetFinder = _byKey(widgetKey);
      _expectPresent(
        widgetFinder,
        _subject(parameters),
        'Không tìm thấy widget key: $widgetKey',
      );
      final visibleType = _text(parameters, 'targetType');
      if (visibleType.isNotEmpty) {
        _assertTargetType(tester, widgetFinder, widgetKey, visibleType);
      }
      return;
    case 'WIDGET_TYPE_VISIBLE':
      await _checkWidgetTypeVisible(tester, parameters);
      return;
    case 'WIDGET_TEXT_CONTENT':
      await _checkWidgetTextContent(tester, parameters);
      return;
    case 'WIDGET_ENABLED':
      await _checkWidgetEnabled(tester, parameters);
      return;
    case 'FORM_VALIDATE_FIELDS':
      await _checkFormValidateFields(tester, parameters);
      return;
    case 'FORM_PREFILL':
      await _checkFormPrefill(tester, parameters);
      return;
    case 'FORM_SUBMIT':
      await _checkFormSubmit(tester, parameters);
      return;
    case 'LIST_ITEM_COUNT':
      await _checkListItemCount(tester, parameters);
      return;
    case 'DIALOG_FLOW':
      await _checkDialogFlow(tester, parameters);
      return;
    case 'WIDGET_SEMANTICS_LABEL':
      await _checkWidgetSemanticsLabel(tester, parameters);
      return;
    case 'STATE_REACTIVE_FLOW':
      await _checkStateReactiveFlow(tester, parameters);
      return;
    case 'FORM_PERSISTENCE_FLOW':
      await _checkFormPersistenceFlow(tester, parameters);
      return;
    case 'CRUD_EDIT_FLOW':
      await _checkCrudEditFlow(tester, parameters);
      return;
    case 'CRUD_DELETE_FLOW':
      await _checkCrudDeleteFlow(tester, parameters);
      return;
    case 'CRUD_DETAIL_FLOW':
      await _checkCrudDetailFlow(tester, parameters);
      return;
    case 'RESPONSIVE_GRID_FLOW':
      await _checkResponsiveGridFlow(tester, parameters);
      return;
    case 'GROUP':
      await _checkGroup(tester, testId, metadata);
      return;
    case 'CUSTOM_CODE':
      // Không bao giờ chạy tới đây: testcase code tay được đăng ký riêng ở
      // _registerCustomTestcases. Nếu rơi vào đây nghĩa là file sinh ra bị lệch.
      fail('Testcase code tay $testId chưa được sinh vào exam_test.dart.');
    case 'FORM_REQUIRED_FIELDS':
      await _boot(tester);
      for (final key in _csv(parameters, 'fieldKeys')) {
        _expectPresent(_byKey(key), 'field', 'Thiếu field semantic key: $key');
      }
      final submitKey = _text(parameters, 'submitKey');
      await _tap(tester, _byKey(submitKey), 'Thiếu submit key: $submitKey');
      await _settle(tester);
      _failIfActionThrew(tester);
      for (final key in _csv(parameters, 'errorKeys')) {
        _expectPresent(
          _byKey(key),
          'error',
          'Thiếu error key: $key',
          where: 'after_action',
        );
      }
      return;
    case 'RESPONSIVE_NO_OVERFLOW':
      await _responsive(tester, parameters);
      return;
    case 'RESPONSIVE_TARGET':
      await _responsiveTarget(tester, parameters);
      return;
    case 'WIDGET_DIMENSION':
      await _checkWidgetDimension(tester, parameters);
      return;
    case 'WIDGET_PADDING':
      await _checkWidgetPadding(tester, parameters);
      return;
    case 'WIDGET_TEXT_STYLE':
      await _checkWidgetTextStyle(tester, parameters);
      return;
    case 'WIDGET_GAP':
      await _checkWidgetGap(tester, parameters);
      return;
    case 'NAVIGATION':
      await _boot(tester);
      final openKey = _text(parameters, 'openKey');
      final destinationKey = _text(parameters, 'destinationKey');
      await _tap(
        tester,
        _byKey(openKey),
        'Không tìm thấy nút mở màn hình đích: $openKey',
      );
      await _settle(tester);
      _failIfActionThrew(tester);
      _expectPresent(
        _byKey(destinationKey),
        'screen',
        'Không mở được màn hình đích: $destinationKey',
        where: 'after_action',
      );
      final backKey = _text(parameters, 'backKey');
      final homeKey = _text(parameters, 'homeKey');
      if (backKey.isNotEmpty && homeKey.isNotEmpty) {
        await _tap(
          tester,
          _byKey(backKey),
          'Không tìm thấy nút quay lại: $backKey',
          where: 'after_action',
        );
        await _settle(tester);
        _failIfActionThrew(tester);
        _expectPresent(
          _byKey(homeKey),
          'screen',
          'Sau khi quay lại, không thấy màn hình trước: $homeKey',
          where: 'after_action',
        );
        // Phải kiểm cả chiều BIẾN MẤT. Một mình `homeKey` là phép kiểm KHÔNG THỂ HỎNG:
        // màn hình trước vẫn nằm trong cây widget suốt lúc màn hình sau đang mở, nên nút
        // quay lại có bấm được hay không thì nó vẫn đạt. Chính chỗ này từng che lỗi
        // `_settle` không đẩy đồng hồ ảo, làm cả một lỗi chấm sai điểm không ai thấy.
        _expectGone(
          _goneByKey(destinationKey),
          'screen',
          'Bấm quay lại nhưng màn hình $destinationKey vẫn đang hiển thị',
          where: 'after_action',
        );
      }
      return;
    case 'LIST_VISIBLE':
      await _boot(tester);
      final listKey = _text(parameters, 'listKey');
      _expectPresent(
        _byKey(listKey),
        'list',
        'Không tìm thấy list key: $listKey',
      );
      for (final key in _csv(parameters, 'itemKeys')) {
        _expectPresent(_byKey(key), 'item', 'Thiếu item semantic key: $key');
      }
      return;
    case 'BUTTON_ACTION':
      await _boot(tester);
      final buttonKey = _text(parameters, 'buttonKey');
      final resultKey = _text(parameters, 'resultKey');
      await _tap(
        tester,
        _byKey(buttonKey),
        'Không tìm thấy nút cần bấm: $buttonKey',
      );
      await _settle(tester);
      _failIfActionThrew(tester);
      _expectPresent(
        _byKey(resultKey),
        _subject(parameters),
        'Bấm xong nhưng không thấy kết quả: $resultKey',
        where: 'after_action',
      );
      return;
    // ── Ch.7: Widget bố cục và hiển thị nâng cao ──
    case 'SCROLL_DIRECTION':
      await _checkScrollDirection(tester, parameters);
      return;
    case 'SCROLL_TO_END':
      await _checkScrollToEnd(tester, parameters);
      return;
    case 'STACK_LAYERS':
      await _checkStackLayers(tester, parameters);
      return;
    case 'INDEXED_STACK_SWITCH':
      await _checkIndexedStackSwitch(tester, parameters);
      return;
    case 'BOTTOM_SHEET_FLOW':
      await _checkBottomSheetFlow(tester, parameters);
      return;
    case 'TABLE_ROWS':
      await _checkTableRows(tester, parameters);
      return;
    case 'SLIVER_SCROLL_COLLAPSE':
      await _checkSliverScrollCollapse(tester, parameters);
      return;
    case 'EXPANDED_WIDGET':
      await _checkExpandedWidget(tester, parameters);
      return;
    default:
      fail('Testcase $testId chưa có common runner: $runner');
  }
}

Future<void> _checkGroup(
  WidgetTester tester,
  String groupId,
  Map<String, dynamic> metadata,
) async {
  final rawChildren = metadata['children'];
  if (rawChildren is! List || rawChildren.isEmpty) {
    fail('Nhóm $groupId không có testcase con.');
  }

  final previousDepth = _groupDepth;
  final previousBooted = _groupBooted;
  _groupDepth++;
  _groupBooted = false;
  final failures = <String>[];
  try {
    for (final rawChild in rawChildren) {
      final child = _asMap(rawChild);
      final childId = _text(child, 'instance_id', 'child');
      try {
        await _runCase(tester, '$groupId/$childId', child);
      } catch (error) {
        // Nêu TÊN yêu cầu con (tiếng Việt, giáo viên nhập) chứ không nêu instance_id:
        // chuỗi này chảy vào result_json nên không được lộ định danh nội bộ.
        failures.add('${_text(child, 'name', 'Yêu cầu con')}: $error');
      }
    }
  } finally {
    _groupDepth = previousDepth;
    _groupBooted = previousBooted;
  }

  if (failures.isNotEmpty) {
    // Không dùng chữ "assert" và không đếm số testcase con — từ vựng nội bộ của hệ thống
    // chấm, sinh viên không kiểm chứng được (cùng lý do đã sửa `expected` của GROUP).
    fail(
      'Chưa đạt yêu cầu "${metadata['name'] ?? groupId}":\n${failures.join('\n')}',
    );
  }
}

Future<void> _fillFields(
  WidgetTester tester,
  Map<String, dynamic> parameters, {
  String fieldKeysName = 'fieldKeys',
  String valuesName = 'values',
}) async {
  final fields = _csv(parameters, fieldKeysName);
  final values = _csv(parameters, valuesName);
  if (fields.isEmpty || fields.length != values.length) {
    fail('$fieldKeysName và $valuesName phải có cùng số phần tử.');
  }
  for (var index = 0; index < fields.length; index++) {
    final finder = _byKey(fields[index]);
    _expectPresent(finder, 'field', 'Thiếu field key: ${fields[index]}');
    await _setFormControlValue(
      tester,
      fields[index],
      finder,
      _decodeInput(values[index]),
    );
  }
}

/// Điền một control theo khả năng quan sát thay vì ép mọi trường thành TextField.
/// Các đề có avatar/category/date thường dùng nút mở picker hoặc dropdown. Nếu
/// giá trị rỗng thì giữ nguyên control để testcase validation kiểm tra lỗi required.
Future<void> _setFormControlValue(
  WidgetTester tester,
  String semanticKey,
  Finder finder,
  String value,
) async {
  final editable = find.descendant(
    of: finder,
    matching: find.byType(EditableText, skipOffstage: false),
    matchRoot: true,
  );
  if (editable.evaluate().isNotEmpty) {
    await tester.enterText(editable.first, value);
    return;
  }
  if (value.isEmpty) return;

  final widget = tester.widget<Widget>(finder.first);
  final selectable =
      widget is ButtonStyleButton ||
      widget is IconButton ||
      widget is DropdownButton ||
      widget is DropdownButtonFormField ||
      widget is InkWell ||
      widget is GestureDetector;
  if (!selectable) {
    fail('Control $semanticKey không hỗ trợ nhập text hoặc chọn giá trị.');
  }

  await tester.tap(finder.first);
  await _settle(tester);
  _failIfActionThrew(tester);

  // Picker local trong starter thường mở DropdownMenuItem/ListTile. Chọn mục
  // đầu tiên ở overlay thay vì phụ thuộc tên asset hay ngôn ngữ hiển thị.
  final menuItems = find.byType(DropdownMenuItem, skipOffstage: false);
  if (menuItems.evaluate().isNotEmpty) {
    await tester.tap(menuItems.first);
    await _settle(tester);
    return;
  }
  final options = find.byType(ListTile, skipOffstage: false);
  if (options.evaluate().isNotEmpty) {
    await tester.tap(options.last);
    await _settle(tester);
    return;
  }

  fail('Control $semanticKey đã mở nhưng không tìm thấy lựa chọn cục bộ.');
}

Future<void> _submitCurrentForm(
  WidgetTester tester,
  Map<String, dynamic> parameters, {
  String submitKeyName = 'submitKey',
  String resultKeyName = 'resultKey',
}) async {
  final submitKey = _requiredText(parameters, submitKeyName);
  await _tap(tester, _byKey(submitKey), 'Không tìm thấy nút lưu: $submitKey');
  await _settle(tester);
  _failIfActionThrew(tester);
  final resultKey = _text(parameters, resultKeyName);
  if (resultKey.isNotEmpty) {
    await _revealKey(tester, resultKey);
    _expectPresent(
      _byKey(resultKey),
      'item',
      'Lưu xong nhưng không thấy kết quả: $resultKey',
      where: 'after_action',
    );
  }
}

Finder _actionInside(String itemKey, String actionKey) {
  final item = _byKey(itemKey);
  _expectPresent(item, 'item', 'Không tìm thấy item cần thao tác: $itemKey');

  // Ưu tiên key tuyệt đối trước: nhiều starter đặt key trực tiếp trên TextButton
  // trong overlay của dialog. Key duy nhất đã xác định chính xác action cần tap.
  final exact = find.byKey(ValueKey<String>(actionKey), skipOffstage: false);
  if (exact.evaluate().length == 1) return exact;

  final rule = _contractRule(actionKey);
  final scoped = rule == null ? null : _contractFinderWithin(rule, item);
  if (scoped != null && scoped.evaluate().isNotEmpty) return scoped;

  // Nếu contract không được mount/đọc ở process testcase, fallback vai trò vẫn
  // phải được scope trong đúng item/dialog; không được trả action toàn màn hình.
  final role = _roleActionFinder(actionKey);
  final scopedRole = find.descendant(
    of: item,
    matching: role,
    matchRoot: true,
  );
  if (scopedRole.evaluate().isNotEmpty) return scopedRole;

  // Chỉ dùng descendant cho trường hợp hiếm có nhiều widget trùng actionKey.
  return find.descendant(
    of: item,
    matching: exact,
    matchRoot: true,
  );
}
/// Đưa item lazy của ListView/GridView vào cây widget trước khi kiểm tra/tap.
/// Không dùng pumpAndSettle vì animation hợp lệ có thể chạy vô hạn.
Future<void> _revealKey(WidgetTester tester, String key) async {
  if (_byKey(key).evaluate().isNotEmpty) return;
  for (var attempt = 0; attempt < 30; attempt++) {
    final scrollables = find.byType(Scrollable, skipOffstage: false);
    final count = scrollables.evaluate().length;
    if (count == 0) return;
    await tester.drag(scrollables.at(count - 1), const Offset(0, -180));
    await _settle(tester);
    if (_byKey(key).evaluate().isNotEmpty) return;
  }
}

Future<void> _restartStudentApp(WidgetTester tester) async {
  await tester.pumpWidget(const SizedBox.shrink());
  await tester.pump();
  final wasBooted = _groupBooted;
  _groupBooted = false;
  try {
    await _boot(tester);
  } finally {
    if (_groupDepth == 0) _groupBooted = wasBooted;
  }
}

/// Luồng tái sử dụng: nhập form → lưu → dựng app mới → dữ liệu vẫn còn.
/// Không biết tên Model/Repository/bảng SQLite; chỉ kiểm tra kết quả quan sát được.
Future<void> _checkFormPersistenceFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  await _fillFields(tester, parameters);
  await _submitCurrentForm(tester, parameters);
  await _restartStudentApp(tester);
  final resultKey = _requiredText(parameters, 'resultKey');
  await _revealKey(tester, resultKey);
  _expectPresent(
    _byKey(resultKey),
    'item',
    'Dữ liệu không còn sau khi dựng lại ứng dụng: $resultKey',
    where: 'after_restart',
  );
}

/// Luồng sửa tổng quát: tự tạo fixture qua UI, tìm action bên trong đúng item,
/// kiểm tra prefill rồi submit dữ liệu mới. Mọi điểm nối đều là tham số semantic key.
Future<void> _checkCrudEditFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  await _fillFields(tester, parameters, valuesName: 'seedValues');
  await _submitCurrentForm(tester, parameters, resultKeyName: 'seedResultKey');

  final itemKey = _requiredText(parameters, 'itemKey');
  final editKey = _requiredText(parameters, 'editKey');
  await _revealKey(tester, itemKey);
  final edit = _actionInside(itemKey, editKey);
  await _tap(tester, edit, 'Không tìm thấy nút sửa trong đúng item: $itemKey');
  await _settle(tester);
  _failIfActionThrew(tester);

  final fields = _csv(parameters, 'fieldKeys');
  final prefill = _csv(parameters, 'expectedPrefillValues');
  if (fields.length != prefill.length) {
    fail('fieldKeys và expectedPrefillValues phải có cùng số phần tử.');
  }
  for (var index = 0; index < fields.length; index++) {
    final field = _byKey(fields[index]);
    _expectPresent(field, 'field', 'Thiếu field khi sửa: ${fields[index]}');
    final editable = find.descendant(
      of: field,
      matching: find.byType(EditableText, skipOffstage: false),
      matchRoot: true,
    );
    // Picker/dropdown không công khai đường dẫn asset dưới dạng text editable.
    // Sự hiện diện và khả năng chọn lại của control đã được _fillFields kiểm tra;
    // chỉ đối chiếu prefill chính xác với control có giá trị text quan sát được.
    if (editable.evaluate().isEmpty) continue;
    final actual = tester.widget<EditableText>(editable.first).controller.text;
    expect(
      actual,
      _decodeInput(prefill[index]),
      reason: 'Dữ liệu prefill không đúng.',
    );
  }

  await _fillFields(tester, parameters, valuesName: 'updatedValues');
  await _submitCurrentForm(
    tester,
    parameters,
    resultKeyName: 'updatedResultKey',
  );
  final oldResultKey = _text(parameters, 'oldResultKey');
  if (oldResultKey.isNotEmpty) {
    _expectGone(
      _byKey(oldResultKey),
      'item',
      'Sửa xong nhưng dữ liệu cũ vẫn còn hiển thị.',
      where: 'after_action',
    );
  }
}

/// Luồng xóa tổng quát: seed → mở dialog → hủy giữ nguyên item → mở lại → xác nhận
/// và kiểm tra đúng item đã biến mất.
Future<void> _checkCrudDeleteFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  await _fillFields(tester, parameters, valuesName: 'seedValues');
  await _submitCurrentForm(tester, parameters, resultKeyName: 'seedResultKey');

  final itemKey = _requiredText(parameters, 'itemKey');
  final deleteKey = _requiredText(parameters, 'deleteKey');
  final dialogKey = _requiredText(parameters, 'dialogKey');
  final cancelKey = _requiredText(parameters, 'cancelKey');
  final confirmKey = _requiredText(parameters, 'confirmKey');
  _reloadContract();

  await _revealKey(tester, itemKey);

  Future<void> openDialog() async {
    await _tap(
      tester,
      _actionInside(itemKey, deleteKey),
      'Không tìm thấy nút xóa trong đúng item.',
    );
    await _settle(tester);
    _failIfActionThrew(tester);
    _expectPresent(
      _byKey(dialogKey),
      'dialog',
      'Không mở được hộp thoại xác nhận.',
    );
  }

  await openDialog();
  await _tap(tester, _actionInside(dialogKey, cancelKey), 'Không tìm thấy nút hủy xóa.');
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectPresent(_byKey(itemKey), 'item', 'Hủy xóa nhưng item đã biến mất.');
  _expectGone(
    _byKey(dialogKey),
    'dialog',
    'Hủy xóa nhưng hộp thoại vẫn còn mở.',
    where: 'after_action',
  );

  await openDialog();
  await _tap(tester, _actionInside(dialogKey, confirmKey), 'Không tìm thấy nút xác nhận xóa.');
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectGone(
    _byKey(itemKey),
    'item',
    'Xác nhận xóa nhưng item vẫn còn.',
    where: 'after_action',
  );
}

Future<void> _checkCrudDetailFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  await _fillFields(tester, parameters, valuesName: 'seedValues');
  await _submitCurrentForm(tester, parameters, resultKeyName: 'seedResultKey');

  final itemKey = _requiredText(parameters, 'itemKey');
  final openKey = _text(parameters, 'openKey');
  await _revealKey(tester, itemKey);
  final openFinder = openKey.isEmpty
      ? _byKey(itemKey)
      : _actionInside(itemKey, openKey);
  await _tap(tester, openFinder, 'Không tìm thấy vùng mở chi tiết.');
  await _settle(tester);
  _failIfActionThrew(tester);
  final destinationKey = _requiredText(parameters, 'destinationKey');
  _expectPresent(
    _byKey(destinationKey),
    'screen',
    'Không mở được màn hình chi tiết.',
  );

  final detailKeys = _csv(parameters, 'detailTextKeys');
  final expectedValues = _csv(parameters, 'expectedDetailValues');
  if (detailKeys.length != expectedValues.length) {
    fail('detailTextKeys và expectedDetailValues phải có cùng số phần tử.');
  }
  for (var index = 0; index < detailKeys.length; index++) {
    final finder = _byKey(detailKeys[index]);
    _expectPresent(finder, 'text', 'Thiếu dữ liệu trên màn chi tiết.');
    final textWidget = tester.widget<Text>(finder);
    expect(
      textWidget.data ?? '',
      contains(_decodeInput(expectedValues[index])),
      reason: 'Màn chi tiết nhận sai dữ liệu.',
    );
  }
  final detailVisualKey = _text(parameters, 'detailVisualKey');
  if (detailVisualKey.isNotEmpty) {
    _expectPresent(
      _byKey(detailVisualKey),
      'image',
      'Thiếu nội dung hình ảnh ở chi tiết.',
    );
  }
}

void _expectItemsInColumns(
  WidgetTester tester,
  String firstKey,
  String secondKey, {
  required bool sameRow,
  required String where,
}) {
  final first = _byKey(firstKey);
  final second = _byKey(secondKey);
  _expectPresent(first, 'item', 'Thiếu item thứ nhất ở $where.');
  _expectPresent(second, 'item', 'Thiếu item thứ hai ở $where.');
  final a = tester.getRect(first);
  final b = tester.getRect(second);
  if (sameRow) {
    expect(
      (a.top - b.top).abs(),
      lessThanOrEqualTo(6),
      reason: '$where phải có hai cột.',
    );
    expect(
      (a.center.dx - b.center.dx).abs(),
      greaterThan(20),
      reason: '$where phải tách hai cột.',
    );
  } else {
    expect(
      (a.top - b.top).abs(),
      greaterThan(10),
      reason: '$where phải xếp một cột dọc.',
    );
  }
}

/// Kiểm tra quan hệ một/hai cột thay vì ép GridView hoặc pixel cụ thể.
Future<void> _checkResponsiveGridFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);
  tester.view.physicalSize = Size(
    _number(parameters, 'phoneWidth', 400),
    _number(parameters, 'phoneHeight', 900),
  );
  await _boot(tester);
  await _fillFields(tester, parameters, valuesName: 'firstValues');
  await _submitCurrentForm(tester, parameters, resultKeyName: 'firstItemKey');
  await _fillFields(tester, parameters, valuesName: 'secondValues');
  await _submitCurrentForm(tester, parameters, resultKeyName: 'secondItemKey');
  final firstKey = _requiredText(parameters, 'firstItemKey');
  final secondKey = _requiredText(parameters, 'secondItemKey');
  _expectNoLayoutError(tester, where: 'phone_portrait');
  _expectItemsInColumns(
    tester,
    firstKey,
    secondKey,
    sameRow: false,
    where: 'Phone dọc',
  );

  tester.view.physicalSize = Size(
    _number(parameters, 'landscapeWidth', 700),
    _number(parameters, 'landscapeHeight', 400),
  );
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'phone_landscape');
  _expectItemsInColumns(
    tester,
    firstKey,
    secondKey,
    sameRow: true,
    where: 'Phone ngang',
  );

  tester.view.physicalSize = Size(
    _number(parameters, 'tabletWidth', 800),
    _number(parameters, 'tabletHeight', 1100),
  );
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'tablet');
  _expectItemsInColumns(
    tester,
    firstKey,
    secondKey,
    sameRow: true,
    where: 'Tablet',
  );
}

Future<void> _checkStateReactiveFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final initialKey = _requiredText(parameters, 'initialKey');
  final actionKey = _requiredText(parameters, 'actionKey');
  final updatedKey = _requiredText(parameters, 'updatedKey');
  final absentKey = _text(parameters, 'absentKey');
  _expectPresent(
    _byKey(initialKey),
    'widget',
    'Thiếu state ban đầu: $initialKey',
  );
  await _tap(
    tester,
    _byKey(actionKey),
    'Không tìm thấy nút thao tác: $actionKey',
  );
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectPresent(
    _byKey(updatedKey),
    'widget',
    'State không cập nhật sau action $actionKey: $updatedKey',
    where: 'after_action',
  );
  if (absentKey.isNotEmpty) {
    _expectGone(
      _goneByKey(absentKey),
      'widget',
      'State cũ vẫn còn sau action $actionKey: $absentKey',
      where: 'after_action',
    );
  }
}

// ═══════════════════════════════════════════════════════════════
// Ch.7 RUNNERS — Widget bố cục và hiển thị nâng cao (3.6.0)
// ═══════════════════════════════════════════════════════════════

/// [SCROLL_DIRECTION] Kiểm chiều cuộn của ListView/GridView.
///
/// Chứng minh sinh viên dùng `scrollDirection: Axis.horizontal` đúng.
/// Parameter:
///   listKey  — Semantics(identifier:) của ListView/GridView
///   direction — 'horizontal' hoặc 'vertical' (mặc định 'vertical')
Future<void> _checkScrollDirection(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final wantedDir = _text(parameters, 'direction', 'vertical').toLowerCase();
  final listFinder = _byIdentifier(listKey);
  _expectPresent(listFinder, 'list', 'Không tìm thấy định danh list: $listKey');

  // Tìm Scrollable là con trực tiếp hoặc chính widget đó
  final scrollables = find.descendant(
    of: listFinder,
    matching: find.byType(Scrollable, skipOffstage: false),
    matchRoot: true,
  );
  if (scrollables.evaluate().isEmpty) {
    _observe('MISSING', subject: 'list', where: 'scroll_check');
    fail('Không tìm thấy Scrollable bên trong $listKey');
  }
  final scrollable = tester.widget<Scrollable>(scrollables.first);
  final actualAxis = scrollable.axisDirection;
  final isHorizontal =
      actualAxis == AxisDirection.left ||
      actualAxis == AxisDirection.right;
  final actualDir = isHorizontal ? 'horizontal' : 'vertical';
  if (actualDir != wantedDir) {
    _observe(
      'SCROLL_DIRECTION_MISMATCH',
      subject: 'list',
      seen: actualDir,
      where: 'scroll_check',
    );
  }
  expect(
    actualDir,
    wantedDir,
    reason: 'Chiều cuộn của $listKey phải là $wantedDir',
  );
}

/// [SCROLL_TO_END] Kiểm item lazy của ListView.builder xuất hiện sau khi cuộn.
///
/// Chứng minh `ListView.builder` chỉ render item khi cần (không overflow),
/// và `itemCount` đã được truyền đúng.
/// Parameters:
///   listKey       — Semantics(identifier:) của ListView/GridView
///   targetItemKey — Semantics(identifier:) của item ở cuối (ví dụ: 'list.item.9')
///   direction     — 'vertical'/'horizontal', mặc định 'vertical'
Future<void> _checkScrollToEnd(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final targetKey = _requiredText(parameters, 'targetItemKey');
  final direction = _text(parameters, 'direction', 'vertical').toLowerCase();
  _expectPresent(
    _byIdentifier(listKey),
    'list',
    'Không tìm thấy định danh list: $listKey',
  );
  // Cuộn đến khi item xuất hiện. Dùng chiều cuộn đúng.
  final offset =
      direction == 'horizontal'
          ? const Offset(-300, 0)
          : const Offset(0, -300);
  for (var attempt = 0; attempt < 30; attempt++) {
    if (_byIdentifier(targetKey).evaluate().isNotEmpty) break;
    final scrollables = find.byType(Scrollable, skipOffstage: false);
    if (scrollables.evaluate().isEmpty) break;
    // Cuộn scrollable cuối cùng (tránh trùng scrollable của AppBar)
    final count = scrollables.evaluate().length;
    await tester.drag(scrollables.at(count - 1), offset);
    await _settle(tester);
    _failIfActionThrew(tester);
  }
  _expectPresent(
    _byIdentifier(targetKey),
    'item',
    'Cuộn hết nhưng không tìm thấy item: $targetKey',
    where: 'after_scroll',
  );
  // Đảm bảo không có lỗi overflow sau khi cuộn
  _expectNoLayoutError(tester, where: 'after_scroll');
}

/// [STACK_LAYERS] Kiểm Stack có widget chồng nhau đúng thứ tự z-order.
///
/// Widget khai sau trong Stack nằm TRÊN widget khai trước (rect đè lên nhau).
/// Parameters:
///   stackKey      — Semantics(identifier:) của Stack widget
///   bottomLayerKey — Semantics(identifier:) của layer dưới
///   topLayerKey    — Semantics(identifier:) của layer trên (đè lên bottom)
Future<void> _checkStackLayers(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final stackKey = _requiredText(parameters, 'stackKey');
  final bottomKey = _requiredText(parameters, 'bottomLayerKey');
  final topKey = _requiredText(parameters, 'topLayerKey');

  final stackFinder = _byIdentifier(stackKey);
  _expectPresent(stackFinder, 'stack', 'Không tìm thấy định danh Stack: $stackKey');
  // Xác nhận đây là Stack widget — tìm theo định danh trả về lớp Semantics bọc ngoài
  // chứ không phải Stack/IndexedStack bên trong, nên phải đi xuống một nấc mới thấy.
  final khungStack = _duoiLopBoc(stackFinder, Stack);
  final khungIndexed = _duoiLopBoc(stackFinder, IndexedStack);
  if (khungStack.evaluate().isEmpty && khungIndexed.evaluate().isEmpty) {
    _observe('TYPE_MISMATCH', subject: 'stack');
    fail('$stackKey không phải Stack hoặc IndexedStack.');
  }

  final bottomFinder = _byIdentifier(bottomKey);
  final topFinder = _byIdentifier(topKey);
  _expectPresent(
    bottomFinder,
    'layer',
    'Không tìm thấy định danh layer dưới: $bottomKey',
  );
  _expectPresent(topFinder, 'layer', 'Không tìm thấy định danh layer trên: $topKey');

  // Kiểm hai layer có overlap (rect giao nhau): đây là dấu hiệu Stack đang xếp chồng
  final bottomRect = tester.getRect(bottomFinder);
  final topRect = tester.getRect(topFinder);
  final overlap = bottomRect.overlaps(topRect);
  if (!overlap) {
    _observe(
      'NUMBER_MISMATCH',
      subject: 'layer',
      dimension: 'vùng chồng lên nhau',
      expected: 1,
      found: 0,
    );
    fail(
      'Layer $topKey không đè lên $bottomKey — Stack chưa xếp chồng đúng cách.',
    );
  }
}

/// [INDEXED_STACK_SWITCH] Kiểm IndexedStack chỉ hiện 1 trang tại 1 thời điểm.
///
/// Đây là use case chính của IndexedStack (paging). Bấm tab → đúng trang hiện,
/// trang cũ trở nên không active (mất semantics). IndexedStack giữ tất cả con
/// trong tree nhưng chỉ vẽ con có index hiện tại.
/// Parameters:
///   stackKey — Semantics(identifier:) của IndexedStack
///   tabKeys  — CSV các định danh nút chuyển tab (ít nhất 2)
///   pageKeys — CSV các định danh widget nhận dạng mỗi trang (cùng thứ tự tabKeys)
Future<void> _checkIndexedStackSwitch(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final stackKey = _requiredText(parameters, 'stackKey');
  final tabKeys = _csv(parameters, 'tabKeys');
  final pageKeys = _csv(parameters, 'pageKeys');
  if (tabKeys.length < 2 || tabKeys.length != pageKeys.length) {
    fail('tabKeys và pageKeys phải có ít nhất 2 phần tử và bằng nhau.');
  }

  // Trang đầu tiên phải đang HIỆN. Trang KHÔNG được chọn của IndexedStack vẫn còn
  // trong cây nhưng không sinh nút ngữ nghĩa (không có Offstage để loại trừ riêng
  // như bản ValueKey cũ), nên tìm được bằng định danh nghĩa là đang hiển thị thật.
  _expectPresent(
    _byIdentifier(pageKeys[0]),
    'screen',
    'Trang đầu tiên (${pageKeys[0]}) phải hiển thị ngay khi boot.',
  );

  // Lần lượt bấm từng tab và kiểm trang tương ứng bật lên
  for (var i = 1; i < tabKeys.length; i++) {
    final tabFinder = _byIdentifier(tabKeys[i]);
    await _tap(
      tester,
      tabFinder,
      'Không tìm thấy nút tab: ${tabKeys[i]}',
      where: 'tab_switch',
    );
    await _settle(tester);
    _failIfActionThrew(tester);

    // Trang mới phải hiện
    _expectPresent(
      _byIdentifier(pageKeys[i]),
      'screen',
      'Sau khi bấm ${tabKeys[i]}, trang ${pageKeys[i]} phải hiển thị.',
      where: 'after_action',
    );
    // Trang trước phải KHÔNG còn tìm được bằng định danh: trang không được chọn
    // của IndexedStack không sinh nút ngữ nghĩa, nên "tìm thấy" ở đây tức là vẫn
    // đang hiển thị thật, không phải chỉ còn nằm offstage trong cây.
    final prevFinder = _byIdentifier(pageKeys[i - 1]);
    if (prevFinder.evaluate().isNotEmpty) {
      _observe(
        'STILL_PRESENT',
        subject: 'screen',
        expected: 0,
        found: prevFinder.evaluate().length,
        where: 'after_action',
      );
      fail(
        'Trang ${pageKeys[i - 1]} vẫn đang visible sau khi chuyển sang tab $i.',
      );
    }
  }

  // Bấm lại tab đầu — trang đầu quay lại
  await _tap(
    tester,
    _byIdentifier(tabKeys[0]),
    'Không tìm thấy nút tab đầu: ${tabKeys[0]}',
    where: 'tab_switch_back',
  );
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectPresent(
    _byIdentifier(pageKeys[0]),
    'screen',
    'Bấm lại tab đầu, trang ${pageKeys[0]} phải hiển thị lại.',
    where: 'after_action',
  );
}

/// [BOTTOM_SHEET_FLOW] Kiểm Modal BottomSheet bật lên và đóng lại đúng cách.
///
/// Chứng minh `showModalBottomSheet` hoạt động: sheet xuất hiện sau khi bấm
/// nút, và đóng được khi bấm nút đóng hoặc bấm ra ngoài.
/// Parameters:
///   triggerKey — Semantics(identifier:) nút bật sheet
///   sheetKey   — Semantics(identifier:) container bên trong sheet
///   closeKey   — (tuỳ chọn) Semantics(identifier:) nút đóng sheet trong sheet; nếu
///                bỏ trống, engine sẽ drag sheet xuống để đóng
Future<void> _checkBottomSheetFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final triggerKey = _requiredText(parameters, 'triggerKey');
  final sheetKey = _requiredText(parameters, 'sheetKey');
  final closeKey = _text(parameters, 'closeKey');

  // Sheet chưa được hiện khi mới boot
  expect(
    _byIdentifier(sheetKey),
    findsNothing,
    reason: 'Sheet $sheetKey không nên hiển thị trước khi bấm trigger.',
  );

  // Bấm trigger → sheet xuất hiện
  await _tap(
    tester,
    _byIdentifier(triggerKey),
    'Không tìm thấy nút bật sheet: $triggerKey',
  );
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectPresent(
    _byIdentifier(sheetKey),
    'sheet',
    'Bấm trigger nhưng sheet $sheetKey không xuất hiện.',
    where: 'after_open',
  );

  // Đóng sheet
  if (closeKey.isNotEmpty) {
    // Đóng bằng nút trong sheet
    await _tap(
      tester,
      _byIdentifier(closeKey),
      'Không tìm thấy nút đóng sheet: $closeKey',
      where: 'after_open',
    );
  } else {
    // Drag sheet xuống để đóng
    final sheetFinder = _byIdentifier(sheetKey);
    await tester.drag(sheetFinder, const Offset(0, 400));
  }
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectGone(
    _goneByIdentifier(sheetKey),
    'sheet',
    'Sheet $sheetKey vẫn còn hiển thị sau khi đóng.',
    where: 'after_close',
  );

  // Bật lại lần 2 để kiểm không bị trạng thái treo
  await _tap(
    tester,
    _byIdentifier(triggerKey),
    'Không mở được sheet lần 2: $triggerKey',
    where: 'second_open',
  );
  await _settle(tester);
  _failIfActionThrew(tester);
  _expectPresent(
    _byIdentifier(sheetKey),
    'sheet',
    'Sheet $sheetKey không mở được lần 2.',
    where: 'second_open',
  );
}

/// [TABLE_ROWS] Kiểm Table widget có đúng số hàng và cell mẫu.
///
/// Chứng minh sinh viên dùng `Table`/`TableRow`/`TableCell` chứ không nhầm
/// sang ListView. Đếm số TableRow con trực tiếp của Table.
/// Parameters:
///   tableKey         — Semantics(identifier:) của Table widget
///   expectedRowCount — số hàng kỳ vọng (bao gồm cả header nếu có)
///   headerKeys       — (tuỳ chọn) CSV định danh các cell header
///   sampleCellKeys   — (tuỳ chọn) CSV định danh các cell dữ liệu mẫu
Future<void> _checkTableRows(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final tableKey = _requiredText(parameters, 'tableKey');
  final tableFinder = _byIdentifier(tableKey);
  _expectPresent(tableFinder, 'table', 'Không tìm thấy định danh Table: $tableKey');

  // Xác nhận là Table widget — tìm theo định danh trả về lớp Semantics bọc ngoài
  // chứ không phải Table bên trong, nên phải đi xuống một nấc mới thấy đúng widget.
  final khungBang = _duoiLopBoc(tableFinder, Table);
  if (khungBang.evaluate().isEmpty) {
    _observe('TYPE_MISMATCH', subject: 'table');
    fail('$tableKey không phải Table widget — dùng Table() thay vì ListView.');
  }
  final tableWidget = tester.widget<Table>(khungBang.first);

  // Đếm số hàng (TableRow) của bảng này
  final actualRows = tableWidget.children.length;
  final wantedRows = _number(parameters, 'expectedRowCount', double.nan).toInt();
  if (!wantedRows.isNaN && actualRows != wantedRows) {
    _observe(
      'COUNT_MISMATCH',
      subject: 'item',
      expected: wantedRows,
      found: actualRows,
    );
    expect(
      actualRows,
      wantedRows,
      reason: 'Bảng $tableKey có $actualRows hàng, mong đợi $wantedRows hàng.',
    );
  }

  // Kiểm header keys nếu có
  for (final key in _csv(parameters, 'headerKeys')) {
    _expectPresent(
      _byIdentifier(key),
      'text',
      'Thiếu định danh header cell: $key',
    );
  }
  // Kiểm sample data cell keys nếu có
  for (final key in _csv(parameters, 'sampleCellKeys')) {
    _expectPresent(
      _byIdentifier(key),
      'item',
      'Thiếu định danh data cell: $key',
    );
  }

  // Kiểm tra nội dung từng cell nếu có expectedCells (format: "cell00,cell01|cell10,cell11")
  final expectedCellsRaw = _text(parameters, 'expectedCells');
  if (expectedCellsRaw.isNotEmpty) {
    final rows = expectedCellsRaw.split('|');
    for (var r = 0; r < rows.length; r++) {
      if (r >= tableWidget.children.length) {
        fail('Số dòng thực tế ($actualRows) ít hơn số dòng mong đợi (${rows.length})');
      }
      final cols = rows[r].split(',').map((c) => c.trim()).toList();
      final tableRow = tableWidget.children[r];
      for (var c = 0; c < cols.length; c++) {
        if (c >= tableRow.children.length) {
          fail('Dòng $r có số cột thực tế (${tableRow.children.length}) ít hơn số cột mong đợi (${cols.length})');
        }
        final cellWidget = tableRow.children[c];
        final cellFinder = find.byWidget(cellWidget);
        final expectedText = _decodeInput(cols[c]);
        if (expectedText.isNotEmpty) {
          // Tìm Text widget là con của cellWidget
          final textFinder = find.descendant(
            of: cellFinder,
            matching: find.byType(Text, skipOffstage: false),
            matchRoot: true,
          );
          if (textFinder.evaluate().isEmpty) {
            fail('Không tìm thấy text widget tại dòng $r, cột $c');
          }
          final textWidget = tester.widget<Text>(textFinder.first);
          final actualText = textWidget.data ?? textWidget.textSpan?.toPlainText() ?? '';
          if (!actualText.contains(expectedText)) {
            _observe('TEXT_MISMATCH', subject: 'text', seen: actualText, where: 'table_cell');
            expect(actualText, contains(expectedText), reason: 'Nội dung ô ($r, $c) không khớp');
          }
        }
      }
    }
  }
}

/// [SLIVER_SCROLL_COLLAPSE] Kiểm SliverAppBar + CustomScrollView không lỗi.
///
/// Xác nhận:
/// 1. CustomScrollView với SliverAppBar boot không lỗi
/// 2. Cuộn lên không gây lỗi "RenderViewport expected a child of type RenderSliver"
/// 3. SliverAppBar thu lại (collapsed) sau khi cuộn (nếu collapseOnScroll = true)
/// Parameters:
///   scrollViewKey  — Semantics(identifier:) của CustomScrollView
///   appBarKey      — Semantics(identifier:) của SliverAppBar (hoặc widget tiêu đề)
///   listKey        — Semantics(identifier:) của SliverList/SliverGrid bên trong
///   collapseOnScroll — true nếu kiểm thu nhỏ (mặc định false — chỉ kiểm boot + scroll an toàn)
/// Đo "chiều cao thật" hiện tại của SliverAppBar (hay widget tiêu đề bên trong
/// flexibleSpace) tại thời điểm gọi — dùng cho SLIVER_SCROLL_COLLAPSE.
///
/// `tester.getRect` trên MỘT widget bên trong `flexibleSpace` không dùng được để
/// biết sliver đã co lại hay chưa: `RenderSliverPersistentHeader` luôn layout con
/// của nó ở đúng `maxExtent`, bất kể đang collapse hay không — phần "thu nhỏ" chỉ
/// là dịch vị trí paint (bị clip bớt), không phải resize. Đại lượng phản ánh đúng
/// độ co giãn là `geometry.paintExtent` của CHÍNH `RenderSliverPersistentHeader`.
///
/// Tìm XUỐNG cây con trước rồi mới leo lên: đích khai bằng định danh trỏ vào lớp
/// SliverSemantics bọc NGOÀI SliverAppBar, leo lên là đi xa khỏi header và không
/// bao giờ gặp — khác bản ValueKey cũ (đích trỏ thẳng vào bên trong, phải leo lên
/// mới ra RenderSliverPersistentHeader). Trả về null nếu không tìm thấy theo cả
/// hai hướng (fallback về getRect ở nơi gọi) — ví dụ bài không dùng SliverAppBar mà
/// tự dựng sliver khác.
double? _sliverCollapsedExtent(WidgetTester tester, Finder finder) {
  if (finder.evaluate().isEmpty) return null;
  final RenderObject goc = tester.renderObject(finder);
  RenderSliverPersistentHeader? tim;
  void diXuong(RenderObject r) {
    if (tim != null) return;
    if (r is RenderSliverPersistentHeader) {
      tim = r;
      return;
    }
    r.visitChildren(diXuong);
  }

  diXuong(goc);
  if (tim != null) return tim!.geometry?.paintExtent;
  RenderObject? node = goc;
  while (node != null) {
    if (node is RenderSliverPersistentHeader) return node.geometry?.paintExtent;
    node = node.parent;
  }
  return null;
}

Future<void> _checkSliverScrollCollapse(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  _expectNoLayoutError(tester, where: 'boot');

  final scrollViewKey = _requiredText(parameters, 'scrollViewKey');
  final appBarKey = _requiredText(parameters, 'appBarKey');

  // Xác nhận CustomScrollView và tiêu đề tồn tại
  _expectPresent(
    _byIdentifier(scrollViewKey),
    'list',
    'Không tìm thấy định danh CustomScrollView: $scrollViewKey',
  );
  _expectPresent(
    _byIdentifier(appBarKey),
    'widget',
    'Không tìm thấy định danh SliverAppBar: $appBarKey',
  );

  // Đo chiều cao AppBar trước khi cuộn. Dùng _sliverCollapsedExtent thay vì
  // tester.getRect trần: RenderSliverPersistentHeader (nền của SliverAppBar) LUÔN
  // layout widget con ở kích thước maxExtent cố định — thu nhỏ khi cuộn chỉ là dịch
  // vị trí paint, không đổi kích thước layout. getRect trên widget bên trong
  // flexibleSpace do đó luôn trả về cùng một chiều cao dù bài làm đúng, khiến phép
  // so sánh dưới đây luôn fail oan. paintExtent của chính sliver mới phản ánh đúng.
  final heightBefore = _sliverCollapsedExtent(tester, _byIdentifier(appBarKey)) ??
      tester.getRect(_byIdentifier(appBarKey)).height;

  // Cuộn lên nhiều lần để kích hoạt collapse
  final scrollables = find.byType(Scrollable, skipOffstage: false);
  for (var i = 0; i < 5; i++) {
    if (scrollables.evaluate().isEmpty) break;
    await tester.drag(scrollables.first, const Offset(0, -200));
    await _settle(tester);
    _failIfActionThrew(tester);
    _expectNoLayoutError(tester, where: 'after_scroll_$i');
  }

  // Kiểm thu nhỏ nếu yêu cầu
  final collapseExpected = _bool(parameters, 'collapseOnScroll', false);
  if (collapseExpected && _byIdentifier(appBarKey).evaluate().isNotEmpty) {
    final heightAfter = _sliverCollapsedExtent(tester, _byIdentifier(appBarKey)) ??
        tester.getRect(_byIdentifier(appBarKey)).height;
    if (heightAfter >= heightBefore) {
      _observe(
        'NUMBER_MISMATCH',
        subject: 'widget',
        dimension: 'chiều cao AppBar sau khi cuộn',
        expected: heightBefore - 1,
        found: heightAfter,
        unit: 'px',
      );
      fail(
        'SliverAppBar không thu lại khi cuộn: chiều cao trước=$heightBefore, sau=$heightAfter',
      );
    }
  }

  // Kiểm SliverList/SliverGrid có trong CustomScrollView không
  final listKey = _text(parameters, 'listKey');
  if (listKey.isNotEmpty) {
    _expectPresent(
      _byIdentifier(listKey),
      'list',
      'Không tìm thấy định danh SliverList/SliverGrid: $listKey',
    );
  }
}

/// [EXPANDED_WIDGET] Kiểm tra một widget có được bọc trong Expanded và nằm trong Row/Column/Flex không.
///
/// Parameters:
///   childKey  — Semantics(identifier:) của widget con (ví dụ: 'list.container')
///   flex      — (tuỳ chọn) giá trị flex mong đợi (mặc định 1)
Future<void> _checkExpandedWidget(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final childKey = _requiredText(parameters, 'childKey');
  final childFinder = _byIdentifier(childKey);
  _expectPresent(childFinder, 'widget', 'Không tìm thấy định danh widget con: $childKey');

  // Tìm Expanded là tổ tiên của child
  final expandedFinder = find.ancestor(
    of: childFinder,
    matching: find.byType(Expanded, skipOffstage: false),
  );
  if (expandedFinder.evaluate().isEmpty) {
    _observe('TYPE_MISMATCH', subject: 'widget', where: 'expanded_check');
    fail('Widget $childKey không được bọc trong Expanded.');
  }

  final expandedWidget = tester.widget<Expanded>(expandedFinder.first);
  final expectedFlex = _number(parameters, 'flex', 1).toInt();
  if (expandedWidget.flex != expectedFlex) {
    fail('Expanded bọc $childKey có flex=${expandedWidget.flex}, mong đợi $expectedFlex');
  }

  // Expanded phải nằm trong Row, Column hoặc Flex
  final parentFinder = find.ancestor(
    of: expandedFinder.first,
    matching: find.byWidgetPredicate(
      (widget) => widget is Row || widget is Column || widget is Flex,
      skipOffstage: false,
    ),
  );
  if (parentFinder.evaluate().isEmpty) {
    fail('Expanded bọc $childKey phải nằm trong Row, Column hoặc Flex.');
  }
}

/// KÊNH QUAN SÁT CÓ CẤU TRÚC (SPEC mục 5.3) — nguồn của `actual` trong result.json.
///
/// Runner là nơi DUY NHẤT biết chính xác nó kiểm gì và thấy gì trước khi assertion nổ. Nó phát
/// ra dữ liệu MÁY ĐỌC ở đây; toàn bộ việc diễn ra tiếng Việt do **backend** làm.
///
/// Vì sao render ở backend chứ không ở đây: file này bị chép ĐÓNG BĂNG vào thư mục testcase của
/// từng đề lúc publish. Câu chữ để trong này thì sửa một chữ cũng phải nâng engine cho mọi đề đã
/// publish; để ở backend thì sửa một lần là đề cũ lẫn đề mới đều hưởng.
///
/// LUẬT NỘI DUNG (SPEC mục 5.4) — payload chỉ được chứa:
///  - `kind`: loại quan sát (bảng đóng, xem `_ObsKind` phía backend);
///  - `subject`: loại thành phần theo cách SINH VIÊN hiểu (`field`, `button`, `list`, `item`,
///    `dialog`, `screen`, `error`, `text`) — **KHÔNG BAO GIỜ** là semantic key nội bộ;
///  - số đếm / giá trị QUAN SÁT ĐƯỢC (`expected`, `found`, `seen`, `unit`, `dimension`);
///  - `where`: bối cảnh kiểm (`portrait`, `landscape`, `after_action`…).
///
/// Tuyệt đối không đưa `ValueKey`, tên hàm test hay số thứ tự test vào đây.
const String kObservationMarker = '###GRADER_OBS###';

void _observe(
  String kind, {
  String? subject,
  num? expected,
  num? found,
  String? seen,
  String? unit,
  String? dimension,
  String? where,
}) {
  final payload = <String, dynamic>{'kind': kind};
  void put(String key, Object? value) {
    if (value != null) payload[key] = value;
  }

  put('subject', subject);
  put('expected', expected);
  put('found', found);
  // Chữ NGƯỜI DÙNG THẤY trên màn hình được phép in (SPEC 5.4); cắt ngắn cho gọn JSON.
  put(
    'seen',
    seen == null
        ? null
        : (seen.length > 80 ? '${seen.substring(0, 80)}…' : seen),
  );
  put('unit', unit);
  put('dimension', dimension);
  put('where', where);
  print('$kObservationMarker${jsonEncode(payload)}');
}

/// Loại thành phần theo cách SINH VIÊN hiểu, suy từ `targetType` mà template đã khai.
///
/// Cố ý KHÔNG suy từ semantic key: key là định danh nội bộ của hệ thống chấm
/// (`field.name`, `action.save`), sinh viên không kiểm chứng được nên không được lộ ra.
String _subject(
  Map<String, dynamic> parameters, [
  String fallback = 'widget',
]) => _subjectOfType(_text(parameters, 'targetType'), fallback);

/// Bảng ĐÓNG các giá trị `subject` — phải khớp bảng `SUBJECT` của `TestObservationRenderer`.
const Set<String> kSubjects = <String>{
  'button',
  'text',
  'input',
  'field',
  'list',
  'item',
  'dialog',
  'screen',
  'image',
  'icon',
  'checkbox',
  // Ch.7 — widget bố cục nâng cao
  'card',
  'table',
  'sheet',
  'layer',
  'stack',
};

String _subjectOfType(String type, [String fallback = 'widget']) {
  final normalized = type.toLowerCase().trim();
  return kSubjects.contains(normalized) ? normalized : fallback;
}

/// Bọc phép kiểm "PHẢI CÓ": phát quan sát trước, rồi assert Y NGUYÊN như cũ.
///
/// Quan sát chỉ là hiệu ứng lề — assertion không đổi một chữ nên **điểm không thể lệch**. Đó là
/// cổng bắt buộc của P5 và là lý do chọn cách bọc thay vì viết lại từng runner.
void _expectPresent(
  Finder finder,
  String subject,
  String reason, {
  String? where,
}) {
  final found = finder.evaluate().length;
  if (found != 1) {
    _observe(
      'MISSING',
      subject: subject,
      expected: 1,
      found: found,
      where: where,
    );
  }
  expect(finder, findsOneWidget, reason: reason);
}

/// Chạm vào một thành phần — KIỂM SỰ TỒN TẠI TRƯỚC, luôn luôn.
///
/// `tester.tap` vào finder rỗng ném lỗi THÔ của flutter_test: không đi qua kênh quan sát, nên
/// `observation` là null và sinh viên nhận nguyên khối log tiếng Anh thay vì câu nói rõ thiếu cái
/// gì. Trước A2b có **tám** chỗ chạm không kiểm gì, và đó là lỗ hổng lớn nhất còn lại của kênh
/// quan sát — mọi runner có thao tác đều đi qua nó.
///
/// ĐIỂM KHÔNG ĐỔI: `tester.tap` cũng đòi đúng một widget, nên phép kiểm này không nghiêm hơn —
/// chỉ đổi cách BÁO khi thiếu. Chủ ngữ luôn là `button`: thứ bộ chấm chạm vào bao giờ cũng là
/// một thành phần bấm được.
Future<void> _tap(
  WidgetTester tester,
  Finder finder,
  String reason, {
  String? where,
}) async {
  _expectPresent(finder, 'button', reason, where: where);
  _stage('STUDENT_UI_ACTION');
  await tester.tap(finder);
  _stage('TESTCASE_ASSERTION');
}

/// Sau MỖI thao tác: ứng dụng có ném lỗi không? Nếu có thì đó là **nguyên nhân gốc**, phải nói ra
/// trước khi phần khẳng định của runner kịp phát hiện *triệu chứng*.
///
/// Vì sao bắt buộc: `flutter_test` KHÔNG dừng thân test khi handler của bài ném lỗi — nó bắt lấy,
/// test chạy tiếp, rồi assertion của runner hỏng vì hệ quả. Đo được ở bài `broken-action`: app ném
/// `RangeError` lúc bấm xoá, nhưng báo cáo nói *"không thấy hộp thoại nào"* và `error_code` là
/// `WIDGET_NOT_FOUND`. Sinh viên đi tìm widget thiếu, trong khi lỗi là truy cập ngoài phạm vi.
/// Đó là **chẩn đoán sai lệch** — tệ hơn chẩn đoán xấu.
///
/// KHÔNG in đối tượng lỗi vào quan sát: tên class là định danh nội bộ của Dart (luật C3). `kind`
/// này cố ý **không mang `error_code`** để `error_code` giữ giá trị của classifier — chỉ nó bóc
/// được loại ngoại lệ (`RANGE_ERROR`, `NULL_ERROR`, `STATE_ERROR`…), độ mịn mà `kind` không mang
/// được. Hai nguồn bổ sung nhau, không cạnh tranh.
///
/// ĐIỂM KHÔNG ĐỔI: lỗi chưa lấy đi vẫn làm `flutter_test` đánh hỏng test lúc kết thúc, nên phán
/// quyết y nguyên — chỉ đổi chỗ hỏng và cách BÁO.
void _failIfActionThrew(WidgetTester tester, {String where = 'after_action'}) {
  final exception = _takeRelevantException(tester);
  if (exception == null) return;
  _observe('ACTION_FAILED', where: where);
  fail('Ứng dụng ném lỗi khi thực hiện thao tác: $exception');
}

/// Bọc phép kiểm "PHẢI BIẾN MẤT". Luôn dùng [_goneByKey] cho finder, xem lý do ở đó.
/// Resource image decoding is independent from CRUD and validation behavior.
/// A dedicated image testcase must inspect Image/ImageProvider explicitly.
Object? _takeRelevantException(WidgetTester tester) {
  for (var index = 0; index < 20; index++) {
    final exception = tester.takeException();
    if (exception == null) return null;
    final message = exception.toString().toLowerCase();
    final unsupportedImageCodec =
        message.contains('codec failed to produce an image') ||
        message.contains('invalid image data') ||
        message.contains('imagecodec exception');
    if (!unsupportedImageCodec) return exception;
  }
  return null;
}

void _expectGone(
  Finder finder,
  String subject,
  String reason, {
  String? where,
}) {
  final found = finder.evaluate().length;
  if (found != 0) {
    _observe(
      'STILL_PRESENT',
      subject: subject,
      expected: 0,
      found: found,
      where: where,
    );
  }
  expect(finder, findsNothing, reason: reason);
}

/// Dấu hiệu MÁY ĐỌC cho `grader.dart`: khung hình đầu tiên của ứng dụng không dựng được,
/// nên runner **chưa chạy tới phần khẳng định của chính nó** ⇒ `status: not_run`.
///
/// Đây là *cơ chế phụ thuộc thật lúc chạy* mà SPEC mục 4.1 đòi hỏi, KHÔNG phải heuristic:
/// mọi runner của engine chung đều gọi `_boot()` trước khi kiểm phần của mình, nên lỗi xảy ra
/// BÊN TRONG `_boot()` là bằng chứng chắc chắn — khác hẳn kiểu suy đoán "test tầng cao hỏng
/// sau test tầng thấp thì coi là bị chặn".
///
/// PHẢI khớp hằng cùng tên trong `grader.dart` (hai chương trình Dart riêng, không import nhau).
const String kBootFailedMarker = '###GRADER_BOOT_FAILED###';

int _groupDepth = 0;
bool _groupBooted = false;

Future<void> _boot(WidgetTester tester) async {
  // Các testcase con của GROUP là các bước của cùng một kịch bản. Khởi động lại main()
  // ở mỗi bước sẽ làm mất trạng thái form/navigation và khiến nhóm không thể biểu diễn
  // Edit, Delete hoặc Detail. Ngoài GROUP, mỗi testcase vẫn khởi động độc lập như cũ.
  if (_groupDepth > 0 && _groupBooted) {
    await tester.pump();
    return;
  }
  // SQLite FFI là I/O thật; gọi main trong FakeAsync khiến Future loadUsers không
  // được hoàn tất, còn pumpAndSettle thì chờ vô hạn vì CircularProgressIndicator.
  _stage('STUDENT_APP_BOOT');
  await tester.runAsync(() async {
    student_app.main();
    // Một số bài tự gán lại databaseFactoryFfi (có isolate) trong main(). Gán lại ngay
    // trước pump đầu tiên để Provider/Repository mở DB bằng backend noIsolate ổn định.
    sqfliteFfiInit();
    databaseFactory = createDatabaseFactoryFfi(noIsolate: true);
    await tester.pump();
    await Future<void>.delayed(const Duration(milliseconds: 500));
  });
  _stage('TESTCASE_ASSERTION');
  await tester.pump();
  final exception = _takeRelevantException(tester);
  // In TRƯỚC khi assertion ném: sau đó không còn cơ hội in nữa.
  if (exception != null) {
    print(kBootFailedMarker);
    _observe('BOOT_FAILED');
  }
  // Giữ nguyên dạng `expect(..., isNull)`: backend đã có luật đọc `Actual:` là đối tượng lỗi
  // Dart và đổi thành câu tiếng Việt, đừng tự nhét exception vào thông điệp fail.
  expect(exception, isNull);
  if (_groupDepth > 0) _groupBooted = true;
}

Future<void> _settle(WidgetTester tester) async {
  // Phải chờ HAI loại thời gian khác nhau, thiếu một loại là chấm sai điểm:
  //
  //  - `runAsync`: I/O THẬT (SQLite FFI) chỉ chạy được ngoài đồng hồ ảo của test.
  //  - ĐẨY đồng hồ ảo: hoạt ảnh chuyển route chỉ chạy khi đồng hồ ảo tiến. `pump()` trần
  //    không đẩy đồng hồ, nên hoạt ảnh không bao giờ kết thúc: lớp phủ chuyển cảnh còn nguyên,
  //    `tester.tap` vào nút trên AppBar màn hình mới bị chắn và chỉ ghi một dòng cảnh báo —
  //    sinh viên làm đúng vẫn mất điểm.
  //
  _stage('STUDENT_ASYNC_SETTLE');
  await tester.runAsync(() async {
    await Future<void>.delayed(const Duration(milliseconds: 300));
  });

  try {
    // `pumpAndSettle` CÓ GIỚI HẠN: đúng cơ chế cần (chạy tới khi không còn frame nào được
    // xếp lịch), nhưng phải chặn timeout kẻo bài có loading/animation không dứt treo cả
    // batch chấm. Vài nhịp `pump(Duration)` cố định là KHÔNG đủ: hoạt ảnh pop route còn
    // ticker đang chạy, màn hình cũ chưa bị gỡ khỏi cây widget.
    await tester.pumpAndSettle(
      const Duration(milliseconds: 50),
      EnginePhase.sendSemanticsUpdate,
      const Duration(seconds: 2),
    );
  } catch (_) {
    // Không lặng được trong hạn: vẫn đẩy đồng hồ một nhịp để không đứng im tại t=0.
    await tester.pump(const Duration(milliseconds: 350));
  }
  _stage('TESTCASE_ASSERTION');
}

Future<void> _responsive(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final portrait = Size(
    _number(parameters, 'portraitWidth', 390),
    _number(parameters, 'portraitHeight', 844),
  );
  final landscape = Size(
    _number(parameters, 'landscapeWidth', 1024),
    _number(parameters, 'landscapeHeight', 768),
  );

  tester.view.physicalSize = portrait;
  await _boot(tester);
  _expectNoLayoutError(tester, where: 'portrait');

  tester.view.physicalSize = landscape;
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'landscape');
}

/// Không có lỗi bố cục ở kích thước đang kiểm.
///
/// Tràn khung là điều QUAN SÁT ĐƯỢC: sinh viên mở máy ở đúng kích thước đó là thấy vệt vàng.
/// Nên chỉ cần nói *tràn ở kích thước nào*, không cần dán log tiếng Anh.
void _expectNoLayoutError(WidgetTester tester, {required String where}) {
  final exception = _takeRelevantException(tester);
  if (exception != null) {
    final text = exception.toString().toLowerCase();
    _observe(
      text.contains('overflow') ? 'OVERFLOW' : 'LAYOUT_ERROR',
      where: where,
    );
  }
  expect(exception, isNull);
}

Future<void> _responsiveTarget(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final portrait = Size(
    _number(parameters, 'portraitWidth', 390),
    _number(parameters, 'portraitHeight', 844),
  );
  final landscape = Size(
    _number(parameters, 'landscapeWidth', 1024),
    _number(parameters, 'landscapeHeight', 768),
  );
  final targetKey = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');

  tester.view.physicalSize = portrait;
  await _boot(tester);
  _expectNoLayoutError(tester, where: 'portrait');
  final portraitFinder = _byKey(targetKey);
  _expectPresent(
    portraitFinder,
    _subject(parameters),
    'Thiếu target ở portrait: $targetKey',
    where: 'portrait',
  );
  _assertTargetType(tester, portraitFinder, targetKey, targetType);

  tester.view.physicalSize = landscape;
  await _settle(tester);
  _expectNoLayoutError(tester, where: 'landscape');
  final landscapeFinder = _byKey(targetKey);
  _expectPresent(
    landscapeFinder,
    _subject(parameters),
    'Thiếu target ở landscape: $targetKey',
    where: 'landscape',
  );
  _assertTargetType(tester, landscapeFinder, targetKey, targetType);
}

Future<void> _checkWidgetDimension(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(
    finder,
    _subject(parameters),
    'Không tìm thấy target key: $key',
  );
  _assertTargetType(tester, finder, key, targetType);

  final dimension = _text(parameters, 'dimension').toLowerCase();
  final expected = _number(parameters, 'expected', double.nan);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final size = tester.getSize(finder);
  final actual = dimension == 'width' ? size.width : size.height;
  _assertNumber(
    actual,
    expected,
    tolerance,
    _text(parameters, 'comparison'),
    '$key ($targetType) có $dimension=$actual, mong đợi $expected',
    dimension: dimension == 'width' ? 'chiều rộng' : 'chiều cao',
  );
}

Future<void> _checkWidgetTypeVisible(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(
    finder,
    _subject(parameters),
    'Không tìm thấy target key: $key',
  );
  _assertTargetType(tester, finder, key, targetType);
}

Future<void> _checkWidgetTextContent(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _text(parameters, 'targetType', 'text');
  final finder = _byKey(key);
  _expectPresent(finder, 'text', 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);
  final widget = tester.widget<Text>(finder);
  final actual = widget.data ?? widget.textSpan?.toPlainText() ?? '';
  final expected = _requiredText(parameters, 'expectedText');
  final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
  final ok = matchMode == 'contains'
      ? actual.contains(expected)
      : actual == expected;
  // Chữ SINH VIÊN đang hiển thị được phép in nguyên văn (SPEC 5.4): đó là thứ em ấy tự
  // nhìn thấy trên máy mình, không phải khoá nội bộ hay log của hệ thống chấm.
  if (!ok) _observe('TEXT_MISMATCH', subject: 'text', seen: actual);
  if (matchMode == 'contains') {
    expect(
      actual,
      contains(expected),
      reason: 'Nội dung $key không chứa expectedText',
    );
  } else {
    expect(actual, expected, reason: 'Nội dung $key không đúng');
  }
}

Future<void> _checkWidgetEnabled(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(
    finder,
    _subject(parameters, 'button'),
    'Không tìm thấy target key: $key',
  );
  _assertTargetType(tester, finder, key, targetType);
  final enabled = _enabledState(tester.widget<Widget>(finder));
  if (enabled == null) {
    fail('Không đọc được trạng thái enabled của $key (${targetType}).');
  }
  final wantEnabled = _bool(parameters, 'expectedEnabled', true);
  if (enabled != wantEnabled) {
    _observe(
      'ENABLED_MISMATCH',
      subject: _subject(parameters, 'button'),
      seen: enabled == true ? 'enabled' : 'disabled',
    );
  }
  expect(
    enabled,
    wantEnabled,
    reason: 'Trạng thái enabled của $key không đúng',
  );
}

Future<void> _checkFormValidateFields(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final values = _csv(parameters, 'invalidValues');
  final errors = _csv(parameters, 'errorKeys');
  if (fields.isEmpty ||
      fields.length != values.length ||
      fields.length != errors.length) {
    fail('fieldKeys, invalidValues và errorKeys phải có cùng số phần tử.');
  }
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    _expectPresent(fieldFinder, 'field', 'Thiếu field key: ${fields[i]}');
    await _setFormControlValue(
      tester,
      fields[i],
      fieldFinder,
      _decodeInput(values[i]),
    );
  }
  final submitKey = _requiredText(parameters, 'submitKey');
  await _tap(tester, _byKey(submitKey), 'Không tìm thấy nút lưu: $submitKey');
  await _settle(tester);
  _failIfActionThrew(tester);
  for (var i = 0; i < errors.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    final errorKey = errors[i];
    // Ưu tiên ValueKey rõ ràng; nếu dùng contract text thì chỉ khớp trong đúng field.
    // Không dùng _byKey(errorKey) ở đây vì fallback toàn cây có thể lấy nhầm lỗi field khác.
    final exactError = find.byKey(
      ValueKey<String>(errorKey),
      skipOffstage: false,
    );
    if (exactError.evaluate().isNotEmpty) {
      _expectPresent(
        exactError,
        'error',
        'Thiếu lỗi validation key: $errorKey',
        where: 'after_action',
      );
      continue;
    }
    final rule = _contractRule(errorKey);
    final scopedContractError = rule == null
        ? null
        : _contractFinderWithin(rule, fieldFinder);
    if (scopedContractError != null &&
        scopedContractError.evaluate().isNotEmpty) {
      _expectPresent(
        scopedContractError,
        'error',
        'Thiếu lỗi validation trong field ${fields[i]}',
        where: 'after_action',
      );
      continue;
    }
    if (_fieldValidationError(fieldFinder)) continue;
    _expectPresent(
      _notFound(),
      'error',
      'Thiếu lỗi validation key: $errorKey (hoặc errorText trong field ${fields[i]})',
      where: 'after_action',
    );
  }
}

Future<void> _checkListItemCount(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final listKey = _requiredText(parameters, 'listKey');
  final listFinder = _byKey(listKey);
  _expectPresent(listFinder, 'list', 'Không tìm thấy list key: $listKey');
  final itemKeys = _csv(parameters, 'itemKeys');
  if (itemKeys.isEmpty) fail('Thiếu itemKeys khi kiểm tra list.');
  var count = 0;
  for (final itemKey in itemKeys) {
    count += find
        .descendant(of: listFinder, matching: _byKey(itemKey))
        .evaluate()
        .length;
  }
  final wanted = _number(parameters, 'expectedCount', double.nan).toInt();
  // Số đếm là quan sát được và vô hại: sinh viên tự đếm lại trên màn hình của mình.
  if (count != wanted) {
    _observe('COUNT_MISMATCH', subject: 'item', expected: wanted, found: count);
  }
  expect(count, wanted, reason: 'Số item trong $listKey không đúng');
}

Future<void> _checkFormPrefill(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final editKey = _requiredText(parameters, 'editKey');
  await _tap(tester, _byKey(editKey), 'Không tìm thấy nút sửa: $editKey');
  await _settle(tester);
  _failIfActionThrew(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final expectedValues = _csv(parameters, 'expectedValues');
  if (fields.isEmpty || fields.length != expectedValues.length) {
    fail('fieldKeys và expectedValues phải có cùng số phần tử.');
  }
  for (var i = 0; i < fields.length; i++) {
    final fieldFinder = _byKey(fields[i]);
    _expectPresent(fieldFinder, 'field', 'Thiếu field key: ${fields[i]}');
    final editable = find.descendant(
      of: fieldFinder,
      matching: find.byType(EditableText, skipOffstage: false),
      matchRoot: true,
    );
    if (editable.evaluate().isEmpty) continue;
    final filled = tester.widget<EditableText>(editable.first).controller.text;
    // Giá trị đang nằm trong ô nhập là thứ sinh viên tự nhìn thấy được.
    if (filled != _decodeInput(expectedValues[i])) {
      _observe(
        'TEXT_MISMATCH',
        subject: 'field',
        seen: filled,
        where: 'after_action',
      );
    }
    expect(
      filled,
      _decodeInput(expectedValues[i]),
      reason: 'Field ${fields[i]} không được prefill đúng',
    );
  }
}

Future<void> _checkFormSubmit(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fields = _csv(parameters, 'fieldKeys');
  final values = _csv(parameters, 'values');
  if (fields.isEmpty || fields.length != values.length) {
    fail('fieldKeys và values phải có cùng số phần tử.');
  }
  await _fillFields(tester, parameters);
  final submitKey = _requiredText(parameters, 'submitKey');
  await _tap(tester, _byKey(submitKey), 'Không tìm thấy nút lưu: $submitKey');
  await _settle(tester);
  _failIfActionThrew(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) {
    _expectPresent(
      _byKey(resultKey),
      'item',
      'Lưu xong nhưng không thấy kết quả: $resultKey',
      where: 'after_action',
    );
  }
  for (final errorKey in _csv(parameters, 'errorKeys')) {
    _expectGone(
      _goneByKey(errorKey),
      'error',
      'Dữ liệu hợp lệ nhưng vẫn còn error key: $errorKey',
      where: 'after_action',
    );
  }
}

Future<void> _checkDialogFlow(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final actionKey = _requiredText(parameters, 'actionKey');
  await _tap(
    tester,
    _byKey(actionKey),
    'Không tìm thấy nút mở hộp thoại: $actionKey',
  );
  await _settle(tester);
  _failIfActionThrew(tester);

  final dialogKey = _requiredText(parameters, 'dialogKey');
  final dialogFinder = _byKey(dialogKey);
  _expectPresent(
    dialogFinder,
    'dialog',
    'Không tìm thấy dialog key: $dialogKey',
    where: 'after_action',
  );
  _assertTargetType(tester, dialogFinder, dialogKey, 'dialog');

  final decisionKey = _requiredText(parameters, 'decisionKey');
  await _tap(
    tester,
    _byKey(decisionKey),
    'Không tìm thấy nút xác nhận trong hộp thoại: $decisionKey',
    where: 'after_action',
  );
  await _settle(tester);
  _failIfActionThrew(tester);
  final resultKey = _text(parameters, 'resultKey');
  if (resultKey.isNotEmpty) {
    _expectPresent(
      _byKey(resultKey),
      'item',
      'Xác nhận xong nhưng không thấy kết quả: $resultKey',
      where: 'after_action',
    );
  }
  final absentKey = _text(parameters, 'absentKey');
  if (absentKey.isNotEmpty) {
    _expectGone(
      _goneByKey(absentKey),
      'item',
      'Sau khi xác nhận, mục lẽ ra phải biến mất vẫn còn: $absentKey',
      where: 'after_action',
    );
  }
}

Future<void> _checkWidgetSemanticsLabel(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  // flutter_test kiểm tra SemanticsHandle còn sống NGAY khi thân test kết thúc, tức
  // TRƯỚC khi tearDown chạy. Dùng addTearDown(semantics.dispose) sẽ khiến testcase
  // luôn fail "A SemanticsHandle was active at the end of the test" dù bài làm đúng.
  // PHẢI trả handle trong `finally`, KHÔNG dùng addTearDown: flutter_test kiểm
  // "còn SemanticsHandle nào đang mở?" NGAY KHI thân test kết thúc, trước khi các
  // addTearDown chạy. Dùng addTearDown thì runner này KHÔNG BAO GIỜ ĐẠT ĐƯỢC — sinh
  // viên gắn nhãn trợ năng đúng vẫn mất điểm, và mất theo cách tệ nhất: lỗi báo về
  // là log tiếng Anh nói chuyện nội bộ của bộ chấm, không phải điều em ấy quan sát được.
  final semantics = tester.ensureSemantics();
  try {
    await _boot(tester);
    final key = _requiredText(parameters, 'targetKey');
    final targetType = _text(parameters, 'targetType', 'any');
    final finder = _byKey(key);
    expect(finder, findsOneWidget, reason: 'Không tìm thấy target key: $key');
    _expectPresent(
      finder,
      _subject(parameters),
      'Không tìm thấy target key: $key',
    );
    _assertTargetType(tester, finder, key, targetType);
    final actual = tester.getSemantics(finder).label;
    final expected = _requiredText(parameters, 'expectedLabel');
    final matchMode = _text(parameters, 'matchMode', 'equals').toLowerCase();
    // Nhãn trợ năng là chữ trình đọc màn hình đọc lên cho người dùng — được phép in.
    final labelOk = matchMode == 'contains'
        ? actual.contains(expected)
        : actual == expected;
    if (!labelOk) {
      _observe(
        'LABEL_MISMATCH',
        subject: 'widget',
        seen: actual.isEmpty ? null : actual,
      );
    }
    if (matchMode == 'contains') {
      expect(
        actual,
        contains(expected),
        reason: 'Semantics label của $key không chứa expectedLabel',
      );
    } else {
      expect(actual, expected, reason: 'Semantics label của $key không đúng');
    }
  } finally {
    semantics.dispose();
  }
}

Future<void> _checkWidgetPadding(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(
    finder,
    _subject(parameters),
    'Không tìm thấy target key: $key',
  );
  _assertTargetType(tester, finder, key, targetType);

  final render = tester.renderObject<RenderPadding>(finder);
  final padding = render.padding.resolve(TextDirection.ltr);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  _assertNumber(
    padding.left,
    _number(parameters, 'left', double.nan),
    tolerance,
    'equals',
    '$key left padding',
    dimension: 'khoảng đệm bên trái',
  );
  _assertNumber(
    padding.top,
    _number(parameters, 'top', double.nan),
    tolerance,
    'equals',
    '$key top padding',
    dimension: 'khoảng đệm phía trên',
  );
  _assertNumber(
    padding.right,
    _number(parameters, 'right', double.nan),
    tolerance,
    'equals',
    '$key right padding',
    dimension: 'khoảng đệm bên phải',
  );
  _assertNumber(
    padding.bottom,
    _number(parameters, 'bottom', double.nan),
    tolerance,
    'equals',
    '$key bottom padding',
    dimension: 'khoảng đệm phía dưới',
  );
}

Future<void> _checkWidgetTextStyle(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final key = _requiredText(parameters, 'targetKey');
  final targetType = _requiredText(parameters, 'targetType');
  final finder = _byKey(key);
  _expectPresent(finder, 'text', 'Không tìm thấy target key: $key');
  _assertTargetType(tester, finder, key, targetType);

  final text = tester.widget<Text>(finder);
  final resolved = DefaultTextStyle.of(tester.element(finder)).style
      .merge(text.style);
  final tolerance = _number(parameters, 'tolerance', 0.5);
  final expectedSize = _number(parameters, 'fontSize', double.nan);
  if (!expectedSize.isNaN) {
    _assertNumber(
      resolved.fontSize ?? double.nan,
      expectedSize,
      tolerance,
      'equals',
      '$key fontSize',
      dimension: 'cỡ chữ',
    );
  }
  final expectedWeight = _text(parameters, 'fontWeight');
  if (expectedWeight.isNotEmpty) {
    if (resolved.fontWeight != _fontWeight(expectedWeight)) {
      _observe('STYLE_MISMATCH', subject: 'text', dimension: 'độ đậm chữ');
    }
    expect(
      resolved.fontWeight,
      _fontWeight(expectedWeight),
      reason: '$key fontWeight không đúng',
    );
  }
}

Future<void> _checkWidgetGap(
  WidgetTester tester,
  Map<String, dynamic> parameters,
) async {
  await _boot(tester);
  final fromKey = _requiredText(parameters, 'fromKey');
  final toKey = _requiredText(parameters, 'toKey');
  final fromFinder = _byKey(fromKey);
  final toFinder = _byKey(toKey);
  _expectPresent(fromFinder, 'widget', 'Không tìm thấy fromKey: $fromKey');
  _expectPresent(toFinder, 'widget', 'Không tìm thấy toKey: $toKey');
  final fromType = _text(parameters, 'fromType');
  final toType = _text(parameters, 'toType');
  if (fromType.isNotEmpty)
    _assertTargetType(tester, fromFinder, fromKey, fromType);
  if (toType.isNotEmpty) _assertTargetType(tester, toFinder, toKey, toType);

  final axis = _text(parameters, 'axis').toLowerCase();
  final from = tester.getRect(fromFinder);
  final to = tester.getRect(toFinder);
  final actual = axis == 'horizontal'
      ? to.left - from.right
      : to.top - from.bottom;
  _assertNumber(
    actual,
    _number(parameters, 'expectedGap', double.nan),
    _number(parameters, 'tolerance', 0.5),
    'equals',
    'Khoảng cách $fromKey → $toKey',
    dimension: 'khoảng cách giữa hai thành phần',
  );
}

String _requiredText(Map<String, dynamic> map, String key) {
  final value = _text(map, key);
  if (value.isEmpty) fail('Thiếu parameter $key');
  return value;
}

void _assertTargetType(
  WidgetTester tester,
  Finder finder,
  String key,
  String targetType,
) {
  final normalizedType = targetType.toLowerCase();
  if (normalizedType == 'any') return;
  final widget = tester.widget<Widget>(finder);
  final matches = switch (normalizedType) {
    'form' => widget is Form,
    'image' => widget is Image,
    'text' => widget is Text,
    'input' => widget is TextField || widget is TextFormField,
    'control' =>
      widget is TextField ||
          widget is TextFormField ||
          widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is DropdownButton ||
          widget is DropdownButtonFormField ||
          widget is Checkbox ||
          widget is Switch,
    'button' =>
      widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is FloatingActionButton ||
          widget is RawMaterialButton,
    'dialog' => widget is AlertDialog,
    'icon' => widget is Icon,
    'checkbox' => widget is Checkbox,
    'switch' => widget is Switch,
    'dropdown' => widget is DropdownButton,
    'padding' => widget is Padding,
    // Template dùng key vai trò screen.home có thể fallback vào Scaffold khi
    // bài không gắn ValueKey; README không bắt buộc sinh viên phải dùng key.
    'container' => widget is Container || widget is Scaffold,
    // ── Ch.7: Widget bố cục và hiển thị nâng cao (3.6.0) ──
    'listview' => widget is ListView,
    'gridview' => widget is GridView,
    'stack' => widget is Stack,
    'indexedstack' => widget is IndexedStack,
    'table' => widget is Table,
    'card' => widget is Card,
    'customscrollview' => widget is CustomScrollView,
    'singlechildscrollview' => widget is SingleChildScrollView,
    'expanded' => widget is Expanded,
    _ => false,
  };
  if (!matches) {
    // Thành phần CÓ MẶT nhưng SAI LOẠI — chẩn đoán riêng, không phải "không thấy gì". Cách sửa
    // của sinh viên cũng riêng: dùng đúng loại widget, không phải thêm thành phần còn thiếu.
    //
    // Trước A2b chỗ này `fail()` trần: chín runner gọi `_assertTargetType`, nên đây là lỗ hổng
    // thứ hai của kênh quan sát — sai loại widget là sinh viên nhận nguyên tên class tiếng Anh.
    // KHÔNG in `widget.runtimeType` vào quan sát: đó là định danh nội bộ của Flutter (luật C3).
    _observe('TYPE_MISMATCH', subject: _subjectOfType(normalizedType));
    fail(
      'Key $key đang gắn vào ${widget.runtimeType}, không phải targetType=$targetType',
    );
  }
}

bool? _enabledState(Widget widget) {
  if (widget is TextField) return widget.enabled;
  if (widget is TextFormField) return widget.enabled;
  if (widget is ButtonStyleButton) return widget.onPressed != null;
  if (widget is IconButton) return widget.onPressed != null;
  if (widget is FloatingActionButton) return widget.onPressed != null;
  if (widget is RawMaterialButton) return widget.onPressed != null;
  if (widget is Checkbox) return widget.onChanged != null;
  if (widget is Switch) return widget.onChanged != null;
  if (widget is DropdownButton) return widget.onChanged != null;
  return null;
}

String _decodeInput(String value) => value == '__EMPTY__' ? '' : value;

bool _bool(Map<String, dynamic> map, String key, bool fallback) {
  final value = map[key];
  if (value is bool) return value;
  return value == null ? fallback : value.toString().toLowerCase() == 'true';
}

/// Điểm chung của WIDGET_DIMENSION / WIDGET_PADDING / WIDGET_GAP / WIDGET_TEXT_STYLE —
/// nên phát quan sát ở đây là phủ được cả bốn runner bằng một chỗ.
///
/// [dimension] là tên phép đo hiển thị cho sinh viên (`chiều rộng`, `khoảng cách`…), KHÔNG phải
/// semantic key. Số đo là quan sát được: sinh viên đo lại trên máy mình là ra.
void _assertNumber(
  double actual,
  double expected,
  double tolerance,
  String comparison,
  String reason, {
  String dimension = 'kích thước',
  String unit = 'px',
}) {
  if (expected.isNaN) fail('Thiếu giá trị số khi kiểm tra: $reason');
  final margin = tolerance < 0 ? 0 : tolerance;
  final mode = comparison.isEmpty ? 'equals' : comparison.toLowerCase();
  final ok = switch (mode) {
    'at_least' => actual >= expected - margin,
    'at_most' => actual <= expected + margin,
    _ => (actual - expected).abs() <= margin,
  };
  if (!ok) {
    _observe(
      'NUMBER_MISMATCH',
      dimension: dimension,
      expected: expected,
      found: actual,
      unit: unit,
    );
  }
  switch (mode) {
    case 'at_least':
      expect(actual, greaterThanOrEqualTo(expected - margin), reason: reason);
      return;
    case 'at_most':
      expect(actual, lessThanOrEqualTo(expected + margin), reason: reason);
      return;
    default:
      expect(actual, closeTo(expected, margin), reason: reason);
  }
}

FontWeight _fontWeight(String value) {
  const weights = <String, FontWeight>{
    'w100': FontWeight.w100,
    'w200': FontWeight.w200,
    'w300': FontWeight.w300,
    'w400': FontWeight.w400,
    'w500': FontWeight.w500,
    'w600': FontWeight.w600,
    'w700': FontWeight.w700,
    'w800': FontWeight.w800,
    'w900': FontWeight.w900,
  };
  return weights[value.toLowerCase()] ?? FontWeight.w400;
}

/// Finder cho phép kiểm **"ĐÃ BIẾN MẤT"** — chỉ nhận `ValueKey` chính xác, KHÔNG fallback.
///
/// Fallback theo vai trò/vị trí của [_byKey] chứng minh được *"có thứ đại loại như vậy"*
/// nhưng KHÔNG BAO GIỜ chứng minh được *"đúng thứ đó đã mất"* — dùng nó cho `findsNothing`
/// là chấm sai điểm sinh viên làm đúng:
///
///  - `item.1` rơi về `ListTile` ở **vị trí 0**: sinh viên xoá đúng người dùng thứ nhất thì
///    finder lại bắt được người thứ hai đang đứng đầu ⇒ luôn hỏng.
///  - `error.name` rơi về bất kỳ `Text` khớp `name|họ|tên|full`: bắt trúng **nhãn của ô nhập**
///    ("Full name") ⇒ phép kiểm "không còn thông báo lỗi" luôn hỏng.
///
/// Không đặt `skipOffstage: false`: màn hình trước trong Navigator vẫn nằm trong cây widget
/// nhưng đã bị che, không được tính là "còn hiển thị".
///
/// Sinh viên không dùng key thì finder rỗng và phép kiểm đạt — cố ý nghiêng về phía sinh viên,
/// vì phần khẳng định CHÍNH của mỗi runner vẫn phải đạt riêng.
Finder _goneByKey(String key) => find.byKey(ValueKey<String>(key));

// ═══════════════════════════════════════════════════════════════
// Ch.7 — định vị bằng Semantics(identifier:), KHÔNG dùng ValueKey.
//
// Tám runner widget bố cục/hiển thị ở dưới (SCROLL_DIRECTION…EXPANDED_WIDGET) từng khai đích
// bằng ValueKey như phần còn lại của file này, nhưng đề chỉ nên có MỘT hệ định danh: phần tương
// tác (CRUD, form, dialog…) của bộ engine này đã dùng Semantics(identifier:) qua các heuristic
// vai trò ở `_byKey`, nên nhóm tiêu chí bố cục dùng ValueKey riêng là hai hệ song song, học một
// cách cho từng nhóm và nhầm lẫn cả hai. Đổi hẳn sang định danh cho khớp, theo đúng cách
// `behavior-replay-engine/exam_test.dart` (engine Golden Solution Record–Abstract–Replay đang
// dùng thật) đã làm trước — xem lịch sử file đó để biết số liệu đo thật (8/8 đạt, 8/8 trượt đúng
// chỗ khi gài lỗi).
// ═══════════════════════════════════════════════════════════════

/// Tìm widget theo Semantics(identifier: …). `skipOffstage: false` để bắt được item lazy-list
/// vừa dựng xong nhưng chưa vào khung nhìn (cần cho SCROLL_TO_END).
Finder _byIdentifier(String id) => find.bySemanticsIdentifier(id, skipOffstage: false);

/// Như [_goneByKey] nhưng cho định danh: KHÔNG override skipOffstage nên mặc định bỏ qua widget
/// offstage — dùng cho phép kiểm "đã biến mất thật" (ví dụ sheet đã đóng), lý do y hệt [_goneByKey].
Finder _goneByIdentifier(String id) => find.bySemanticsIdentifier(id);

/// Widget THẬT dưới lớp bọc Semantics — tìm theo định danh trả về node Semantics (hoặc
/// SliverSemantics) bọc ngoài chứ không phải Stack/Table bên trong, nên phép kiểm "đúng kiểu"
/// phải đi xuống một nấc mới gặp đúng widget cần kiểm.
Finder _duoiLopBoc(Finder wrapper, Type type) => find.descendant(
  of: wrapper,
  matching: find.byType(type, skipOffstage: false),
  matchRoot: true,
);

Finder _roleActionFinder(String key) {
  switch (key) {
    case 'action.delete.cancel':
      return _buttonWithText(
        RegExp(r'^(cancel|no|hủy|huỷ|đóng|close)$', caseSensitive: false),
      );
    case 'action.delete.confirm':
      return _buttonWithText(
        RegExp(
          r'^(confirm( delete)?|yes|delete|remove|xóa|xoá|đồng ý)$',
          caseSensitive: false,
        ),
      );
    default:
      return _notFound();
  }
}
Finder _byKey(String key) {
  final exact = find.byKey(ValueKey<String>(key), skipOffstage: false);
  if (exact.evaluate().isNotEmpty) return exact;

  // Hợp đồng bài làm (Khu vực 0) quyết định cách dò khi bài không gắn ValueKey.
  // Không có hợp đồng thì giữ nguyên heuristic cũ để đề cũ chấm lại vẫn ra đúng.
  final rule = _contractRule(key);
  if (_contractRequiresKeys() && !_ruleFlag(rule, 'allow_fallback'))
    return _notFound();
  if (rule != null) {
    final strategy = _text(rule, 'strategy', 'auto');
    if (strategy == 'key_only') return _notFound();
    if (strategy != 'auto') {
      final finder = _contractFinder(rule);
      // Với contract không bắt buộc ValueKey, locator của giảng viên là ưu tiên
      // chứ không phải điểm dừng tuyệt đối. Locator rỗng phải được phép rơi về
      // heuristic vai trò; nếu không chỉ một khác biệt GridView/SliverGrid hay
      // TextFormField/nút chọn avatar sẽ làm cả luồng CRUD mất điểm dây chuyền.
      if (finder != null && finder.evaluate().isNotEmpty) return finder;
    }
  }

  // Public contract của starter không ép ValueKey. Khi không có key, dùng
  // fallback theo vai trò hiển thị để template vẫn đánh giá được hành vi.
  switch (key) {
    case 'screen.home':
    case 'screen.list':
      return find.byType(Scaffold, skipOffstage: false);
    case 'screen.detail':
      return find.text('User Detail', skipOffstage: false);
    case 'field.title':
    case 'field.name':
    case 'field.fullName':
      return _textFormFieldAt(0);
    case 'field.email':
      return _textFormFieldAt(1);
    case 'field.avatar':
      final dropdown = find.byWidgetPredicate(
        (widget) => widget is DropdownButtonFormField,
        skipOffstage: false,
      );
      if (dropdown.evaluate().isNotEmpty) return dropdown.first;
      return _buttonWithText(
        RegExp(r'avatar|image|photo|picture|ảnh|hình', caseSensitive: false),
      );
    case 'action.save':
      return _buttonWithText(
        RegExp(
          r'^(add|create|save|submit|update|thêm|tạo|lưu|cập nhật)(\s+user)?$',
          caseSensitive: false,
        ),
      );
    case 'action.item.edit':
      return _buttonWithText(
        RegExp(r'^(edit|sửa|chỉnh sửa)$', caseSensitive: false),
      );
    case 'action.delete':
      return _buttonWithText(
        RegExp(r'^(delete|remove|xóa)$', caseSensitive: false),
      );
    case 'action.delete.cancel':
      return _buttonWithText(
        RegExp(r'^(cancel|no|hủy|đóng)$', caseSensitive: false),
      );
    case 'action.delete.confirm':
      return _buttonWithText(
        RegExp(
          r'^(confirm( delete)?|yes|delete|remove|xóa|xoá|đồng ý)$',
          caseSensitive: false,
        ),
      );
    case 'action.back':
      return _buttonWithText(
        RegExp(r'^(back|quay lại|trở về)$', caseSensitive: false),
      );
    case 'action.open-detail':
      return find.byType(ListTile, skipOffstage: false);
    case 'list.items':
      return _collectionFinder();
    case 'item.1':
      return _listTileAt(0);
    case 'item.2':
      return _listTileAt(1);
    case 'item.3':
      return _listTileAt(2);
    case 'dialog.delete':
      return find.byType(AlertDialog, skipOffstage: false);
    case 'message.success':
      return find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            RegExp(
              r'success|successful|saved|added|updated|thành công|đã thêm|đã cập nhật|đã lưu',
              caseSensitive: false,
            ).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );
    case 'state.empty':
      return find.byWidgetPredicate(
        (widget) =>
            widget is Text &&
            RegExp(
              r'no users|empty|chưa có|không có',
              caseSensitive: false,
            ).hasMatch(widget.data ?? ''),
        skipOffstage: false,
      );
    case 'state.loaded':
      return _listTileAt(0);
    case 'text.screen.title':
      final appBar = find.byType(AppBar, skipOffstage: false);
      return find.descendant(
        of: appBar,
        matching: find.byType(Text, skipOffstage: false),
        matchRoot: true,
      );
    default:
      if (key.startsWith('error.')) return _validationErrorFor(key);
      return _notFound();
  }
}

Finder _textFormFieldAt(int index) {
  // TextFormField DỰNG một TextField con, nên predicate "is TextFormField || is TextField"
  // đếm mỗi ô nhập hai lần → field.email (index 1) lại trỏ vào chính ô đầu tiên.
  // Chỉ đếm TextField: bài dùng TextFormField vẫn khớp qua TextField con, và mọi thao tác
  // (enterText, đọc EditableText, targetType='input') đều chạy đúng trên widget này.
  final fields = find.byType(TextField, skipOffstage: false);
  return fields.evaluate().length > index ? fields.at(index) : _notFound();
}

Finder _collectionFinder() => find.byWidgetPredicate(
  (widget) =>
      widget is ListView ||
      widget is GridView ||
      widget is SliverList ||
      widget is SliverGrid ||
      widget is CustomScrollView,
  skipOffstage: false,
);

Finder _listTileAt(int index) {
  final items = find.byType(ListTile, skipOffstage: false);
  return items.evaluate().length > index ? items.at(index) : _notFound();
}

Finder _buttonWithText(RegExp pattern) {
  final labels = find.byWidgetPredicate(
    (widget) => widget is Text && pattern.hasMatch((widget.data ?? '').trim()),
    skipOffstage: false,
  );
  final buttons = find.ancestor(
    of: labels,
    matching: find.byWidgetPredicate(
      (widget) =>
          widget is ButtonStyleButton ||
          widget is IconButton ||
          widget is FloatingActionButton ||
          widget is RawMaterialButton,
      skipOffstage: false,
    ),
    matchRoot: true,
  );
  return buttons.evaluate().isNotEmpty ? buttons.first : _notFound();
}

/// Fallback tìm thông báo lỗi của một ô nhập khi bài không đặt `ValueKey`.
///
/// ĐIỀU KIỆN BẮT BUỘC: nội dung `Text` phải nói lên chuyện LỖI. Bản trước ưu tiên nhánh
/// "khớp tên field" mà không đòi thêm gì, nên **nhãn của ô nhập** ("Full name", "Email")
/// cũng được tính là thông báo lỗi ⇒ form không kiểm dữ liệu gì vẫn đạt. Đó là chấm sai
/// điểm theo chiều CHO ĐIỂM OAN, ngược với lỗi `_byKey` nhưng cùng một nguồn: fallback
/// đoán theo chữ hiển thị.
/// Kiểm tra errorText/error widget mà Flutter render bên trong đúng field.
/// Không phụ thuộc câu chữ/localization và không lấy nhầm lỗi field khác.
bool _fieldValidationError(Finder field) {
  if (field.evaluate().isEmpty) return false;
  final decorators = find.descendant(
    of: field,
    matching: find.byType(InputDecorator, skipOffstage: false),
    matchRoot: true,
  );
  for (final element in decorators.evaluate()) {
    final decorator = element.widget as InputDecorator;
    if ((decorator.decoration.errorText ?? '').trim().isNotEmpty ||
        decorator.decoration.error != null) {
      return true;
    }
  }
  return false;
}

Finder _validationErrorFor(String key) {
  // Chỉ chấp nhận Text TRÔNG NHƯ thông báo lỗi. Trước đây nhánh "specific" chỉ khớp
  // tên field nên nhãn ô nhập ("Full Name", "Email") cũng bị tính là lỗi validation
  // → FORM_REQUIRED_FIELDS/FORM_VALIDATE_FIELDS pass giả dù bài không validate gì.
  final errorPattern = RegExp(
    r'required|minimum|at least|invalid|must|cannot|empty|not valid'
    r'|bắt buộc|tối thiểu|không hợp lệ|không được|vui lòng|hãy nhập|sai định dạng|lỗi',
    caseSensitive: false,
  );
  bool isError(Widget widget) =>
      widget is Text && errorPattern.hasMatch(widget.data ?? '');

  final all = find.byWidgetPredicate(isError, skipOffstage: false);
  final email = key.toLowerCase().contains('email');
  final fieldPattern = email
      ? RegExp(r'email|e-mail|định dạng', caseSensitive: false)
      : RegExp(r'name|họ|tên|full', caseSensitive: false);
  // Lỗi của đúng field: vừa là thông báo lỗi, vừa nhắc tới tên field.
  final specific = find.byWidgetPredicate(
    (widget) =>
        isError(widget) && fieldPattern.hasMatch((widget as Text).data ?? ''),
    skipOffstage: false,
  );
  return specific.evaluate().isNotEmpty ? specific.first : all;
}

Finder _notFound() => find.byWidgetPredicate((_) => false);

// ─────────────────────── HỢP ĐỒNG BÀI LÀM (Khu vực 0) ───────────────────────
// Giáo viên khai mỗi semantic key được dò thế nào khi bài không gắn ValueKey.
// Trước đây cách dò bị hardcode trong _byKey nên bài dùng GridView/Card/nút icon
// (đúng đề nhưng khác giả định) bị chấm trượt oan.

Map<String, dynamic>? _contractCache;

Map<String, dynamic> _contract() {
  if (_contractCache != null) return _contractCache!;
  for (final path in <String>['test/contract.json', 'contract.json']) {
    final file = File(path);
    if (!file.existsSync()) continue;
    final value = jsonDecode(file.readAsStringSync());
    if (value is Map) return _contractCache = _asMap(value);
  }
  return _contractCache = <String, dynamic>{};
}

/// Khi ch?y t?ng testcase b?ng process ri?ng, CWD c? th? kh?ng ch?a contract.json.
/// Reset cache v? cho flow tr?ng y?u d?ng fallback vai tr? thay v? m?t locator d?y chuy?n.
void _reloadContract() {
  _contractCache = null;
  _contract();
}

/// Đề bắt buộc sinh viên gắn ValueKey: bỏ hết cách dò thay thế, thiếu key là trượt.
bool _contractRequiresKeys() => _contract()['require_keys'] == true;

bool _ruleFlag(Map<String, dynamic>? rule, String key) =>
    rule != null && rule[key] == true;

Map<String, dynamic>? _contractRule(String key) {
  final keys = _contract()['keys'];
  if (keys is List) {
    for (final raw in keys) {
      final rule = _asMap(raw);
      if (_text(rule, 'key') == key) return rule;
    }
  } else if (keys is Map && keys[key] != null) {
    return _asMap(keys[key]);
  }
  return null;
}

Finder? _contractFinder(Map<String, dynamic> rule) {
  final strategy = _text(rule, 'strategy');
  final value = _text(rule, 'value');
  final index = _number(rule, 'index', 0).toInt();
  switch (strategy) {
    case 'widget_type':
      return _pickAt(_byTypeName(value), index);
    case 'icon':
      return _pickAt(_buttonOrSelf(_iconFinder(value)), index);
    case 'tooltip':
      return _pickAt(
        _buttonOrSelf(find.byTooltip(value, skipOffstage: false)),
        index,
      );
    case 'text':
      return _pickAt(_textLike(value), index);
    case 'button_text':
      return _pickAt(_buttonOrSelf(_textLike(value)), index);
    case 'type_with_text':
      return _pickAt(
        find.ancestor(
          of: _textLike(_text(rule, 'text')),
          matching: _byTypeName(value),
          matchRoot: true,
        ),
        index,
      );
    default:
      return null;
  }
}

/// Áp dụng rule contract trong phạm vi một item cụ thể. Hàm này ngăn trường hợp
/// nút Edit/Delete của card đầu tiên bị dùng nhầm khi testcase đang chấm card khác.
Finder _buttonOrSelfWithin(Finder inner, Finder ancestor) {
  if (inner.evaluate().isEmpty) return _notFound();
  for (final matcher in <bool Function(Widget)>[_isRealButton, _isTappable]) {
    final buttons = find.ancestor(
      of: inner,
      matching: find.byWidgetPredicate(matcher, skipOffstage: false),
      matchRoot: true,
    );
    final scoped = find.descendant(of: ancestor, matching: buttons, matchRoot: true);
    if (scoped.evaluate().isNotEmpty) return scoped;
  }
  return inner;
}

/// Dò locator theo label trước rồi mới leo lên widget action. Vì vậy contract
/// không thể lấy nhầm nút cùng chữ ở một dialog/card khác ngoài ancestor.
Finder? _contractFinderWithin(Map<String, dynamic> rule, Finder ancestor) {
  final strategy = _text(rule, 'strategy');
  final value = _text(rule, 'value');
  final index = _number(rule, 'index', 0).toInt();
  Finder scoped(Finder finder) =>
      find.descendant(of: ancestor, matching: finder, matchRoot: true);

  switch (strategy) {
    case 'widget_type':
      return _pickAt(scoped(_byTypeName(value)), index);
    case 'icon':
      return _pickAt(
        _buttonOrSelfWithin(scoped(_iconFinder(value)), ancestor),
        index,
      );
    case 'tooltip':
      return _pickAt(
        _buttonOrSelfWithin(
          scoped(find.byTooltip(value, skipOffstage: false)),
          ancestor,
        ),
        index,
      );
    case 'text':
      return _pickAt(scoped(_textLike(value)), index);
    case 'button_text':
      final labels = scoped(_textLike(value));
      return _pickAt(_buttonOrSelfWithin(labels, ancestor), index);
    case 'type_with_text':
      final labels = scoped(_textLike(_text(rule, 'text')));
      final typed = find.ancestor(
        of: labels,
        matching: _byTypeName(value),
        matchRoot: true,
      );
      return _pickAt(scoped(typed), index);
    default:
      return null;
  }
}
Finder _pickAt(Finder finder, int index) {
  // Thiếu phần tử thứ index thì phải BÁO KHÔNG TÌM THẤY. Nếu tự lùi về phần tử 0,
  // "item.1" sẽ trỏ vào chính card của form và testcase pass giả khi danh sách rỗng.
  final count = finder.evaluate().length;
  if (index < 0 || index >= count) return _notFound();
  return finder.at(index);
}

/// Khớp theo TÊN class để giáo viên gõ được 'SliverGrid', 'Card', 'InkWell'...
/// mà engine không cần map cứng từng loại widget.
Finder _byTypeName(String name) {
  final wanted = name.trim();
  if (wanted.isEmpty) return _notFound();
  return find.byWidgetPredicate((widget) {
    final actual = widget.runtimeType.toString();
    final base = actual.contains('<')
        ? actual.substring(0, actual.indexOf('<'))
        : actual;
    return base == wanted;
  }, skipOffstage: false);
}

/// Khớp text chính xác hoặc biểu thức /.../flags từ contract.
/// Hỗ trợ các flag Dart phổ biến (i/m/s/u), bao gồm contract dạng /.../i.
Finder _textLike(String value) {
  final trimmed = value.trim();
  if (trimmed.isEmpty) return _notFound();
  RegExp? pattern;
  try {
    final regex = RegExp(r'^/(.*)/([imsu]*)$').firstMatch(trimmed);
    pattern = regex == null
        ? RegExp('^${RegExp.escape(trimmed)}\$', caseSensitive: false)
        : RegExp(
            regex.group(1)!,
            caseSensitive: !regex.group(2)!.contains('i'),
            multiLine: regex.group(2)!.contains('m'),
            dotAll: regex.group(2)!.contains('s'),
            unicode: regex.group(2)!.contains('u'),
          );
  } on FormatException {
    // Contract legacy có regex hỏng không được làm crash toàn bộ suite.
    return _notFound();
  } catch (_) {
    return _notFound();
  }
  return find.byWidgetPredicate(
    (widget) => widget is Text && pattern!.hasMatch((widget.data ?? '').trim()),
    skipOffstage: false,
  );
}
/// Nhóm icon theo Ý NGHĨA: chọn "Sửa (bút)" là khớp mọi biến thể edit/mode_edit/create,
/// vì đề chỉ yêu cầu "icon bút" chứ không chỉ định đúng một hằng Icons nào.
const Map<String, List<IconData>> _iconGroups = <String, List<IconData>>{
  'edit': <IconData>[
    Icons.edit,
    Icons.edit_outlined,
    Icons.edit_note,
    Icons.mode_edit,
    Icons.mode_edit_outlined,
    Icons.create,
    Icons.create_outlined,
    Icons.drive_file_rename_outline,
  ],
  'delete': <IconData>[
    Icons.delete,
    Icons.delete_outline,
    Icons.delete_forever,
    Icons.delete_rounded,
    Icons.remove_circle,
    Icons.remove_circle_outline,
  ],
  'add': <IconData>[
    Icons.add,
    Icons.add_circle,
    Icons.add_circle_outline,
    Icons.add_box,
    Icons.add_box_outlined,
    Icons.playlist_add,
  ],
  'save': <IconData>[
    Icons.save,
    Icons.save_outlined,
    Icons.save_alt,
    Icons.check,
    Icons.check_circle,
    Icons.check_circle_outline,
    Icons.done,
  ],
  'back': <IconData>[
    Icons.arrow_back,
    Icons.arrow_back_ios,
    Icons.arrow_back_ios_new,
    Icons.chevron_left,
    Icons.keyboard_arrow_left,
  ],
  'forward': <IconData>[
    Icons.arrow_forward,
    Icons.arrow_forward_ios,
    Icons.chevron_right,
    Icons.open_in_new,
    Icons.visibility,
    Icons.visibility_outlined,
  ],
  'close': <IconData>[
    Icons.close,
    Icons.cancel,
    Icons.cancel_outlined,
    Icons.clear,
  ],
  'search': <IconData>[Icons.search, Icons.manage_search],
  'person': <IconData>[
    Icons.person,
    Icons.person_outline,
    Icons.person_outlined,
    Icons.account_circle,
    Icons.account_circle_outlined,
  ],
  'email': <IconData>[
    Icons.email,
    Icons.email_outlined,
    Icons.mail,
    Icons.mail_outline,
    Icons.alternate_email,
  ],
  'image': <IconData>[
    Icons.image,
    Icons.image_outlined,
    Icons.photo,
    Icons.photo_outlined,
    Icons.add_photo_alternate,
    Icons.add_photo_alternate_outlined,
    Icons.camera_alt,
  ],
  'menu': <IconData>[
    Icons.menu,
    Icons.menu_open,
    Icons.more_vert,
    Icons.more_horiz,
  ],
};

Finder _iconFinder(String value) {
  final name = value.trim();
  final group = _iconGroups[name];
  final codePoint = group == null ? int.tryParse(name) : null;
  if (group == null && codePoint == null) return _notFound();
  return find.byWidgetPredicate((widget) {
    if (widget is! Icon) return false;
    final icon = widget.icon;
    if (icon == null) return false;
    if (codePoint != null) return icon.codePoint == codePoint;
    return group!.any(
      (candidate) =>
          candidate.codePoint == icon.codePoint &&
          candidate.fontFamily == icon.fontFamily,
    );
  }, skipOffstage: false);
}

/// Nút bọc ngoài (nếu có) để targetType='button' và tap() đều đúng đối tượng.
Finder _buttonOrSelf(Finder inner) {
  if (inner.evaluate().isEmpty) return inner;
  // Nút thật phải được ưu tiên: mỗi button Material tự dựng một InkWell BÊN TRONG nó,
  // nên lấy ancestor gần nhất sẽ ra InkWell chứ không phải FilledButton/IconButton.
  for (final matcher in <bool Function(Widget)>[_isRealButton, _isTappable]) {
    final found = find.ancestor(
      of: inner,
      matching: find.byWidgetPredicate(matcher, skipOffstage: false),
      matchRoot: true,
    );
    if (found.evaluate().isNotEmpty) return found;
  }
  return inner;
}

bool _isRealButton(Widget widget) =>
    widget is ButtonStyleButton ||
    widget is IconButton ||
    widget is FloatingActionButton ||
    widget is RawMaterialButton;

bool _isTappable(Widget widget) =>
    widget is InkWell || widget is GestureDetector;

Map<String, dynamic> _loadMatrix() {
  for (final path in <String>[
    'test/skills_matrix.json',
    'skills_matrix.json',
  ]) {
    final file = File(path);
    if (!file.existsSync()) continue;
    final value = jsonDecode(file.readAsStringSync());
    if (value is Map) return _asMap(value);
  }
  return <String, dynamic>{};
}

Map<String, dynamic> _asMap(dynamic value) {
  if (value is! Map) return <String, dynamic>{};
  return <String, dynamic>{
    for (final entry in value.entries) entry.key.toString(): entry.value,
  };
}

String _text(Map<String, dynamic> map, String key, [String fallback = '']) {
  final value = map[key];
  return value == null ? fallback : value.toString().trim();
}

List<String> _csv(Map<String, dynamic> map, String key) => _text(map, key)
    .split(',')
    .map((value) => value.trim())
    .where((value) => value.isNotEmpty)
    .toList();

double _number(Map<String, dynamic> map, String key, double fallback) {
  final value = map[key];
  return value is num
      ? value.toDouble()
      : double.tryParse('$value') ?? fallback;
}
