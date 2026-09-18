# AGENTS.md — Auto-Grader V2 (chấm thi thực hành Flutter)

Hướng dẫn cho agent làm việc trong repo này. Đọc hết file trước khi mở code — phần lớn
những gì dưới đây KHÔNG suy ra được từ việc đọc codebase, và mỗi Gotcha đều đã gây lỗi thật.

## 1. Hệ thống làm gì

Chấm tự động bài thi thực hành (PE) môn Flutter. Giảng viên soạn một **bộ chấm Golden** từ
chính bài giải mẫu của mình; người chấm nhận bộ đó rồi chạy hàng loạt bài sinh viên qua nó.

Toàn bộ dự án chỉ có **hai mục tiêu**, mọi đánh đổi kỹ thuật phải quy về được một trong hai:

1. Máy chấm đúng với những gì sinh viên thật sự làm.
2. Điểm máy ngang điểm người.

Không có đăng nhập, không có phân quyền runtime. Chạy localhost. Đừng thêm code đọc token
hay `@RequestAttribute("teacherEmail")` — bảng `teachers` đã bị gỡ từ lâu.

## 2. Hai bản, hai vai — KHÔNG phải một hệ thống làm cả hai việc

Một repo, một cờ vai, script `dong-goi.ps1` cắt ra hai thư mục độc lập trong `dist/`.

| Vai | Backend | Frontend | Schema MySQL | Giữ chức năng |
|---|---|---|---|---|
| `gv` (giảng viên) | 8090 | 3100 | `chamthi_gv` | Tạo đề · Tạo Golden · Bộ chấm Golden · Kho tài liệu đề · Thư viện chấm |
| `nc` (người chấm) | 8080 | 3000 | `chamthi_nc` | Chấm tự động · Lịch sử chấm · Quản lý bộ testcase · Thư viện chấm |

Nguồn sự thật duy nhất cho bảng này: `vai-cau-hinh.ps1`. Cả `dong-goi.ps1` lẫn `start-all.ps1`
dot-source nó. Đừng khai lại cổng ở chỗ khác.

**Vai được cài ở ba chỗ, phải khớp nhau:**

- `GRADER_ROLE=gv|nc` → `spring.profiles.active` (`application.yml:12`). **Không có giá trị
  mặc định** và đó là cố ý. `config/VaiBatBuoc.java` ném lỗi lúc khởi động nếu thiếu vai.
- `@Profile(Vai.GIANG_VIEN|Vai.NGUOI_CHAM)` trên controller. Không nạp thì `/api/...` trả
  **404 thật** — không phải ẩn nút trên giao diện.
- `NEXT_PUBLIC_ROLE` → `frontend/lib/vai.ts`. Next.js **nướng biến này vào lúc build**, nên
  đổi vai phải xoá `.next` (start-all.ps1 tự làm).

| Controller | Profile | Mount |
|---|---|---|
| `AiAuthorController` | gv | `/api/ai` |
| `BehaviorAuthoringController` | gv | `/api/behavior-authoring` |
| `ExamSetupController` | gv | `/api/exam-setup` |
| `BatchController` | nc | `/api/batch` |
| `ResultController` | nc | `/api/results` |
| `GradingRuntimeController` | nc | `/api/grading-runtime` |
| `ExamCatalogController` | *(cả hai)* | `/api/exam-setup` |
| `GradingEnvController` | *(cả hai)* | `/api/grading-env` |

**Hai vai không gọi nhau, không dùng chung schema, không dùng chung thư mục.** Kênh liên lạc
duy nhất là **gói bàn giao `.zip`** giảng viên xuất ra rồi gửi cho người chấm. Nếu bạn định
viết code cho phép bên này đọc dữ liệu của bên kia — dừng lại, đó là logic của hệ thống cũ.

## 3. Cây repo

```
Grader_App_Fix/
├── grader/            Backend Spring Boot 4 · Java 17 · MySQL · gọi Docker để chấm
│   └── src/main/resources/behavior-replay-engine/exam_test.dart   ← "engine": ~4300 dòng Dart
├── frontend/          Next.js 16 · React 19 · Tailwind v4   (ĐỌC frontend/AGENTS.md trước khi sửa)
├── grader-base/       Dockerfile ảnh nền chấm (Flutter SDK) → grading-base:<nhãn>
├── installer/         setup-prereqs.ps1 (cài Docker/Node/Java trên máy trống)
├── dist/gv · dist/nc  SẢN PHẨM của dong-goi.ps1 — không sửa tay, sẽ bị ghi đè
├── fixtures/          dữ liệu mẫu cho test
├── vai-cau-hinh.ps1   bảng cổng/schema của từng vai
├── dong-goi.ps1       cắt repo thành dist/gv và dist/nc
└── run.cmd · start-all.ps1 · GraderLauncher.exe
```

`exams/`, `submissions/`, `grader/behavior-artifacts/`, `grader/golden-runtimes/` là thư mục
**runtime**, không nằm trong repo. Chúng sinh ra bên cạnh nơi chạy (`dist/gv/...`).

## 4. Chạy & build

```powershell
.\run -Vai gv              # hoặc -Vai nc. Không khai vai thì script từ chối và nhắc.
cd grader; .\mvnw.cmd -q -o compile        # compile backend (offline, deps đã cache)
cd grader; .\mvnw.cmd -o test              # test backend
cd frontend; npx tsc --noEmit              # typecheck FE
powershell -ExecutionPolicy Bypass -File .\dong-goi.ps1    # cắt lại dist/ (phải đóng cả hai bản trước)
```

Backend phải chạy **trên host**, không trong container — nó gọi `docker run` để chấm.

## 5. Hai luồng cốt lõi

### A. Giảng viên soạn bộ chấm (vai `gv`)

```
Upload Golden Solution (.zip lib/)
  → deploy runtime web            GoldenRuntimeService  (build Flutter web + TIÊM JS bridge ghi hình)
  → giảng viên bấm thử trên app   bridge gửi event về /api/behavior-authoring/recordings/{id}/events
  → trừu tượng hoá                POST /recordings/{id}/abstract  → scenario + checkpoint
  → capture oracle                GoldenOracleCaptureService (chạy Docker trên chính Golden, đo số chuẩn)
  → publish                       BehaviorSuiteMaterializer → thư mục testcase + skills_matrix.json
  → Kiểm tra Golden               GoldenValidationService (chạy nguyên bộ trên Golden, phải đạt)
  → Xuất gói bàn giao             GET /api/exam-setup/{examId}/xuat-goi   (BanGiaoService)
```

`BanGiaoService.xuatGoi` **từ chối** nếu đề chưa qua kiểm đồng bộ khung phát — đó là nơi cuối
cùng còn phán được, vì bên nhận không có Golden để tự kiểm.

### B. Người chấm chấm bài (vai `nc`)

```
Nạp gói bàn giao   POST /api/exam-setup/nhap-goi   (trả kèm danh sách package ảnh chấm còn thiếu)
  → upload ZIP bài nộp
  → GradingService / BatchGradingService  →  docker run grading-base  →  chạy engine trên bài
  → result_json (LONGTEXT trong DB)  →  trang Lịch sử  GET /api/results/exam/{examId}
```

`result_json` dựng ở `BatchGradingService.assembleResultJson`. Một dòng tiêu chí = một phần tử
trong `test_cases[]`.

## 6. Từ điển (đọc cái này trước khi đọc code)

- **Golden Solution** — bài giải mẫu của giảng viên. Mọi số chuẩn đều đo từ nó.
- **suite** → **scenario** → **checkpoint**. Suite gắn với một đề. Scenario là một luồng thao
  tác (`ADD`, `EDIT`, `SORT`...). Checkpoint là một tiêu chí chấm, có `weight`.
- **oracle** — giá trị chuẩn (vị trí, màu, chuỗi) đo tự động trên Golden lúc capture.
- **plan / testcase** — JSON đã publish, là thứ engine đọc lúc chấm.
- **engine** — `behavior-replay-engine/exam_test.dart`. Chạy trong container, đọc plan, điều
  khiển app bằng `flutter_test`, in kết quả từng checkpoint.
- **hidden.db vs app.db** — `hidden.db` là dữ liệu chấm, KHÔNG được lọt vào tài liệu phát cho
  sinh viên. Soạn Golden phải nạp `hidden.db`, nạp nhầm `app.db` là soạn mù.
- **execution_code** — checkpoint chung mã thì chung một lần chạy app; chung mã là chung số phận.
- **`requires`** — checkpoint tiên quyết. Tiên quyết trượt thì checkpoint phụ thuộc bị hạ xuống
  trượt dù tự nó đạt.

## 7. Hợp đồng định danh (Semantics identifier)

Đề phát cho sinh viên kèm một file `dinh_danh.dart` khai hằng số. Sinh viên bọc widget trong
`Semantics(identifier: ...)`. Đây là hợp đồng chấm.

- **`identifier` KHÔNG phải `ValueKey`.** Engine tìm `semantic_id` bằng
  `find.bySemanticsIdentifier`, không phải `find.byKey`. Đừng "sửa" lại.
- Thứ tự tìm trong `_finder()` (`exam_test.dart`): `semanticId` → `valueKey` → `icon`/`image`
  → `label`/`hint` → `text` → `text_prefix`.
- **`label` so khớp TUYỆT ĐỐI với nhãn của nút ngữ nghĩa.** Một dòng `ListTile` có nhãn hai
  dòng `"tiêu đề\nphụ đề"`, nên khai `label: "tiêu đề"` sẽ tìm ra **0** widget. Tiêu chí bố
  cục / Chapter 7 phải khai bằng `semantic_id`.
- Recorder trên web có leo lên node cha để lấy identifier (`GoldenRuntimeService.identifierOf`),
  vì `Semantics(identifier:)` bọc quanh `ChoiceChip`/`IconButton`/`FAB` sinh ra DOM hai tầng:
  tầng mang định danh không có role, tầng có role không mang định danh. Đường leo dừng lại khi
  gặp cha mang aria-label khác. Đừng gỡ nó nếu chưa thay bằng `MergeSemantics` ở Golden.

## 8. Gotchas (mỗi cái đã tốn thời gian thật)

1. **Hai phiên bản Jackson cùng classpath.** Services dùng `com.fasterxml.jackson` (2), vài
   controller dùng `tools.jackson` (3, mặc định Spring Boot 4). Theo file xung quanh.
2. **Phải có Docker bật và đúng nhãn ảnh.** Nhãn hiện tại: `grading-base:2026-08-22e`
   (`application.yml:86`). Ảnh ~7 GB, build lại rất lâu — đừng build lại khi chỉ cần thêm
   package; màn "Thư viện chấm" `docker commit` đè lên chính nhãn đó.
3. **`NEXT_PUBLIC_*` nướng vào lúc build.** Đổi vai mà không xoá `.next` là giao diện lên
   nhưng gọi sai cổng API — loại lỗi mất nhiều thời gian nhất để tìm.
4. **File `.ps1` phải THUẦN ASCII.** Em-dash hoặc nháy cong làm đứt chuỗi, script im lặng
   exit 0 mà không làm gì.
5. **File `.cmd` phải CRLF** (`.gitattributes` đã ép). LF làm vỡ khối `for`/`if (` với thông
   báo `. was unexpected at this time`. Trong `.cmd`, dấu `)` trong chuỗi phải escape thành `^)`.
6. **Surefire chỉ thấy class `*Test` ở mức top-level.** Class test lồng tĩnh
   (`Outer$Inner.class`) bị bỏ qua **im lặng** — số test không đổi, tưởng là đã chạy.
7. **MySQL 8: cột `datetime(6)` phải mặc định `CURRENT_TIMESTAMP(6)`.** Sai thì Hibernate tạo
   **không** bảng nào mà app vẫn báo khởi động thành công.
8. **`find.bySemanticsIdentifier(String)` so khớp tuyệt đối, `RegExp` thì `hasMatch`.** Dùng
   RegExp phải neo `^...$`, không thì `chi_tieu.dong.` nuốt luôn `chi_tieu.dong.3.xoa`.
9. **`component_color` lấy pixel xuất hiện nhiều nhất trong khung**, không phải màu widget.
   Trên chữ mảnh nó thường ra màu viền khử răng cưa. Ngưỡng 5% = 13/255 mỗi kênh.
10. **Sửa engine thì phải publish lại bộ đề.** Bộ đã publish mang một bản sao engine trong
    thư mục testcase, và `BanGiaoService` ghi vân tay SHA-256 của `exam_test.dart` vào tờ khai.
11. **`dong-goi.ps1` phải giữ `node_modules` và `target`.** Nó move-aside/restore chứ không
    dựa vào `robocopy /XD`; mọi lệnh robocopy phải có `/XJ` vì mặc định robocopy đi xuyên junction.
12. **Đừng đóng gói khi một vai đang chạy.** Script tự kiểm cổng và từ chối — dọn thư mục
    trong lúc tiến trình Java chạy từ đó sẽ giết nó giữa chừng.

## 9. Quy ước khi sửa code

- **Comment tiếng Việt**, súc tích, giải thích **"tại sao"** chứ không mô tả lại code. Theo
  đúng style dày đặc sẵn có — đó là nơi chứa lý do của những quyết định đã đo.
- **Không tự commit, không thêm dòng `Co-Authored-By`.** Người dùng tự commit. "Viết commit
  message đi" = xin nội dung, không phải bảo chạy `git commit`.
- Sửa backend xong: `mvnw -q -o compile`. Sửa FE xong: `npx tsc --noEmit`.
- **Không kết luận nếu chưa kiểm tra.** Đo rồi hãy nói. Đừng suy từ code ra hành vi runtime
  khi có thể chạy thử.
- File tạm/script nháp để ở thư mục scratch của session, **không rải vào repo**.
- Đừng tự khởi động lại service người dùng đang chạy.
- Chỉ `DE_BAI.docx` mới tính là đề bài. Người dùng không đọc `.md` — đừng dẫn số dòng file `.md`.

## 10. Trạng thái tại 17/9/2026 (nhánh `chien`)

- **Khung năng lực (syllabus / skill / skill_category) đã gỡ hẳn.** Gotcha cũ "skill_code phải
  có trong bảng `skill`" **không còn đúng**. Ba bảng `skill`, `skill_category`, `syllabus_meta`
  vẫn còn trong các schema tạo trước đó — Hibernate không xoá bảng. Không code nào đọc chúng
  nữa, cứ để đó.
- Bộ đề mẫu đang chạy được đầu-cuối: `PE_PRM393_FA26` (Quản lý chi tiêu cá nhân) đã publish
  trên bản `gv`, kiểm Golden 79/79. Đây là bộ tốt nhất để đối chiếu khi cần biết một trường
  trong plan trông như thế nào trong thực tế.
- Còn nhiều thay đổi chưa commit trong working tree. Kiểm `git status` trước khi sửa.
