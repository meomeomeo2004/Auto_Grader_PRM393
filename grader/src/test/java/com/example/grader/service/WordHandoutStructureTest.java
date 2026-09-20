package com.example.grader.service;

import com.example.grader.repository.ExamRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WordHandoutStructureTest {
    @TempDir Path temp;
    private static final String NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String BODY = """
            <w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:t>ĐỀ THI QUẢN LÝ THƯ VIỆN</w:t></w:r></w:p>
            <w:p><w:r><w:t>Dòng đầu của đề</w:t><w:br/><w:t>Dòng tiếp theo</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Yêu cầu thứ nhất</w:t></w:r></w:p>
            <w:p><w:r><w:t>Mô tả giữa hai mục</w:t></w:r></w:p>
            <w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>Yêu cầu thứ hai</w:t></w:r></w:p>
            <w:tbl><w:tr><w:tc><w:p><w:r><w:t>Thành phần</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Giá trị</w:t></w:r></w:p></w:tc></w:tr>
            <w:tr><w:tc><w:p><w:r><w:t>Nút | Lưu</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Lưu sách</w:t><w:br/><w:t>Không để trống</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
            <w:p><w:pPr><w:jc w:val="right"/><w:ind w:left="300"/></w:pPr><w:r><w:t>Hết đề</w:t></w:r></w:p>
            """;

    private static byte[] word(String body) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            put(zip, "word/document.xml", "<w:document xmlns:w=\"" + NS + "\"><w:body>" + body + "</w:body></w:document>");
            put(zip, "word/numbering.xml", "<w:numbering xmlns:w=\"" + NS + "\"><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1.\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>");
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test void wordImportPreservesTableBreaksAlignmentAndNumbering() throws Exception {
        String md = (String) new ExamDocumentReader().read("de.docx", word(BODY)).get("text");
        String html = HandoutDocument.toHtml("DE", "Đề", md, List.of());
        assertTrue(html.contains("<table>"), html);
        assertTrue(html.contains("Nút | Lưu"), html);
        assertTrue(html.contains("Lưu sách<br>Không để trống"), html);
        assertTrue(html.contains("Dòng đầu của đề<br>Dòng tiếp theo"), html);
        assertTrue(html.contains("text-align:center"), html);
        assertTrue(html.contains("text-align:right"), html);
        assertTrue(html.contains("value=\"2\""), html);
        assertTrue(html.indexOf("Yêu cầu thứ hai") < html.indexOf("<table>"));
    }

    @Test void rendererKeepsExplicitNumbersAndLineBreaks() {
        String html = HandoutDocument.toHtml("DE", "Đề", "1. Mục đầu\nDòng A\nDòng B\n2. Mục hai\nMô tả\n4. Mục bốn", List.of());
        assertTrue(html.contains("value=\"2\""), html);
        assertTrue(html.contains("value=\"4\""), html);
        assertTrue(html.contains("Dòng A<br>Dòng B"), html);
    }

    @Test void legacyTextCanRecoverStructureWithoutRewritingOrLosingEdits() throws Exception {
        var service = new ExamService();
        var repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "examRepository", repo);
        ReflectionTestUtils.setField(service, "examsDir", temp.toString());
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("tmpl").toString());
        ReflectionTestUtils.setField(service, "documentReader", new ExamDocumentReader());
        Path dir = Files.createDirectories(temp.resolve("DE/handout"));
        String legacy = "ĐỀ THI QUẢN LÝ THƯ VIỆN\nDòng đầu của đềDòng tiếp theo\nYêu cầu thứ nhất\nMô tả giữa hai mục\nYêu cầu thứ hai\nThành phần\nGiá trị\nNút | Lưu\nLưu sáchKhông để trống\nHết đề";
        Files.write(dir.resolve("original.docx"), word(BODY));
        Files.writeString(dir.resolve("de_bai.md"), legacy);
        assertTrue(service.buildHandoutHtml("DE").contains("<table>"));
        assertEquals(legacy, Files.readString(dir.resolve("de_bai.md")));
        Files.writeString(dir.resolve("de_bai.md"), legacy + "\nGiảng viên đã sửa nội dung.");
        assertEquals(legacy + "\nGiảng viên đã sửa nội dung.", service.readDeBai("DE"));
    }

    /**
     * BẤT BIẾN của ô xem trước: nó phải dựng ra ĐÚNG cái mà bản lưu dựng ra.
     *
     * <p>Người soạn chỉ tin ô xem trước khi nó không nói dối. Hai đường — xem trước (nội dung
     * chưa lưu, đi qua {@code /de-bai/xem-truoc}) và bản chính thức ({@code buildHandoutHtml})
     * — phải gọi cùng một bộ dựng VỚI CÙNG tham số. Trước 20/9 đường xem trước truyền mã đề
     * vào chỗ tên đề, nên hai bản lệch nhau ngay thẻ h1.
     */
    @Test void xemTruocDungHtmlVoiBanLuu() throws Exception {
        var service = new ExamService();
        var repo = mock(ExamRepository.class);
        var exam = new com.example.grader.entity.Exam();
        exam.setExamId("DE");
        exam.setExamName("Quản lý thư viện");
        when(repo.findByExamId(anyString())).thenReturn(Optional.of(exam));
        ReflectionTestUtils.setField(service, "examRepository", repo);
        ReflectionTestUtils.setField(service, "examsDir", temp.toString());
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("tmpl").toString());

        String md = "# Mục lớn\n\n1. Mục đầu\n2. Mục hai\n\n| A | B |\n| --- | --- |\n| 1 | 2 |\n";
        Files.createDirectories(temp.resolve("DE/handout"));
        Files.writeString(temp.resolve("DE/handout/de_bai.md"), md);

        String banLuu = service.buildHandoutHtml("DE");
        String banXemTruoc = HandoutDocument.toHtml("DE", service.tenDeHienThi("DE"), md, List.of());

        assertEquals(banLuu, banXemTruoc, "xem trước và bản chính thức phải giống hệt nhau");
        assertTrue(banLuu.contains("Quản lý thư viện"), "phải in TÊN đề, không phải mã đề");
        assertTrue(banLuu.contains("value=\"2\"") && banLuu.contains("<table>"), banLuu);
    }

    @Test void docxExportKeepsBreaksAndNumbers() throws Exception {
        var service = new ExamService();
        var repo = mock(ExamRepository.class);
        when(repo.findByExamId(anyString())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(service, "examRepository", repo);
        ReflectionTestUtils.setField(service, "examsDir", temp.toString());
        ReflectionTestUtils.setField(service, "templateDir", temp.resolve("tmpl").toString());
        Path dir = Files.createDirectories(temp.resolve("DE/handout"));
        Files.writeString(dir.resolve("de_bai.md"), "1. Mục đầu\nDòng A\nDòng B\n2. Mục hai");
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(service.buildHandoutDocx("DE", List.of())))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals("word/document.xml")) continue;
                String xml = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(xml.contains("<w:br/>"), xml);
                assertTrue(xml.contains("2. "), xml);
                return;
            }
        }
        fail("Thiếu nội dung Word");
    }
}
