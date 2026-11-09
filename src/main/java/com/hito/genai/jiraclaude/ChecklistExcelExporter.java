package com.hito.genai.jiraclaude;

import com.hito.genai.jiraclaude.ReportModels.*;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Mengisi template "Pre Deployment Checklist" (xlsx) dengan data report.
 *
 * Tanpa library tambahan: file xlsx adalah zip berisi XML, dan hanya isi sel yang diganti.
 * Style, lebar kolom, merge cell, dan dropdown (data validation) milik template tetap utuh.
 * Kolom yang diisi manual (status SIT/UAT/PROD, branch, collection, dst.) sengaja dibiarkan kosong.
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
        Map<String, byte[]> files = readTemplate();
        Map<String, String> sheetPaths = sheetPaths(files);

        fillAllTicket(open(files, sheetPaths, "All Ticket"), files, report.tickets());
        fillDevOps(open(files, sheetPaths, "DevOps Ticket"), files, report.devops());
        fillDdl(open(files, sheetPaths, "DDL Ticket"), files, report.ddl());
        fillHotfix(open(files, sheetPaths, "Hotfix OR Sanity Tickets"), files, report.hotfix());
        fillRepos(open(files, sheetPaths, "Repo Impacted"), files, report.repos());

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
        s.clear(2, s.lastRow(), 1, 27);
        for (int i = 0; i < tickets.size(); i++) {
            Ticket t = tickets.get(i);
            int r = i + 2;
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, t.issueType());
            s.setText(r, 3, t.key());
            s.link(r, 3, t.link());
            s.setText(r, 4, t.link());
            s.setText(r, 5, t.summary());
            s.setText(r, 6, t.usingPrdaApi());
            s.setText(r, 7, t.parentKey());
            s.setText(r, 8, t.parentSummary());
            s.setText(r, 9, t.assignee());
            s.setText(r, 10, t.assigneeId());
            s.setText(r, 11, t.reporter());
            s.setText(r, 12, t.reporterId());
            List<String> sp = t.sprints();
            for (int k = 0; k < 5; k++) {
                String v = k < sp.size() ? sp.get(k) : "";
                if (k == 4 && sp.size() > 5) {
                    v = String.join(", ", sp.subList(4, sp.size()));
                }
                s.setText(r, 13 + k, v);
            }
            s.setText(r, 18, t.status());
            s.setText(r, 19, t.priority());
            s.setText(r, 20, t.resolution());
            s.setText(r, 21, t.created());
            s.setText(r, 22, t.updated());
            if (t.storyPoints() != null) {
                s.setNumber(r, 23, t.storyPoints());
            } else {
                s.setText(r, 23, "");
            }
        }
        Element af = s.firstChild("autoFilter");
        if (af != null) {
            af.setAttribute("ref", "A1:AA" + (tickets.size() + 1));
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

    private void fillDdl(Sheet s, Map<String, byte[]> files, List<DdlRow> rows) {
        s.captureRefStyles(2);
        s.clear(2, s.lastRow(), 1, 12);
        for (int i = 0; i < rows.size(); i++) {
            int r = 2 + i;
            DdlRow d = rows.get(i);
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, d.label());
            s.link(r, 2, d.link());
            // dari panel Development Jira: branch & status PR (kolom lain tetap manual)
            if (!d.branches().isEmpty()) {
                s.setText(r, 3, String.join(", ", d.branches()));
            }
            if (!d.prSummary().isBlank()) {
                s.setText(r, 4, d.prSummary());
            }
        }
        s.finish(files);
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

    private void fillRepos(Sheet s, Map<String, byte[]> files, List<RepoRow> rows) {
        s.captureRefStyles(3);
        for (int i = 0; i < rows.size(); i++) {
            int r = 3 + i;
            RepoRow row = rows.get(i);
            s.setNumber(r, 1, i + 1);
            s.setText(r, 2, row.name());
            // "Pull Request To Main": status PR ke main/master, berhyperlink ke PR pertama
            if (!row.prsToMain().isEmpty()) {
                s.setText(r, 9, row.prToMainLabel());
                s.link(r, 9, row.prToMainUrl());
            }
        }
        s.finish(files);
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

        void setText(int r, int c, String text) {
            Element cell = cell(r, c);
            wipe(cell);
            applyStyle(cell, c);
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
