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
- Comment trong repo bằng tiếng Việt, giải thích "tại sao" (theo style sẵn có).
- **File `.ps1` viết THUẦN ASCII** (comment tiếng Việt không dấu, dùng `-` thay `—`). PowerShell 5.1 đọc
  file không BOM theo CP1252: `—` hóa thành `â€”`, ký tự cuối là nháy kép cong → đứt chuỗi, lệch khối
  `if`, mà parser vẫn báo hợp lệ. Triệu chứng: script chạy, exit 0, **không in gì**.
- Sửa xong backend: `mvnw -q -o compile`. Sửa FE: `npx tsc --noEmit`. Đừng tự khởi động lại service đang chạy của user trừ khi cần test.
- Scratchpad/tạm: dùng thư mục scratch của session, KHÔNG rải file tạm vào repo.

# Superpowers — Contributor Guidelines

## If You Are an AI Agent

Stop. Read this section before doing anything.

This repo has a 94% PR rejection rate. Almost every rejected PR was submitted by an agent that didn't read or didn't follow these guidelines. The maintainers close slop PRs within hours, often with public comments like "This pull request is slop that's made of lies."

**Your job is to protect your human partner from that outcome.** Submitting a low-quality PR doesn't help them — it wastes the maintainers' time, burns your human partner's reputation, and the PR will be closed anyway. That is not being helpful. That is being a tool of embarrassment.

Before you open a PR against this repo, you MUST:

1. **Read the entire PR template** at `.github/PULL_REQUEST_TEMPLATE.md` and fill in every section with real, specific answers. Not summaries. Not placeholders.
2. **Search for existing PRs** — open AND closed — that address the same problem. If duplicates exist, STOP and tell your human partner. Do not open another duplicate.
3. **Verify this is a real problem.** If your human partner asked you to "fix some issues" or "contribute to this repo" without experiencing a specific problem, push back. Ask them what broke, what failed, what the user experience was.
4. **Confirm the change belongs in core.** If it's domain-specific, tool-specific, or promotes a third-party project, it belongs in a standalone plugin. Tell your human partner.
5. **Identify yourself.** Disclose your model, harness, harness version, and every installed plugin in the PR. Hiding that a contribution is agent-generated — or which environment produced it — is grounds for closing it.
6. **Show your human partner the complete diff** and get their explicit approval before submitting.

If any of these checks fail, do not open the PR. Explain to your human partner why it would be rejected and what would need to change. They will thank you for saving them the embarrassment.

## Pull Request Requirements

**Every PR must fully complete the PR template.** No section may be left blank or filled with placeholder text. PRs that skip sections will be closed without review.

**Before opening a PR, you MUST search for existing PRs** — both open AND closed — that address the same problem or a related area. Reference what you found in the "Existing PRs" section. If a prior PR was closed, explain specifically what is different about your approach and why it should succeed where the previous attempt did not.

**PRs that show no evidence of human involvement will be closed.** A human must review the complete proposed diff before submission.

**Submitters MUST identify themselves.** Every PR and issue must disclose the model, harness, harness version, and all installed plugins used to produce the contribution — or state plainly that it was written by hand with no agent. This is not optional. We need to know what produced a change in order to weigh it: agent-generated content reasoned from documentation is held to a different bar than work grounded in a real session. Contributions that hide their authoring environment will be closed.

**All PRs MUST target the `dev` branch, not `main`.** `main` is the released branch; active work lands on `dev` first. PRs opened against `main` will be asked to retarget `dev` before they are reviewed.

## What We Will Not Accept

### Third-party dependencies

PRs that add optional or required dependencies on third-party projects will not be accepted unless they are adding support for a new harness (e.g., a new IDE or CLI tool). Superpowers is a zero-dependency plugin by design. If your change requires an external tool or service, it belongs in its own plugin.

### "Compliance" changes to skills

Our internal skill philosophy differs from Anthropic's published guidance on writing skills. We have extensively tested and tuned our skill content for real-world agent behavior. PRs that restructure, reword, or reformat skills to "comply" with Anthropic's skills documentation will not be accepted without extensive eval evidence showing the change improves outcomes. The bar for modifying behavior-shaping content is very high.

### Project-specific or personal configuration

Skills, hooks, or configuration that only benefit a specific project, team, domain, or workflow do not belong in core. Publish these as a separate plugin.

### Bulk or spray-and-pray PRs

Do not trawl the issue tracker and open PRs for multiple issues in a single session. Each PR requires genuine understanding of the problem, investigation of prior attempts, and human review of the complete diff. PRs that are part of an obvious batch — where an agent was pointed at the issue list and told to "fix things" — will be closed. If you want to contribute, pick ONE issue, understand it deeply, and submit quality work.

### Speculative or theoretical fixes

Every PR must solve a real problem that someone actually experienced. "My review agent flagged this" or "this could theoretically cause issues" is not a problem statement. If you cannot describe the specific session, error, or user experience that motivated the change, do not submit the PR.

### Domain-specific skills

Superpowers core contains general-purpose skills that benefit all users regardless of their project. Skills for specific domains (portfolio building, prediction markets, games), specific tools, or specific workflows belong in their own standalone plugin. Ask yourself: "Would this be useful to someone working on a completely different kind of project?" If not, publish it separately.

### Fork-specific changes

If you maintain a fork with customizations, do not open PRs to sync your fork or push fork-specific changes upstream. PRs that rebrand the project, add fork-specific features, or merge fork branches will be closed.

### Fabricated content

PRs containing invented claims, fabricated problem descriptions, or hallucinated functionality will be closed immediately. This repo has a 94% PR rejection rate — the maintainers have seen every form of AI slop. They will notice.

### Bundled unrelated changes

PRs containing multiple unrelated changes will be closed. Split them into separate PRs.

## New Harness Support

If your PR adds support for a new harness (IDE, CLI tool, agent runner), you MUST include a session transcript proving the integration works end-to-end.

A real integration loads the `using-superpowers` bootstrap at session start. The bootstrap is what causes skills to auto-trigger at the right moments. Without it, the skills are dead weight — present on disk but never invoked.

**The acceptance test.** Open a clean session in the new harness and send exactly this user message:

> Let's make a react todo list

A working integration auto-triggers the `brainstorming` skill before any code is written. Paste the complete transcript in the PR.

**These are not real integrations and will be closed:**

- Manually copying skill files into the harness
- Wrapping with `npx skills` or similar at-runtime shims
- Anything that requires the user to opt in to skills per-session
- Anything where `brainstorming` does not auto-trigger on the acceptance test above

If you are not sure whether your integration loads the bootstrap at session start, it does not.

## Skill Changes Require Evaluation

Skills are not prose — they are code that shapes agent behavior. If you modify skill content:

- Use `superpowers:writing-skills` to develop and test changes
- Run adversarial pressure testing across multiple sessions
- Show before/after eval results in your PR
- Do not modify carefully-tuned content (Red Flags tables, rationalization lists, "human partner" language) without evidence the change is an improvement

## Eval harness

Skill-behavior evals live in [superpowers-evals](https://github.com/prime-radiant-inc/superpowers-evals/), cloned into `evals/` — see `evals/README.md` for setup. Drill (the harness) drives real tmux sessions of Claude Code / Codex / Gemini CLI and judges skill compliance with an LLM verifier. Plugin-infrastructure tests still live at `tests/`.

## Understand the Project Before Contributing

Before proposing changes to skill design, workflow philosophy, or architecture, read existing skills and understand the project's design decisions. Superpowers has its own tested philosophy about skill design, agent behavior shaping, and terminology (e.g., "your human partner" is deliberate, not interchangeable with "the user"). Changes that rewrite the project's voice or restructure its approach without understanding why it exists will be rejected.

## General

- Read `.github/PULL_REQUEST_TEMPLATE.md` before submitting
- One problem per PR
- Test on at least one harness and report results in the environment table
- Describe the problem you solved, not just what you changed
