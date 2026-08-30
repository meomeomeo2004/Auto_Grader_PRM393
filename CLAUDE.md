# CLAUDE.md — Grader App (chấm thi Flutter)

Hướng dẫn cho Claude Code khi làm việc trong repo này. Đọc kỹ phần **Gotchas** — nhiều thứ
khác với mặc định và đã từng gây lỗi thật.

> 🚩 **ĐANG CÓ VIỆC DỞ DANG — đọc [`docs/result-json-v2-plan.md`](docs/result-json-v2-plan.md)
> trước khi làm bất cứ gì liên quan tới `result.json`, engine chấm, hay bộ fixture.**
> Mục **"BẮT ĐẦU TỪ ĐÂY"** ở đầu file đó nói: đang ở đâu · việc kế tiếp · quyết định đã chốt ·
> luật làm việc · và bài học đã lặp 5 lần. Có **phía thứ hai** (bot NLP,
> `D:\AGS-PRM393\prm393-feedback-bot`) ăn output của repo này — trao đổi hai chiều qua
> `D:\AGS-PRM393\SPEC_grader_result_json\CHANGELOG_FOR_{NLP,GRADER}.md`.

## Repo này là MỘT gói hoàn chỉnh
```
Grader_App/                  ← repo duy nhất (clone 1 cái là đủ)
├── grader/        Backend Spring Boot 4 · Java 17 · MySQL · gọi Docker để chấm
├── frontend/      Next.js 16 · React 19 · Tailwind v4  (ĐỌC frontend/AGENTS.md trước khi sửa FE)
├── grader-base/   Dockerfile ảnh nền chấm (Flutter SDK) → image `grading-base:latest`
├── exams/  submissions/   dữ liệu runtime (gitignore, rỗng khi mới clone)
├── grader-setup.cmd  ← MÁY TRỐNG: cài Docker/Node/Java + build ảnh (gọi installer/setup-prereqs.ps1, tự UAC)
├── run.cmd · start-all.ps1 · GraderLauncher.exe  ← chạy tất cả (chạy NGAY trong repo, không nhân bản)
└── installer/     setup-prereqs.ps1 (cài thành phần nền) · README-INSTALLER.md
```

## Chạy & build
- **Chạy tất cả:** `run` (cmd) hoặc `.\run` (PowerShell) tại thư mục Grader_App → MySQL + backend(:8080) + frontend(:3000).
- **Backend only:** `cd grader && .\mvnw.cmd spring-boot:run` (cổng khác: `$env:SERVER_PORT=8090`). Phải chạy **trên host**, không trong container (cần mount docker.sock host để chấm).
- **Compile backend:** `cd grader && .\mvnw.cmd -q -o compile` (offline, deps đã cache).
- **Frontend:** `cd frontend && npm run dev`.
- **Không có đăng nhập:** mở `http://localhost:3000` là dùng được ngay (xem Gotcha 4).

## Luồng dữ liệu cốt lõi
```
Upload ZIP bài nộp → grade trong Docker (grading-base) → result_json lưu DB (LONGTEXT)
result_json = ĐÚNG shape cho API (student/exam/grading_result/test_cases/competency/...)
Trang "Lịch sử" → GET /api/exam/{examId}/results
```
- Backend chỉ nói chuyện `/api` (cổng 8080).
- `result_json` dựng ở `BatchGradingService.assembleResultJson`. Endpoint đọc: `ResultController`.

## Gotchas (đã gây lỗi thật — đừng lặp lại)
1. **Hai phiên bản Jackson cùng classpath.** `com.fasterxml.jackson` (Jackson 2) và `tools.jackson` (Jackson 3, mặc định Spring Boot 4) đều có. **Chia theo TỪNG FILE, không theo tầng** — luôn kiểm import của chính file đang sửa: `SyllabusService` = Jackson 2, còn `BatchGradingService` = **Jackson 3** (`TypeReference` ở `tools.jackson.core.type`).
2. **skill_code phải có trong syllabus (bảng `skill`).** Upload testcase validate nghiêm (`ExamService.validateSkillCodes` ném lỗi) để giữ taxonomy sạch.
3. **Cần Docker bật + ảnh nền đúng NHÃN GHIM** (`grader.base-image` trong `application.yml`, hiện là
   `grading-base:2026-08-22e`) — không phải `latest`. Đường chấm bài tự build khi thiếu, nhưng đường
   soạn đề (recorder/capture/validate) gọi `docker run` thẳng nên thiếu nhãn là chết ngay.
   `grader-base/build-base.ps1` build `latest` rồi gắn thêm nhãn ghim đọc từ `application.yml`
   (`-TagOnly` để chỉ gắn nhãn, `-RetagPinned` để đè nhãn đang trỏ ảnh khác).
4. **KHÔNG có xác thực.** Đăng nhập/đăng ký/role/bảng `teachers` đã bị gỡ bỏ hoàn toàn — mọi `/api/**` đều mở, đừng thêm code đọc token hay `@RequestAttribute("teacherEmail")`. Các cột audit (`created_by`, `manual_by`) vẫn còn và được điền bằng `AppActor.DEFAULT`. Hệ quả: **chỉ chạy localhost**, đừng expose cổng 8080 ra mạng.

## Quy ước khi sửa code
- Comment trong repo bằng tiếng Việt, súc tích, giải thích "tại sao" (theo style sẵn có).
- **File `.ps1` viết THUẦN ASCII** (comment tiếng Việt không dấu, dùng `-` thay `—`). PowerShell 5.1 đọc
  file không BOM theo CP1252: `—` hóa thành `â€”`, ký tự cuối là nháy kép cong → đứt chuỗi, lệch khối
  `if`, mà parser vẫn báo hợp lệ. Triệu chứng: script chạy, exit 0, **không in gì**.
- Sửa xong backend: `mvnw -q -o compile`. Sửa FE: `npx tsc --noEmit`. Đừng tự khởi động lại service đang chạy của user trừ khi cần test.
- Scratchpad/tạm: dùng thư mục scratch của session, KHÔNG rải file tạm vào repo.
