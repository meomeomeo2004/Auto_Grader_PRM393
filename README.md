# 🎓 Grader App — Hệ thống chấm thi Flutter tự động

Hệ thống chấm bài thi thực hành **Flutter/Dart** tự động trong môi trường **Docker cô lập**. Giáo viên upload hàng loạt bài nộp (file ZIP), hệ thống tự biên dịch, chạy testcase và trả về điểm + log chi tiết cho từng sinh viên.

> **Repo này là MỘT gói hoàn chỉnh**: backend (`grader/`) · frontend (`frontend/`) · script chạy (`run.cmd`, `start-all.ps1`) · bộ cài (`installer/`). Clone 1 repo là đủ.

---

## 🚀 Chạy nhanh — luồng cho người mới

**Cấu hình cần biết khi clone về:** repo chạy bằng Docker + Java + Node, không cần dịch vụ bên ngoài.

### Cách A — máy đã có sẵn Docker + Node + Java
```powershell
# Chạy TẤT CẢ bằng 1 lệnh (mở terminal tại thư mục Grader_App)
.\run                      # = run.cmd: bật MySQL + backend + frontend
```
Mở **http://localhost:3000** → dùng ngay (không cần đăng nhập).

### Cách B — máy TRỐNG (chưa có Docker/Node/Java)
Sau khi **clone repo**, chạy **`grader-setup.cmd`** (tự xin quyền admin → winget cài Docker/Node/Java + build ảnh chấm `grading-base`). Xong → chạy **`GraderLauncher.exe`** hoặc **`.\run`** ngay trong repo. Chi tiết: [`installer/README-INSTALLER.md`](installer/README-INSTALLER.md).

### Luồng sử dụng chính
1. **Cấu hình Đề thi** → upload ZIP testcase (`exam_test.dart`, `grader.dart`, `skills_matrix.json`).
2. **Chấm bài (Batch)** → nhập mã đề + chọn thư mục chứa các bài `username/<tên-bất-kỳ>.zip` → chấm và xem kết quả.

### Các file bị `.gitignore` có làm clone về không chạy được không?

Không. Những file bị ignore là dữ liệu phát sinh ở từng máy hoặc chứa secret cá nhân. Repo vẫn commit đầy đủ **file template** và script để tạo lại:

| File/thư mục bị ignore | Lý do không commit | Cách tạo lại sau khi clone |
|---|---|---|
| `frontend/.env.local` | URL backend theo cổng máy local | `.\run` / `start-all.ps1` tự ghi; chạy tay thì copy từ `frontend/.env.example` |
| `exams/` | Testcase giáo viên upload khi dùng app | Tự sinh khi cấu hình đề trên UI |
| `submissions/` | ZIP bài nộp sinh viên, dữ liệu nhạy cảm | Tự sinh khi chấm batch |
| `.idea/`, `.claude/`, `.codex/`, `.agents/` | Cấu hình IDE/agent và đường dẫn/quyền local | Không cần để chạy app |

Vì vậy người mới clone repo vẫn chạy được sau khi có Docker, Java và Node.

---

## ✨ Tính năng chính

| Nhóm | Tính năng |
|---|---|
| **Chấm bài** | Upload & chấm hàng loạt; hàng đợi 8 worker song song; mỗi bài chạy trong 1 container Docker riêng (`--rm`); tự giới hạn RAM/CPU/timeout |
| **Theo dõi** | Tiến độ real-time (queued / grading / done / error); **không mất kết quả khi rời trang** (tự khôi phục từ backend) |
| **Lịch sử chấm** | Xem lại theo từng đề: danh sách bài + điểm + trạng thái; tải JSON từng bài; **xuất CSV** |
| **Thống kê** | Tổng hợp pass/fail, điểm trung bình, biểu đồ; tối ưu O(log N) bằng Flag Pattern |
| **Truy cập** | Không có đăng nhập/phân quyền — công cụ chạy cục bộ cho một giáo viên; đừng expose cổng 8080 ra mạng |
| **Giao diện** | Sáng/Tối (dark mode); responsive; xuất CSV & JSON kết quả đầy đủ |

---

## 🏗️ Kiến trúc & Công nghệ

```
┌──────────────┐      REST API       ┌──────────────────┐      docker.sock      ┌─────────────────┐
│   Frontend   │ ─────────────────►  │     Backend      │ ───────────────────►  │  Docker Engine  │
│  Next.js 16  │  ◄───────────────   │  Spring Boot 4   │  ◄──── kết quả JSON   │ grading-base +  │
│  React 19    │                     │  (8 worker pool) │                       │  container/ bài │
└──────────────┘                     └────────┬─────────┘                       └─────────────────┘
                                              │ JPA/Hibernate
                                       ┌──────▼──────┐
                                       │  MySQL 8.0  │
                                       └─────────────┘
```

| Thành phần | Công nghệ |
|---|---|
| **Backend** | Spring Boot 4.0.6 · Java 17 · JPA/Hibernate · MySQL 8.0 |
| **Frontend** | Next.js 16.2.6 · React 19 · Tailwind CSS v4 · Recharts · lucide-react |
| **Chấm bài** | Docker · Flutter SDK (ảnh nền `grading-base`) |

---

## 📋 Yêu cầu hệ thống

- **Docker Desktop** (bật, chia sẻ ổ đĩa chứa project nếu trên Windows)
- **Java 17+** và **Maven** (hoặc dùng `mvnw` kèm sẵn)
- **Node.js 20+** và **npm**
- RAM khuyến nghị ≥ 8GB (mỗi container chấm mặc định 2GB)

---

## 🚀 Cài đặt & Chạy

### Bước 1 — Khởi động MySQL

```bash
docker compose up -d        # chạy MySQL 8.0 (cổng 3306) - không tạo sẵn database nào
```

### Bước 2 — Build ảnh nền chấm bài (chỉ 1 lần)

```bash
cd grader-base
# Windows
./build-base.ps1
# Linux/Mac
./build-base.sh
# hoặc thủ công:
docker build -f Dockerfile.base -t grading-base:latest .
```
> Ảnh `grading-base` gói sẵn Flutter SDK + package → các lần chấm sau chỉ mount testcase (nhanh vài giây). Xem chi tiết ở mục [Docker image & container](#-docker-image--container).

### Bước 3 — Chạy Backend (khuyến nghị chạy trên host)

```bash
cd grader
./mvnw spring-boot:run        # Windows: .\mvnw spring-boot:run
```
Backend chạy ở **http://localhost:8080**. Lần đầu sẽ tự seed 2 tài khoản mẫu (xem bên dưới).

### Bước 4 — Chạy Frontend

```bash
cd frontend
npm install
npm run dev
```
Mở **http://localhost:3000** → bắt đầu chấm.

Nếu chạy bằng `.\run` thì script tự tạo `frontend/.env.local` đúng cổng backend. Nếu chạy thủ công và muốn cấu hình riêng, copy:

```powershell
Copy-Item frontend/.env.example frontend/.env.local
notepad frontend/.env.local
```

> 💡 **Schema tự sinh**: không phải tạo database bằng tay. Mỗi vai dùng schema riêng (`chamthi_gv` / `chamthi_nc`, xem `vai-cau-hinh.ps1`) và chuỗi kết nối mang sẵn `createDatabaseIfNotExist=true`, nên lần chạy đầu backend tự dựng schema cùng toàn bộ bảng.

---

## 📖 Hướng dẫn sử dụng

### 1. Cấu hình đề thi (trang **Cấu hình Đề thi**)
Upload file ZIP testcase chứa: `exam_test.dart`, `grader.dart`, `skills_matrix.json`.

### 2. Chấm bài (trang **Chấm bài (Batch)**)
- Nhập **mã đề** → chọn hoặc kéo thả thư mục cha chứa các thư mục username.
- Mỗi bài nằm trong thư mục mang tên username và chứa một file **`.zip`** của thư mục `lib`; tên ZIP không bắt buộc (vd `khiempghe186137/bai_lam.zip`).
- Bấm **Bắt đầu chấm** → theo dõi tiến độ real-time.
- Rời trang rồi quay lại **vẫn còn kết quả** (tự tải lại từ server).
- Tải **CSV** (bảng điểm) hoặc xuất thư mục chứa **một JSON cho mỗi sinh viên**.

### 3. Xem lịch sử (trang **Lịch sử chấm**)
- Chọn đề ở cột trái → xem toàn bộ bài đã chấm (điểm, trạng thái, thời gian).
- Tìm theo mã SV / tên; tải **JSON** từng bài; **xuất CSV** cả đề.

### 4. Thống kê (trang **Thống kê**)
Chọn đề (hoặc tất cả) để xem pass/fail, điểm trung bình, phân bố điểm.

---

## ⚙️ Cấu hình (biến môi trường)

Tất cả có giá trị mặc định — chỉ ghi đè khi cần.

### Backend (`grader`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `SPRING_DATASOURCE_URL` | *không có mặc định* | Kết nối MySQL. Schema theo vai: `chamthi_gv` / `chamthi_nc`. Trình khởi động tự đặt biến này; thiếu nó là backend dừng ngay |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | `root` / `123456` | Tài khoản DB |
| `GRADER_MAX_CONCURRENT` | `8` | Số bài chấm song song (= số container đồng thời) |
| `GRADER_TIMEOUT_SECONDS` | `240` | Timeout mỗi bài (giây) |
| `GRADER_RUN_MEMORY` | `2048m` | RAM mỗi container chấm |
| `GRADER_RUN_CPUS` | `2.0` | Số CPU mỗi container chấm |
| `GRADER_BASE_IMAGE` | `grading-base:latest` | Tên ảnh nền |
| `GRADER_SAVE_SUBMISSIONS` | `true` | Lưu file ZIP để audit/chấm lại |
| `GRADER_SUBMISSIONS_RETENTION_DAYS` | `30` | Số ngày giữ bài nộp (≤0 = giữ mãi) |
| `GRADER_PASS_THRESHOLD` | `5` | Ngưỡng điểm đạt (hệ 10) |

> ⚠️ **Máy yếu/ít RAM**: giảm `GRADER_MAX_CONCURRENT` và/hoặc `GRADER_RUN_MEMORY`.
> Tối đa RAM ước tính = `MAX_CONCURRENT × RUN_MEMORY` (mặc định 8 × 2GB = 16GB).

### Frontend (`frontend/.env.local`)

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `NEXT_PUBLIC_API_BASE` | `http://localhost:8080/api` | URL backend |
| `NEXT_PUBLIC_DEFAULT_EXAM_ID` | `FLUTTER_PE_01` | Mã đề gợi ý sẵn |
| `NEXT_PUBLIC_PASS_THRESHOLD` | `5` | Ngưỡng điểm đạt |

---

## 🔌 API chính

| Method | Endpoint | Mô tả |
|---|---|---|
| `POST` | `/api/auth/login` · `/register` · `/logout` · `/me` | Xác thực giáo viên |
| `POST` | `/api/exam-setup/upload-testcase` | Cấu hình đề (upload testcase) |
| `GET` | `/api/exam-setup/status/{examId}` | Trạng thái đề |
| `POST` | `/api/batch/upload` | Upload & chấm hàng loạt |
| `GET` | `/api/batch/progress/{batchId}` | Tiến độ 1 phiên chấm |
| `GET` | `/api/results/exam/{examId}` | **Lịch sử chấm theo đề** (danh sách bài) |
| `GET` | `/api/results/{examId}/{studentId}` | JSON đầy đủ 1 bài |
| `GET` | `/api/results/batch/{batchId}` | JSON đầy đủ cả phiên chấm |
| `GET` | `/api/statistics` · `/statistics/exams` | Thống kê & danh sách đề đã chấm |

---

## 🐳 Docker image & container

### Ảnh nền `grading-base` (build 1 lần)
`grader-base/Dockerfile.base` — gói sẵn Flutter SDK, package, script chấm. Mọi đề thi dùng chung ảnh này, **không** build ảnh riêng cho từng đề → upload nhanh, không tích tụ image.

### Container chấm (mỗi bài 1 container, tự xóa)
Khi chấm 1 bài, backend chạy:
```bash
docker run --rm --memory 2048m --cpus 2.0 \
  -v <bài-nộp>/lib:/app/lib \      # code sinh viên
  -v <testcase>:/app/test \         # testcase của đề (mount mode)
  grading-base:latest
```
→ container chạy `run_grader.sh` (flutter test) → trả JSON kết quả → tự xóa (`--rm`).

**Lợi ích**: cô lập (lỗi 1 bài không ảnh hưởng bài khác) · nhanh · sạch · kiểm soát tài nguyên.

---

## 📁 Cấu trúc thư mục

```
Grader_App/
├── grader/                 # Backend Spring Boot
│   └── src/main/java/com/example/grader/
│       ├── controller/     # REST API (Auth, Batch, Result, ExamSetup, Statistics)
│       ├── service/        # BatchGradingService (hàng đợi), GradingService (docker), AuthService
│       ├── entity/         # Exam, ExamResult, GradingBatch, Teacher
│       └── repository/     # JPA repositories
├── frontend/               # Next.js
│   └── app/
│       ├── page.jsx        # Chấm bài (dashboard)
│       ├── history/        # Lịch sử chấm
│       ├── statistics/     # Thống kê
│       ├── teacher/        # Cấu hình đề, Kho đề, Thư viện chấm
│       ├── login·register·profile/
│       └── components/     # SidebarLayout, AuthProvider
├── grader-base/            # Dockerfile.base + script chấm + pubspec base
├── dong-goi.ps1            # Cắt repo thành hai bản theo vai (gv / nc)
├── vai-cau-hinh.ps1        # Cổng và schema của từng vai - một nguồn sự thật duy nhất
└── docker-compose.yml      # Chỉ MySQL
```

`exams/` và `submissions/` không nằm ở gốc repo nữa: chúng là **dữ liệu của từng bản chạy**, sinh ra trong `dist/gv/` hoặc `dist/nc/` khi bạn xuất bản đề và chấm bài.

---

## 🔧 Xử lý sự cố thường gặp

| Triệu chứng | Nguyên nhân & cách xử lý |
|---|---|
| Chấm bị kẹt `QUEUED` mãi | Đã fix (worker không chết). Nếu còn → restart backend, `recoverPendingJobs` tự nạp lại hàng đợi |
| `image not found: grading-base` | Chưa build ảnh nền → chạy `grader-base/build-base.ps1` |
| Maven báo `No compiler is provided` | Máy đang dùng JRE, thiếu JDK. Chạy lại `grader-setup.cmd`; script sẽ cài/tìm JDK 17+ và set `JAVA_HOME` cho backend |
| Docker build báo `failed to compute cache key` / `input/output error` | Lỗi storage/cache của Docker Desktop/WSL hoặc ổ Docker thiếu dung lượng. Chạy lại `grader-setup.cmd`; `build-base.ps1` sẽ prune cache, retry `--no-cache`, restart Docker/WSL rồi retry lần cuối |
| Bài báo `0/0 — không chạy được testcase` | Bài nộp sai tên class/thiếu file so với đề, hoặc lỗi biên dịch |
| `Mỗi file .zip phải đi kèm đúng tên thư mục username` | Chọn thư mục cha chứa các nhánh `username/<tên-bất-kỳ>.zip` |
| Hết RAM khi chấm nhiều | Giảm `GRADER_MAX_CONCURRENT` hoặc `GRADER_RUN_MEMORY` |
| Mất kết nối DB | Kiểm tra `docker compose ps`, cổng 3306, biến `SPRING_DATASOURCE_*` |

---

## 📝 Ghi chú

- Backend **khuyến nghị chạy trên host** (không trong container) vì cần mount đường dẫn host thật vào Docker để chấm.
- Bài nộp được lưu tại `submissions/` để audit & chấm lại; tự dọn sau `RETENTION_DAYS` ngày.
- Chấm lại cùng (SV + đề) sẽ **ghi đè** kết quả cũ.
