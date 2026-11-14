package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.*;
import com.hito.genai.jiraclaude.RepositoryQualityModels.DatabasePipelineResult;
import com.hito.genai.jiraclaude.RepositoryQualityModels.EnvironmentPipelineResult;
import com.hito.genai.jiraclaude.RepositoryQualityModels.RepoQualityRow;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Mengisi template "Pre Deployment Checklist" (xlsx) dengan data report.
 *
 * Tanpa library tambahan: file xlsx adalah zip berisi XML, dan hanya isi sel yang diganti.
 * Style dan struktur template dipertahankan; status pipeline Database dan Repo Impacted diisi otomatis.
 * Kolom Database yang bertanda em dash tetap disediakan untuk pengisian manual.
 */
@Component
public class ChecklistExcelExporter {

    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String HYPERLINK_TYPE = R_NS + "/hyperlink";

    private final ResourceLoader loader;
    private final JiraClaudeProperties props;

    public ChecklistExcelExporter(ResourceLoader loader, JiraClaudeProperties props) {
        this.loader = loader;
        this.props = props;
    }

    public byte[] export(Report report) throws IOException {
        return export(report, List.of());
    }

    public byte[] export(Report report, List<RepoQualityRow> qualityRows) throws IOException {
        Map<String, RepoQualityRow> sit = new HashMap<>();
        if (qualityRows != null) for (RepoQualityRow row : qualityRows) sit.put(RepoCoverageWhitelistService.normalize(row.repository()), row);
        return export(report, sit, Map.of(), Map.of());
    }

    public byte[] export(Report report, Map<String, RepoQualityRow> sitRows,
                         Map<String, RepoQualityRow> uatRows, Map<String, RepoQualityRow> prodRows) throws IOException {
        return export(report, sitRows, uatRows, prodRows, DatabasePipelineResult.empty());
    }

    public byte[] export(Report report, Map<String, RepoQualityRow> sitRows,
                         Map<String, RepoQualityRow> uatRows, Map<String, RepoQualityRow> prodRows,
                         DatabasePipelineResult databasePipelines) throws IOException {
        Map<String, byte[]> files = readTemplate();
        Map<String, String> sheetPaths = sheetPaths(files);
        // Excel sheet names cannot contain '/', so the export tab uses "Bugs Development - UAT".
        addWorksheetClone(files, sheetPaths, "All Ticket", "Bugs Development - UAT");
        sheetPaths = sheetPaths(files);
        ExcelStyleManager excelStyles = new ExcelStyleManager(parse(files.get("xl/styles.xml")));
        DatabasePipelineResult effectiveDatabasePipelines = databasePipelines == null
                ? DatabasePipelineResult.empty() : databasePipelines;

        fillAllTicket(open(files, sheetPaths, "All Ticket"), files, report.tickets());
        fillAllTicket(open(files, sheetPaths, "Bugs Development - UAT"), files, report.bugsDevelopmentUat());
        fillDevOps(open(files, sheetPaths, "DevOps Ticket"), files, report.devops());
        fillDdl(open(files, sheetPaths, "DDL Ticket"), files, report.ddl(), effectiveDatabasePipelines, excelStyles);
        fillDatabaseRelated(open(files, sheetPaths, "Database Related"), files, report.db(),
                effectiveDatabasePipelines, excelStyles);
        fillHotfix(open(files, sheetPaths, "Hotfix OR Sanity Tickets"), files, report.hotfix());
        fillRepos(open(files, sheetPaths, "Repo Impacted"), files, report.repos(),
                sitRows == null ? Map.of() : sitRows, uatRows == null ? Map.of() : uatRows,
                prodRows == null ? Map.of() : prodRows, excelStyles);
        files.put("xl/styles.xml", serialize(excelStyles.document()));

        // rentang filter All Ticket pada workbook.xml ikut disesuaikan
        String wb = new String(files.get("xl/workbook.xml"), StandardCharsets.UTF_8);
        wb = wb.replaceAll("('All Ticket'!\\$A\\$1:\\$AA\\$)\\d+", "$1" + (report.tickets().size() + 1));
        files.put("xl/workbook.xml", wb.getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ pengisian per sheet

    private void fillAllTicket(Sheet s, Map<String, byte[]> files, List<Ticket> tickets) {
        s.captureRefStyles(2);
        // All Ticket dan Bugs Development / UAT mengikuti urutan kolom UI.
        s.setRefStyle(5, 4); // Summary uses the template's wrapped-text body style.
        s.setRefStyle(14, 15);
        s.setRefStyle(14, 16);
        s.setRefStyle(14, 17);
        s.setColumnWidth(4, 60.0);
        s.setColumnWidth(10, 30.0);
        s.setColumnWidth(15, 20.0);
        s.setColumnWidth(16, 10.0);
        s.setColumnWidth(17, 34.0);
        s.clear(2, s.lastRow(), 1, 27);

        String[] headers = {
                "No", "Issue Type", "Issue key", "Summary", "Using PRDA API?", "Parent key",
                "Parent summary", "Assignee", "Reporter", "Sprint", "Status", "Priority",
                "Resolution", "Created", "Updated", "SP", "Labels"
        };
        String headerStyle = s.styleAt(1, 14);
        for (int col = 1; col <= headers.length; col++) {
            s.setText(1, col, headers[col - 1], headerStyle);
        }

        for (int i = 0; i < tickets.size(); i++) {
            Ticket t = tickets.get(i);
            int r = i + 2;
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, t.issueType());
            s.setText(r, 3, t.key());
            s.link(r, 3, t.link());
            s.setText(r, 4, t.summary());
            s.setText(r, 5, t.usingPrdaApi());
            s.setText(r, 6, t.parentKey());
            s.setText(r, 7, t.parentSummary());
            s.setText(r, 8, t.assignee());
            s.setText(r, 9, t.reporter());
            s.setText(r, 10, t.sprints() == null ? "" : String.join(", ", t.sprints()));
            s.setText(r, 11, t.status());
            s.setText(r, 12, t.priority());
            s.setText(r, 13, t.resolution());
            s.setText(r, 14, t.created());
            s.setText(r, 15, t.updated());
            if (t.storyPoints() != null) {
                s.setNumber(r, 16, t.storyPoints());
            } else {
                s.setText(r, 16, "");
            }
            s.setText(r, 17, t.labels() == null ? "" : String.join(", ", t.labels()));
        }
        Element af = s.firstChild("autoFilter");
        if (af != null) {
            af.setAttribute("ref", "A1:Q" + (tickets.size() + 1));
        }
        s.finish(files);
    }

    private void fillDevOps(Sheet s, Map<String, byte[]> files, Map<String, List<DevOpsRow>> byEnv) {
        s.captureRefStyles(3);
        s.clear(3, s.lastRow(), 1, 26);
        int[] baseCol = {1, 10, 19}; // A (SIT), J (UAT), S (PROD)
        String[] envs = {"SIT", "UAT", "PROD"};
        for (int b = 0; b < envs.length; b++) {
            List<DevOpsRow> rows = byEnv.getOrDefault(envs[b], List.of());
            for (int i = 0; i < rows.size(); i++) {
                DevOpsRow d = rows.get(i);
                int r = 3 + i;
                int c = baseCol[b];
                s.setNumber(r, c, i + 1);
                s.setText(r, c + 1, d.label());
                s.link(r, c + 1, d.link());
                s.setText(r, c + 2, d.repository());
                s.setText(r, c + 3, d.epicKey());
                s.setText(r, c + 4, d.type());
                s.setText(r, c + 5, d.note());
                s.setText(r, c + 6, "");
                s.setText(r, c + 7, d.status());
            }
        }
        s.finish(files);
    }

    private void fillDdl(Sheet s, Map<String, byte[]> files, List<DdlRow> rows,
                         DatabasePipelineResult databasePipelines, ExcelStyleManager excelStyles) {
        // Baris 2 pada sebagian template merupakan placeholder dengan font strikethrough.
        // Gunakan baris 3 sebagai referensi style data normal agar nilai DDL tidak tercoret.
        s.captureRefStyles(3);
        s.clear(2, s.lastRow(), 1, 12);
        for (int i = 0; i < rows.size(); i++) {
            int r = 2 + i;
            DdlRow d = rows.get(i);
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, d.label());
            s.link(r, 2, d.link());
            s.setText(r, 3, databaseBranchDisplay(d.sitBranch(), d.uatBranch(), d.prodBranch(),
                    databasePipelines));
            s.setText(r, 4, d.prSummary().isBlank() ? "—" : d.prSummary());
            s.setText(r, 5, "—");
            s.setText(r, 6, "—");
            writeDatabasePipelineStatus(s, r, 7, databasePipelines.sit(), excelStyles);
            writeDatabasePipelineStatus(s, r, 8, databasePipelines.uat(), excelStyles);
            s.setText(r, 9, "—");
            writeDatabasePipelineStatus(s, r, 10, databasePipelines.prod(), excelStyles);
            s.setText(r, 11, "—");
            s.setText(r, 12, "—");
        }
        s.removeRowsAfter(rows.size() + 1);
        s.finish(files);
    }

    /**
     * Mengisi Database Related seperti UI. Branch berasal dari Jira Development untuk
     * backendapi-ddl, sedangkan status SIT/UAT/PROD berasal dari pipeline Testing Bitbucket.
     */
    private void fillDatabaseRelated(Sheet s, Map<String, byte[]> files, List<DatabaseRow> rows,
                                     DatabasePipelineResult databasePipelines, ExcelStyleManager excelStyles) {
        s.captureRefStyles(3);
        s.setRefStyle(2, 2);
        for (int col = 3; col <= 12; col++) {
            s.setRefStyle(3, col);
        }
        s.clear(1, s.lastRow(), 1, 27);
        s.removeFirstChild("dataValidations");
        s.replaceMerges(List.of());

        String[] headers = {
                "No", "Ticket", "Bitbucket Branch", "Note", "Collections", "Note",
                "Status SIT", "Status UAT", "Already Check/Improve", "Status Prod",
                "Rollback Database", "Assets Folder"
        };
        String defaultHeaderStyle = s.styleAt(1, 2);
        String numberHeaderStyle = s.styleAt(1, 1);
        for (int col = 1; col <= headers.length; col++) {
            s.setText(1, col, headers[col - 1], col == 1 ? numberHeaderStyle : defaultHeaderStyle);
        }

        for (int i = 0; i < rows.size(); i++) {
            int r = i + 2;
            DatabaseRow row = rows.get(i);
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, row.label());
            s.link(r, 2, row.link());
            s.setText(r, 3, databaseBranchDisplay(row.sitBranch(), row.uatBranch(), row.prodBranch(), databasePipelines));
            s.setText(r, 4, "—");
            s.setText(r, 5, "—");
            s.setText(r, 6, "—");
            writeDatabasePipelineStatus(s, r, 7, databasePipelines.sit(), excelStyles);
            writeDatabasePipelineStatus(s, r, 8, databasePipelines.uat(), excelStyles);
            s.setText(r, 9, "—");
            writeDatabasePipelineStatus(s, r, 10, databasePipelines.prod(), excelStyles);
            s.setText(r, 11, "—");
            s.setText(r, 12, "—");
        }

        s.setColumnWidths(7, 28, 30, 18, 24, 18, 16, 16, 25, 16, 24, 22);
        s.removeRowsAfter(rows.size() + 1);
        Element autoFilter = s.firstChild("autoFilter");
        if (autoFilter != null) {
            autoFilter.setAttribute("ref", "A1:L" + Math.max(1, rows.size() + 1));
        }
        s.finish(files);
    }

    private static String databaseBranchDisplay(String sitBranch, String uatBranch, String prodBranch,
                                                DatabasePipelineResult pipelines) {
        String sit = pipelines.sit() != null && pipelines.sit().branch() != null && !pipelines.sit().branch().isBlank()
                ? pipelines.sit().branch() : sitBranch;
        String uat = pipelines.uat() != null && pipelines.uat().branch() != null && !pipelines.uat().branch().isBlank()
                ? pipelines.uat().branch() : uatBranch;
        String prod = pipelines.prod() != null && pipelines.prod().branch() != null && !pipelines.prod().branch().isBlank()
                ? pipelines.prod().branch() : prodBranch;
        return "SIT: " + emptyDash(sit) + " | UAT: " + emptyDash(uat) + " | PROD: " + emptyDash(prod);
    }

    private static String emptyDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static void writeDatabasePipelineStatus(Sheet sheet, int row, int column,
                                                    EnvironmentPipelineResult pipeline,
                                                    ExcelStyleManager excelStyles) {
        if (pipeline == null) {
            sheet.setText(row, column, "—");
            return;
        }
        String status = pipeline.displayStatus();
        String style = sheet.baseStyle(column);
        if (pipeline.isSuccess()) {
            style = excelStyles.style(style, "DCFCE7", "166534");
        } else if (pipeline.isFailed()) {
            style = excelStyles.style(style, "FEE2E2", "991B1B");
        } else if (pipeline.isRunning()) {
            style = excelStyles.style(style, "FEF3C7", "92400E");
        }
        sheet.setText(row, column, status, style);
        sheet.link(row, column, pipeline.url());
    }

    private void fillHotfix(Sheet s, Map<String, byte[]> files, List<HotfixRow> rows) {
        s.captureRefStyles(2);
        s.clear(2, s.lastRow(), 1, 2);
        for (int i = 0; i < rows.size(); i++) {
            int r = 2 + i;
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, rows.get(i).label());
            s.link(r, 2, rows.get(i).link());
        }
        s.finish(files);
    }

    private void fillRepos(Sheet s, Map<String, byte[]> files, List<RepoRow> rows,
                           Map<String, RepoQualityRow> sitRows, Map<String, RepoQualityRow> uatRows,
                           Map<String, RepoQualityRow> prodRows, ExcelStyleManager excelStyles) {
        // The previous exporter wrote 27 columns into a 9-column template. Rebuild this sheet
        // as the same 15-column, two-level table rendered by the Repo Impacted UI tab.
        s.captureRefStyles(3);
        s.setRefStyle(6, 9);
        s.setRefStyle(6, 10);
        s.setRefStyle(6, 11);
        s.setRefStyle(6, 12);
        s.setRefStyle(6, 13);
        s.setRefStyle(6, 14);
        s.setRefStyle(9, 15);
        s.clear(1, 2, 1, 27);
        s.clear(3, s.lastRow(), 1, 27);
        s.removeFirstChild("dataValidations");
        setRepoImpactedHeaders(s);
        s.setColumnWidths(6, 30, 18, 18, 12, 22, 18, 18, 12, 22, 18, 18, 12, 22, 28);

        for (int i = 0; i < rows.size(); i++) {
            int r = 3 + i;
            RepoRow row = rows.get(i);
            s.setNumber(r, 1, i + 1);               // A: No
            s.setText(r, 2, row.name());             // B: Repository Related
            String key = RepoCoverageWhitelistService.normalize(row.name());
            writeEnvironmentQuality(s, r, 3, sitRows.get(key), excelStyles);   // C:F SIT
            writeEnvironmentQuality(s, r, 7, uatRows.get(key), excelStyles);   // G:J UAT
            writeEnvironmentQuality(s, r, 11, prodRows.get(key), excelStyles); // K:N PROD

            s.setText(r, 15, row.prToMainLabel());
            if (!row.prsToMain().isEmpty()) {
                s.link(r, 15, row.prToMainUrl());
            }
        }
        s.removeRowsAfter(2 + rows.size());
        s.finish(files);
    }

    private static void setRepoImpactedHeaders(Sheet s) {
        String noStyle = s.styleAt(1, 1);
        String repoStyle = s.styleAt(1, 2);
        String groupStyle = s.styleAt(1, 3);
        String pullRequestStyle = s.styleAt(1, 9);
        String subHeaderStyle = s.styleAt(2, 3);
        String fixedHeaderStyle = s.styleAt(2, 1);

        s.setText(1, 1, "No", noStyle);
        s.setText(1, 2, "Repository Related", repoStyle);
        s.setText(1, 3, "SIT · Sonar Quality", groupStyle);
        s.setText(1, 7, "UAT · Sonar Quality", groupStyle);
        s.setText(1, 11, "PROD · Sonar Quality", groupStyle);
        s.setText(1, 15, "Pull Request To Main", pullRequestStyle);

        String[] subHeaders = {"Branch", "Quality Gate", "Coverage", "Status Pipeline"};
        for (int env = 0; env < 3; env++) {
            int first = 3 + (env * 4);
            for (int col = 0; col < 4; col++) {
                s.setText(2, first + col, subHeaders[col], subHeaderStyle);
            }
        }
        // Keep the header style on the two vertically merged columns.
        s.setText(2, 1, "", fixedHeaderStyle);
        s.setText(2, 2, "", fixedHeaderStyle);
        s.setText(2, 15, "", fixedHeaderStyle);
        s.replaceMerges(List.of("A1:A2", "B1:B2", "C1:F1", "G1:J1", "K1:N1", "O1:O2"));
    }

    private static void writeEnvironmentQuality(Sheet s, int row, int firstCol, RepoQualityRow quality,
                                               ExcelStyleManager excelStyles) {
        if (quality == null) {
            for (int col = firstCol; col < firstCol + 4; col++) s.setText(row, col, "");
            return;
        }
        s.setText(row, firstCol, quality.branch());

        String gateStyle = excelStyles.style(s.baseStyle(firstCol + 1), "F3F4F6", "374151");
        String gate = normalizeStatus(quality.qualityGateStatus());
        if ("ERROR".equals(gate)) {
            gateStyle = excelStyles.style(s.baseStyle(firstCol + 1), "FEE2E2", "991B1B");
        } else if ("NOT_WHITELISTED".equals(gate)) {
            gateStyle = excelStyles.style(s.baseStyle(firstCol + 1), "E5E7EB", "374151");
        } else if ("UNAVAILABLE".equals(gate)) {
            gateStyle = excelStyles.style(s.baseStyle(firstCol + 1), "FFEDD5", "9A3412");
        } else if ("OK".equals(gate)) {
            gateStyle = excelStyles.style(s.baseStyle(firstCol + 1), "DCFCE7", "166534");
        }
        s.setText(row, firstCol + 1, quality.qualityGateStatus(), gateStyle);

        String coverageStyle = excelStyles.style(s.baseStyle(firstCol + 2), "F3F4F6", "374151");
        if (quality.isCoverageBelow80()) {
            coverageStyle = excelStyles.style(s.baseStyle(firstCol + 2), "FEE2E2", "991B1B");
        } else if (quality.isCoverageAbove80()) {
            coverageStyle = excelStyles.style(s.baseStyle(firstCol + 2), "DCFCE7", "166534");
        }
        s.setText(row, firstCol + 2, quality.coveragePercent(), coverageStyle);

        String pipelineStyle = excelStyles.style(s.baseStyle(firstCol + 3), "F3F4F6", "374151");
        String pipeline = normalizeStatus(quality.pipelineStatus());
        if ("BITBUCKET_DISABLED".equals(pipeline)) {
            pipelineStyle = excelStyles.style(s.baseStyle(firstCol + 3), "E5E7EB", "374151");
        } else if ("NOT_WHITELISTED".equals(pipeline)) {
            pipelineStyle = excelStyles.style(s.baseStyle(firstCol + 3), "FEF3C7", "92400E");
        }
        s.setText(row, firstCol + 3, quality.pipelineLabel(), pipelineStyle);
        s.link(row, firstCol + 3, quality.pipelineUrl());
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ paket xlsx

    private Map<String, byte[]> readTemplate() throws IOException {
        Resource res = loader.getResource(props.getTemplate());
        if (!res.exists()) {
            throw new IllegalStateException("Template Excel tidak ditemukan: " + props.getTemplate());
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (InputStream in = res.getInputStream(); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (!e.isDirectory()) {
                    files.put(e.getName(), zip.readAllBytes());
                }
            }
        }
        return files;
    }

    /** nama sheet -> path file XML (mis. "All Ticket" -> xl/worksheets/sheet6.xml). */
    private Map<String, String> sheetPaths(Map<String, byte[]> files) {
        Document wb = parse(files.get("xl/workbook.xml"));
        Document rels = parse(files.get("xl/_rels/workbook.xml.rels"));
        Map<String, String> targetById = new HashMap<>();
        NodeList rl = rels.getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < rl.getLength(); i++) {
            Element e = (Element) rl.item(i);
            targetById.put(e.getAttribute("Id"), e.getAttribute("Target"));
        }
        Map<String, String> out = new HashMap<>();
        NodeList sl = wb.getElementsByTagNameNS("*", "sheet");
        for (int i = 0; i < sl.getLength(); i++) {
            Element e = (Element) sl.item(i);
            String target = targetById.get(e.getAttributeNS(R_NS, "id"));
            if (target != null) {
                out.put(e.getAttribute("name"), "xl/" + target.replaceFirst("^/?(xl/)?", ""));
            }
        }
        return out;
    }

    /** Clone the All Ticket template worksheet and register it as a new Excel tab. */
    private void addWorksheetClone(Map<String, byte[]> files, Map<String, String> paths,
                                   String sourceName, String newName) {
        if (paths.containsKey(newName)) return;
        String sourcePath = paths.get(sourceName);
        if (sourcePath == null || !files.containsKey(sourcePath)) {
            throw new IllegalStateException("Tidak dapat menggandakan sheet template " + sourceName);
        }
        int nextSheetNumber = files.keySet().stream()
                .filter(k -> k.matches("xl/worksheets/sheet\\d+\\.xml"))
                .mapToInt(k -> Integer.parseInt(k.replaceAll(".*sheet(\\d+)\\.xml", "$1")))
                .max().orElse(0) + 1;
        String newPath = "xl/worksheets/sheet" + nextSheetNumber + ".xml";
        files.put(newPath, files.get(sourcePath).clone());

        Document workbook = parse(files.get("xl/workbook.xml"));
        Document relationships = parse(files.get("xl/_rels/workbook.xml.rels"));
        Element sheets = firstElement(workbook, "sheets");
        int nextSheetId = 1;
        NodeList sheetNodes = workbook.getElementsByTagNameNS("*", "sheet");
        for (int i = 0; i < sheetNodes.getLength(); i++) {
            Element e = (Element) sheetNodes.item(i);
            try { nextSheetId = Math.max(nextSheetId, Integer.parseInt(e.getAttribute("sheetId")) + 1); }
            catch (NumberFormatException ignored) { }
        }
        int nextRel = 1;
        NodeList relNodes = relationships.getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < relNodes.getLength(); i++) {
            String id = ((Element) relNodes.item(i)).getAttribute("Id");
            if (id.matches("rId\\d+")) {
                try { nextRel = Math.max(nextRel, Integer.parseInt(id.substring(3)) + 1); }
                catch (NumberFormatException ignored) { }
            }
        }
        String relId = "rId" + nextRel;
        Element sheet = workbook.createElementNS(NS, "sheet");
        sheet.setAttribute("name", newName);
        sheet.setAttribute("sheetId", Integer.toString(nextSheetId));
        sheet.setAttributeNS(R_NS, "r:id", relId);
        sheets.appendChild(sheet);

        Element rel = relationships.createElementNS(PKG_REL_NS, "Relationship");
        rel.setAttribute("Id", relId);
        rel.setAttribute("Type", R_NS + "/worksheet");
        rel.setAttribute("Target", "worksheets/sheet" + nextSheetNumber + ".xml");
        relationships.getDocumentElement().appendChild(rel);
        files.put("xl/workbook.xml", serialize(workbook));
        files.put("xl/_rels/workbook.xml.rels", serialize(relationships));

        Document contentTypes = parse(files.get("[Content_Types].xml"));
        Element override = contentTypes.createElementNS("http://schemas.openxmlformats.org/package/2006/content-types", "Override");
        override.setAttribute("PartName", "/" + newPath);
        override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml");
        contentTypes.getDocumentElement().appendChild(override);
        files.put("[Content_Types].xml", serialize(contentTypes));
        updateExtendedProperties(files, newName);
    }

    private static Element firstElement(Document document, String name) {
        NodeList nodes = document.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static Element firstElement(Element parent, String name) {
        if (parent == null) return null;
        NodeList nodes = parent.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static void updateExtendedProperties(Map<String, byte[]> files, String newSheetName) {
        byte[] data = files.get("docProps/app.xml");
        if (data == null) return;
        Document app = parse(data);
        NodeList titleVectors = app.getElementsByTagNameNS("*", "TitlesOfParts");
        if (titleVectors.getLength() > 0) {
            Element vector = firstElement((Element) titleVectors.item(0), "vector");
            if (vector != null) {
                vector.appendChild(app.createElementNS("http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes", "vt:lpstr"));
                Element last = (Element) vector.getLastChild();
                last.setTextContent(newSheetName);
                vector.setAttribute("size", Integer.toString(Integer.parseInt(vector.getAttribute("size")) + 1));
            }
        }
        NodeList pairs = app.getElementsByTagNameNS("*", "HeadingPairs");
        if (pairs.getLength() > 0) {
            NodeList counts = ((Element) pairs.item(0)).getElementsByTagNameNS("*", "i4");
            if (counts.getLength() > 0) {
                Element count = (Element) counts.item(0);
                try { count.setTextContent(Integer.toString(Integer.parseInt(count.getTextContent()) + 1)); }
                catch (NumberFormatException ignored) { }
            }
        }
        files.put("docProps/app.xml", serialize(app));
    }

    private Sheet open(Map<String, byte[]> files, Map<String, String> sheetPaths, String name) {
        String path = sheetPaths.get(name);
        if (path == null || !files.containsKey(path)) {
            throw new IllegalStateException("Template tidak memiliki sheet '" + name + "'");
        }
        return new Sheet(path, parse(files.get(path)));
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalStateException("Gagal membaca XML template: " + e.getMessage(), e);
        }
    }

    private static byte[] serialize(Document doc) {
        try {
            doc.setXmlStandalone(true);
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Gagal menulis XML: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Excel status styles

    /** Adds status-specific fills/fonts while preserving each template cell's border/alignment. */
    private static final class ExcelStyleManager {
        private final Document document;
        private final Element fonts;
        private final Element fills;
        private final Element cellXfs;
        private final Map<String, String> styleCache = new HashMap<>();
        private final Map<String, Integer> fillCache = new HashMap<>();
        private final Map<String, Integer> fontCache = new HashMap<>();

        ExcelStyleManager(Document document) {
            this.document = document;
            this.fonts = directChild(document.getDocumentElement(), "fonts");
            this.fills = directChild(document.getDocumentElement(), "fills");
            this.cellXfs = directChild(document.getDocumentElement(), "cellXfs");
            if (fonts == null || fills == null || cellXfs == null) {
                throw new IllegalStateException("Template Excel tidak memiliki fonts/fills/cellXfs yang valid.");
            }
        }

        Document document() { return document; }

        String style(String baseStyle, String backgroundHex, String foregroundHex) {
            int baseIndex;
            try {
                baseIndex = Integer.parseInt(baseStyle == null || baseStyle.isBlank() ? "0" : baseStyle);
            } catch (NumberFormatException ex) {
                baseIndex = 0;
            }
            String key = baseIndex + "|" + backgroundHex + "|" + foregroundHex;
            String cached = styleCache.get(key);
            if (cached != null) return cached;

            Element baseXf = elementAt(cellXfs, baseIndex);
            if (baseXf == null) baseXf = elementAt(cellXfs, 0);
            int baseFontId = parseInt(baseXf.getAttribute("fontId"), 0);
            int newFontId = coloredFont(baseFontId, foregroundHex);
            int newFillId = solidFill(backgroundHex);

            Element xf = (Element) baseXf.cloneNode(true);
            xf.setAttribute("fontId", Integer.toString(newFontId));
            xf.setAttribute("fillId", Integer.toString(newFillId));
            xf.setAttribute("applyFont", "1");
            xf.setAttribute("applyFill", "1");
            cellXfs.appendChild(xf);
            int newStyleId = elementCount(cellXfs) - 1;
            cellXfs.setAttribute("count", Integer.toString(elementCount(cellXfs)));
            String result = Integer.toString(newStyleId);
            styleCache.put(key, result);
            return result;
        }

        private int solidFill(String hex) {
            Integer cached = fillCache.get(hex);
            if (cached != null) return cached;
            Element fill = document.createElementNS(NS, "fill");
            Element pattern = document.createElementNS(NS, "patternFill");
            pattern.setAttribute("patternType", "solid");
            Element fg = document.createElementNS(NS, "fgColor");
            fg.setAttribute("rgb", "FF" + hex);
            Element bg = document.createElementNS(NS, "bgColor");
            bg.setAttribute("indexed", "64");
            pattern.appendChild(fg);
            pattern.appendChild(bg);
            fill.appendChild(pattern);
            fills.appendChild(fill);
            int id = elementCount(fills) - 1;
            fills.setAttribute("count", Integer.toString(elementCount(fills)));
            fillCache.put(hex, id);
            return id;
        }

        private int coloredFont(int baseFontId, String hex) {
            String key = baseFontId + "|" + hex;
            Integer cached = fontCache.get(key);
            if (cached != null) return cached;
            Element baseFont = elementAt(fonts, baseFontId);
            if (baseFont == null) baseFont = elementAt(fonts, 0);
            Element font = (Element) baseFont.cloneNode(true);
            Element color = directChild(font, "color");
            if (color == null) {
                color = document.createElementNS(NS, "color");
                Element size = directChild(font, "sz");
                if (size != null) font.insertBefore(color, size.getNextSibling());
                else font.appendChild(color);
            }
            color.removeAttribute("theme");
            color.removeAttribute("indexed");
            color.removeAttribute("auto");
            color.removeAttribute("tint");
            color.setAttribute("rgb", "FF" + hex);
            fonts.appendChild(font);
            int id = elementCount(fonts) - 1;
            fonts.setAttribute("count", Integer.toString(elementCount(fonts)));
            fontCache.put(key, id);
            return id;
        }

        private static Element directChild(Element parent, String localName) {
            if (parent == null) return null;
            NodeList nodes = parent.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element e && localName.equals(e.getLocalName())) return e;
            }
            return null;
        }

        private static Element elementAt(Element parent, int index) {
            if (parent == null || index < 0) return null;
            int current = 0;
            NodeList nodes = parent.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) {
                Node node = nodes.item(i);
                if (node instanceof Element e) {
                    if (current++ == index) return e;
                }
            }
            return null;
        }

        private static int elementCount(Element parent) {
            int count = 0;
            NodeList nodes = parent.getChildNodes();
            for (int i = 0; i < nodes.getLength(); i++) if (nodes.item(i) instanceof Element) count++;
            return count;
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
        }
    }

    // ------------------------------------------------------------------ editor sheet

    private static final class Sheet {
        private final String path;
        private final Document doc;
        private final Element sheetData;
        private final TreeMap<Integer, Element> rows = new TreeMap<>();
        private final Map<Integer, String> refStyles = new HashMap<>();
        private final List<String[]> links = new ArrayList<>();

        Sheet(String path, Document doc) {
            this.path = path;
            this.doc = doc;
            this.sheetData = firstChild("sheetData");
            NodeList nl = sheetData.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element e && e.getLocalName().equals("row")) {
                    rows.put(Integer.parseInt(e.getAttribute("r")), e);
                }
            }
        }

        Element firstChild(String localName) {
            NodeList nl = doc.getDocumentElement().getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element e && e.getLocalName().equals(localName)) {
                    return e;
                }
            }
            return null;
        }

        int lastRow() {
            return rows.isEmpty() ? 1 : rows.lastKey();
        }

        void removeRowsAfter(int lastKeptRow) {
            List<Integer> remove = new ArrayList<>(rows.tailMap(lastKeptRow, false).keySet());
            for (Integer rowNumber : remove) {
                Element row = rows.remove(rowNumber);
                if (row != null) sheetData.removeChild(row);
            }
        }

        void captureRefStyles(int refRow) {
            Element row = rows.get(refRow);
            if (row == null) {
                return;
            }
            for (Element c : cells(row)) {
                String s = c.getAttribute("s");
                if (!s.isEmpty()) {
                    refStyles.put(colIndex(c.getAttribute("r")), s);
                }
            }
        }

        String styleAt(int rowNumber, int column) {
            Element row = rows.get(rowNumber);
            if (row == null) return "0";
            for (Element cell : cells(row)) {
                if (colIndex(cell.getAttribute("r")) == column) {
                    String style = cell.getAttribute("s");
                    return style.isBlank() ? "0" : style;
                }
            }
            return "0";
        }

        void setRefStyle(int sourceColumn, int targetColumn) {
            refStyles.put(targetColumn, baseStyle(sourceColumn));
        }

        void removeFirstChild(String localName) {
            Element existing = firstChild(localName);
            if (existing != null) doc.getDocumentElement().removeChild(existing);
        }

        void replaceMerges(List<String> references) {
            removeFirstChild("mergeCells");
            if (references == null || references.isEmpty()) return;
            Element merges = doc.createElementNS(NS, "mergeCells");
            merges.setAttribute("count", Integer.toString(references.size()));
            for (String reference : references) {
                Element merge = doc.createElementNS(NS, "mergeCell");
                merge.setAttribute("ref", reference);
                merges.appendChild(merge);
            }
            Node anchor = sheetData.getNextSibling();
            while (anchor != null && anchor.getNodeType() != Node.ELEMENT_NODE) anchor = anchor.getNextSibling();
            if (anchor == null) doc.getDocumentElement().appendChild(merges);
            else doc.getDocumentElement().insertBefore(merges, anchor);
        }

        void setColumnWidth(int column, double width) {
            Element cols = firstChild("cols");
            if (cols == null) {
                cols = doc.createElementNS(NS, "cols");
                Node anchor = sheetData;
                doc.getDocumentElement().insertBefore(cols, anchor);
            }
            NodeList nl = cols.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element col && "col".equals(col.getLocalName())) {
                    int min = parseInt(col.getAttribute("min"), -1);
                    int max = parseInt(col.getAttribute("max"), -1);
                    if (column >= min && column <= max) {
                        col.setAttribute("width", Double.toString(width));
                        col.setAttribute("customWidth", "1");
                        return;
                    }
                }
            }
            Element col = doc.createElementNS(NS, "col");
            col.setAttribute("min", Integer.toString(column));
            col.setAttribute("max", Integer.toString(column));
            col.setAttribute("width", Double.toString(width));
            col.setAttribute("customWidth", "1");
            cols.appendChild(col);
        }

        private static int parseInt(String value, int fallback) {
            try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
        }

        void setColumnWidths(double... widths) {
            Element cols = firstChild("cols");
            if (cols == null) {
                cols = doc.createElementNS(NS, "cols");
                Node anchor = sheetData;
                doc.getDocumentElement().insertBefore(cols, anchor);
            } else {
                while (cols.getFirstChild() != null) cols.removeChild(cols.getFirstChild());
            }
            for (int i = 0; i < widths.length; i++) {
                Element col = doc.createElementNS(NS, "col");
                col.setAttribute("min", Integer.toString(i + 1));
                col.setAttribute("max", Integer.toString(i + 1));
                col.setAttribute("width", Double.toString(widths[i]));
                col.setAttribute("customWidth", "1");
                cols.appendChild(col);
            }
        }

        /** Kosongkan isi sel (style dibiarkan) pada rentang baris/kolom. */
        void clear(int r1, int r2, int c1, int c2) {
            for (Map.Entry<Integer, Element> e : rows.subMap(r1, true, r2, true).entrySet()) {
                for (Element c : cells(e.getValue())) {
                    int col = colIndex(c.getAttribute("r"));
                    if (col >= c1 && col <= c2) {
                        wipe(c);
                    }
                }
                e.getValue().removeAttribute("spans");
            }
        }

        String baseStyle(int col) { return refStyles.getOrDefault(col, "0"); }

        void setText(int r, int c, String text) {
            setText(r, c, text, null);
        }

        void setText(int r, int c, String text, String styleOverride) {
            Element cell = cell(r, c);
            wipe(cell);
            if (styleOverride == null || styleOverride.isBlank()) applyStyle(cell, c);
            else cell.setAttribute("s", styleOverride);
            String v = clean(text);
            if (v.isEmpty()) {
                return;
            }
            cell.setAttribute("t", "inlineStr");
            Element is = doc.createElementNS(NS, "is");
            Element t = doc.createElementNS(NS, "t");
            t.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve");
            t.setTextContent(v);
            is.appendChild(t);
            cell.appendChild(is);
        }

        void setNumber(int r, int c, double value) {
            Element cell = cell(r, c);
            wipe(cell);
            applyStyle(cell, c);
            Element v = doc.createElementNS(NS, "v");
            v.setTextContent(value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value));
            cell.appendChild(v);
        }

        void link(int r, int c, String url) {
            if (url != null && !url.isBlank()) {
                links.add(new String[]{colName(c) + r, url});
            }
        }

        private void applyStyle(Element cell, int col) {
            String s = refStyles.get(col);
            if (s != null) {
                cell.setAttribute("s", s);
            }
        }

        private static void wipe(Element cell) {
            while (cell.getFirstChild() != null) {
                cell.removeChild(cell.getFirstChild());
            }
            cell.removeAttribute("t");
        }

        private Element row(int r) {
            Element existing = rows.get(r);
            if (existing != null) {
                existing.removeAttribute("spans");
                return existing;
            }
            Element row = doc.createElementNS(NS, "row");
            row.setAttribute("r", Integer.toString(r));
            Map.Entry<Integer, Element> next = rows.higherEntry(r);
            if (next != null) {
                sheetData.insertBefore(row, next.getValue());
            } else {
                sheetData.appendChild(row);
            }
            rows.put(r, row);
            return row;
        }

        private Element cell(int r, int c) {
            Element row = row(r);
            String ref = colName(c) + r;
            Node before = null;
            for (Element cell : cells(row)) {
                int col = colIndex(cell.getAttribute("r"));
                if (col == c) {
                    return cell;
                }
                if (col > c) {
                    before = cell;
                    break;
                }
            }
            Element cell = doc.createElementNS(NS, "c");
            cell.setAttribute("r", ref);
            if (before != null) {
                row.insertBefore(cell, before);
            } else {
                row.appendChild(cell);
            }
            return cell;
        }

        private static List<Element> cells(Element row) {
            List<Element> out = new ArrayList<>();
            NodeList nl = row.getChildNodes();
            for (int i = 0; i < nl.getLength(); i++) {
                if (nl.item(i) instanceof Element e && e.getLocalName().equals("c")) {
                    out.add(e);
                }
            }
            return out;
        }

        /** Perbarui dimension, buang hyperlink lama (menunjuk tiket versi sebelumnya), tulis hyperlink baru. */
        void finish(Map<String, byte[]> files) {
            int maxRow = 1, maxCol = 1;
            for (Map.Entry<Integer, Element> e : rows.entrySet()) {
                for (Element c : cells(e.getValue())) {
                    maxRow = Math.max(maxRow, e.getKey());
                    maxCol = Math.max(maxCol, colIndex(c.getAttribute("r")));
                }
            }
            Element dim = firstChild("dimension");
            if (dim != null) {
                dim.setAttribute("ref", "A1:" + colName(maxCol) + maxRow);
            }

            Element old = firstChild("hyperlinks");
            if (old != null) {
                doc.getDocumentElement().removeChild(old);
            }
            String dir = path.substring(0, path.lastIndexOf('/') + 1);
            String file = path.substring(path.lastIndexOf('/') + 1);
            String relsPath = dir + "_rels/" + file + ".rels";
            Document rels = files.containsKey(relsPath) ? parse(files.get(relsPath)) : newRels();
            NodeList rl = rels.getElementsByTagNameNS("*", "Relationship");
            for (int i = rl.getLength() - 1; i >= 0; i--) {
                Element rel = (Element) rl.item(i);
                if (HYPERLINK_TYPE.equals(rel.getAttribute("Type"))) {
                    rel.getParentNode().removeChild(rel);
                }
            }
            if (!links.isEmpty()) {
                Element hl = doc.createElementNS(NS, "hyperlinks");
                int n = 1;
                for (String[] l : links) {
                    String id = "rIdLink" + n++;
                    Element h = doc.createElementNS(NS, "hyperlink");
                    h.setAttribute("ref", l[0]);
                    h.setAttributeNS(R_NS, "r:id", id);
                    hl.appendChild(h);
                    Element rel = rels.createElementNS(PKG_REL_NS, "Relationship");
                    rel.setAttribute("Id", id);
                    rel.setAttribute("Type", HYPERLINK_TYPE);
                    rel.setAttribute("Target", l[1]);
                    rel.setAttribute("TargetMode", "External");
                    rels.getDocumentElement().appendChild(rel);
                }
                Element margins = firstChild("pageMargins");
                if (margins != null) {
                    doc.getDocumentElement().insertBefore(hl, margins);
                } else {
                    doc.getDocumentElement().appendChild(hl);
                }
            }
            if (!links.isEmpty() || files.containsKey(relsPath)) {
                files.put(relsPath, serialize(rels));
            }
            files.put(path, serialize(doc));
        }

        private static Document newRels() {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                Document d = f.newDocumentBuilder().newDocument();
                d.appendChild(d.createElementNS(PKG_REL_NS, "Relationships"));
                return d;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    // ------------------------------------------------------------------ util

    static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                n = n * 26 + (ch - 'A' + 1);
            } else {
                break;
            }
        }
        return n;
    }

    static String colName(int idx) {
        StringBuilder sb = new StringBuilder();
        while (idx > 0) {
            int rem = (idx - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            idx = (idx - 1) / 26;
        }
        return sb.toString();
    }

    /** Buang karakter yang tidak valid di XML 1.0 dan batasi panjang sel (32.767 karakter). */
    static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            boolean ok = cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                    || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF);
            if (ok) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.length() > 32767 ? sb.substring(0, 32767) : sb.toString();
    }
}
