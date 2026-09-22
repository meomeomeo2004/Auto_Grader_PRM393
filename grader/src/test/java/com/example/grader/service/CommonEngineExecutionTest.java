package com.example.grader.service;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Chốt các cơ chế an toàn tối thiểu của engine Dart được chép vào từng đề. */
class CommonEngineExecutionTest {

    @Test
    void graderRunsSmallBatchesInTimeLimitedProcesses() throws Exception {
        String grader = resource("/common-testcase-engine/grader.dart");

        assertTrue(grader.contains("Process.start("),
                "grader phải dùng Process.start để có thể dừng process bị treo");
        assertTrue(grader.contains("GRADER_BATCH_SIZE"));
        assertTrue(grader.contains("GRADER_BATCH_TIMEOUT_SECONDS"));
        assertTrue(grader.contains("GRADER_TOTAL_TIMEOUT_SECONDS"));
        assertTrue(grader.contains("GRADER_PREFLIGHT_TIMEOUT_SECONDS"));
        assertTrue(grader.contains("STUDENT_APP_BOOT_TIMEOUT"));
        assertTrue(grader.contains("TESTCASE_EXECUTION_TIMEOUT"));
        assertTrue(grader.contains("kStageMarker"));
        assertTrue(grader.contains("GRADER_CASE_MODE"));
        assertTrue(grader.contains("GRADER_CASE_IDS"));
        assertTrue(grader.contains("final sourceOnlyIds = <String>[];"),
                "source-contract thu?n ph?i ???c gom ri?ng ?? ti?t ki?m compile");
        assertTrue(grader.contains("final isolatedIds = <String>[];"),
                "scenario widget/behavior ph?i ???c c? l?p");
        assertTrue(grader.contains("batches.addAll(isolatedIds.map((id) => <String>[id]))"),
                "m?i scenario c? g?i app ph?i ch?y trong process ri?ng");
        assertTrue(grader.contains("metadata['runner']?.toString() == 'CUSTOM_CODE'"));
        assertTrue(grader.contains("sourceChecks.isNotEmpty"));
        assertTrue(grader.contains("--concurrency=1"));
        assertTrue(grader.contains("process.exitCode.timeout("));
        assertFalse(grader.contains("final process = await Process.run("),
                "không được quay lại chạy cả suite bằng một Process.run không timeout");
    }

    @Test
    void reusableValidationAndDeleteFlowsUseRobustLocators() throws Exception {
        String exam = resource("/common-testcase-engine/exam_test.dart");

        assertTrue(exam.contains("RegExp(r'^/(.*)/([imsu]*)$')"),
                "engine phải parse regex contract có flags như /.../i");
        assertTrue(exam.contains("on FormatException"),
                "regex contract hỏng không được làm crash runner");
        assertTrue(exam.contains("final labels = scoped(_textLike(value));"),
                "button_text phải scope label trong ancestor trước khi leo button");
        assertTrue(exam.contains("_buttonOrSelfWithin(labels, ancestor)"),
                "button finder phải giới hạn trong ancestor");
        assertTrue(exam.contains("exact.evaluate().length == 1"),
                "ValueKey action duy nhất phải được ưu tiên trước contract fallback");
        assertTrue(exam.contains("_reloadContract();"),
                "flow delete phải nạp lại contract trong process testcase");
        assertTrue(exam.contains("final role = _roleActionFinder(actionKey);"),
                "delete phải có fallback vai trò khi process không đọc được contract");
        assertTrue(exam.contains("hủy|huỷ"),
                "fallback nút hủy phải hỗ trợ cả hai cách đặt dấu tiếng Việt");
        assertTrue(exam.contains("_contractFinderWithin(rule, fieldFinder)"),
                "validation phải giới hạn contract trong đúng field");
        assertTrue(exam.contains("_fieldValidationError(fieldFinder)"),
                "validation phải fallback theo đúng field, không lấy nhầm lỗi field khác");
        assertTrue(exam.contains("_actionInside(itemKey, deleteKey)"),
                "delete phải scope action trong đúng item đã seed");
        assertTrue(exam.contains("_actionInside(dialogKey, cancelKey)"),
                "nút hủy phải nằm trong đúng dialog");
        assertTrue(exam.contains("_actionInside(dialogKey, confirmKey)"),
                "nút xác nhận phải nằm trong đúng dialog");
        assertTrue(exam.contains("_expectPresent(_byKey(itemKey), 'item'"),
                "nhánh hủy phải xác nhận item vẫn còn");
        assertTrue(exam.matches("(?s).*_expectGone\\(\\R\\s+_byKey\\(dialogKey\\).*"),
                "nhánh hủy phải xác nhận dialog đã đóng");
        assertTrue(exam.matches("(?s).*_expectGone\\(\\R\\s+_byKey\\(itemKey\\).*"),
                "nhánh xác nhận phải xác nhận đúng item biến mất");
    }

    @Test
    void examRegistersOnlyTheRequestedBatchInIsolatedMode() throws Exception {
        String exam = resource("/common-testcase-engine/exam_test.dart");

        assertTrue(exam.contains("Platform.environment['GRADER_CASE_MODE']"));
        assertTrue(exam.contains("Platform.environment['GRADER_CASE_IDS']"));
        assertTrue(exam.contains("!selectedBatch.contains(testId)"));
        assertTrue(exam.contains("STUDENT_APP_BOOT"));
        assertTrue(exam.contains("STUDENT_UI_ACTION"));
        assertTrue(exam.contains("TESTCASE_ASSERTION"));
        assertTrue(exam.contains("case 'action.delete.confirm':"));
        assertTrue(exam.contains("confirm( delete)?"));
    }

    @Test
    void chaptersSevenRunnersAreFullyImplemented() throws Exception {
        String exam = resource("/common-testcase-engine/exam_test.dart");

        assertTrue(exam.contains("case 'SCROLL_DIRECTION':"));
        assertTrue(exam.contains("case 'SCROLL_TO_END':"));
        assertTrue(exam.contains("case 'STACK_LAYERS':"));
        assertTrue(exam.contains("case 'INDEXED_STACK_SWITCH':"));
        assertTrue(exam.contains("case 'BOTTOM_SHEET_FLOW':"));
        assertTrue(exam.contains("case 'TABLE_ROWS':"));
        assertTrue(exam.contains("case 'SLIVER_SCROLL_COLLAPSE':"));
        assertTrue(exam.contains("case 'EXPANDED_WIDGET':"));

        // Kiểm tra logic Expanded
        assertTrue(exam.contains("find.byType(Expanded"));
        assertTrue(exam.contains("widget is Row || widget is Column || widget is Flex"));

        // Kiểm tra cell content check của Table
        assertTrue(exam.contains("expectedCellsRaw.split('|')"));
        assertTrue(exam.contains("tableRow.children.length"));
    }

    @Test
    void readCheckpointRequiresProofThatSubmissionQueriedTheTable() throws Exception {
        String exam = resource("/behavior-replay-engine/exam_test.dart");

        // Bo cham chep hidden.db vao app.db TRUOC khi mo app, nen checkpoint
        // `database_observation` + `operation: READ` dung san ke ca voi bai chua tung
        // cham SQLite. Do 22/9/2026 tren lo PE_PRM393_FA26: 4 bai co repository rong
        // hoan toan van an tron 7.5/100 diem cua ba luong validate. Ba khang dinh duoi
        // day la ba manh cua cai cong da bit lo do; mat manh nao la lo mo lai.

        // 1. Bien toan cuc `databaseFactory` — thu duy nhat bai lam cham toi — phai di
        //    qua lop boc co dem, khong duoc gan thang factory ffi.
        assertTrue(exam.contains("databaseFactory = _factorySqlCoDem();"),
                "bai lam phai chay qua factory co dem thi moi biet no co doc bang khong");
        assertFalse(exam.contains("databaseFactory = databaseFactoryFfiNoIsolate;"),
                "gan thang factory ffi la bo dem khong thay gi, checkpoint READ lai dung san");

        // 2. Engine tu truy cap DB thi phai goi THANG factory ffi, neu khong chinh cau
        //    SELECT cua engine se tu lam chung cho bai lam.
        assertTrue(exam.contains("databaseFactoryFfiNoIsolate.openDatabase(path)"));
        assertTrue(exam.contains("_ghiNhanSqlBaiLam(arguments)"));

        // 3. Cong chi ap cho READ (INSERT/UPDATE/DELETE tu chung minh bang du lieu doi),
        //    va nguoi soan de tat duoc cho luong khong di qua man nao doc bang.
        assertTrue(exam.contains("operation == 'READ'"));
        assertTrue(exam.contains("_bool(checkpoint['require_student_read'], true)"));
        assertTrue(exam.contains("!_bangBaiLamDaDoc.contains(table.toLowerCase())"));
    }

    private String resource(String path) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "Không tìm thấy resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
