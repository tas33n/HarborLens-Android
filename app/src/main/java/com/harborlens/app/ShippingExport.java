package com.harborlens.app;

import android.content.Context;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

final class ShippingExport {
    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String S = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    static final String WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    static final class Files {
        final byte[] word, excel;
        Files(byte[] word, byte[] excel) { this.word = word; this.excel = excel; }
    }

    static Files create(Context context, ArrayList<String[]> additions) throws Exception {
        byte[] template;
        try (InputStream input = context.getAssets().open("shipping-bill-template.docx")) {
            template = bytes(input);
        }
        Document document = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(template))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes(input)));
                break;
            }
        }
        if (document == null) throw new IllegalArgumentException("Template has no document body");
        var texts = document.getElementsByTagNameNS(W, "t");
        for (int i = 0; i < texts.getLength(); i++) {
            Node heading = texts.item(i);
            String label = heading.getTextContent();
            if (!label.contains("SHIPPING BILL LIST.") || !label.endsWith("DATE:")) continue;
            var date = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Dhaka"));
            heading.setTextContent(label + date.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)));
            break;
        }
        ArrayList<String[]> sheet = populate(document, additions);
        ByteArrayOutputStream word = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(template));
             ZipOutputStream output = new ZipOutputStream(word)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                byte[] data = bytes(input);
                output.putNextEntry(new ZipEntry(entry.getName()));
                if ("word/document.xml".equals(entry.getName())) {
                    TransformerFactory.newInstance().newTransformer()
                        .transform(new DOMSource(document), new StreamResult(output));
                } else output.write(data);
                output.closeEntry();
            }
        }
        return new Files(word.toByteArray(), excel(sheet));
    }

    private static ArrayList<String[]> populate(Document document, ArrayList<String[]> additions) {
        var tables = document.getElementsByTagNameNS(W, "tbl");
        for (int i = 0; i < tables.getLength(); i++) {
            Element table = (Element) tables.item(i);
            var rows = children(table, "tr");
            if (rows.size() != 2) continue;
            Element header = rows.get(0);
            String[] labels = values(header);
            if (labels.length != 7 || !labels[1].equals("VSL NAME")) continue;
            Element blank = rows.get(1);
            if (children(blank, "tc").size() != labels.length) continue;
            var properties = children(header, "trPr");
            Element prop = properties.isEmpty() ? document.createElementNS(W, "w:trPr") : properties.get(0);
            if (properties.isEmpty()) header.insertBefore(prop, header.getFirstChild());
            if (children(prop, "tblHeader").isEmpty()) prop.appendChild(document.createElementNS(W, "w:tblHeader"));

            ArrayList<String[]> data = new ArrayList<>();
            data.add(labels);
            table.removeChild(blank);
            for (String[] addition : additions) {
                Element row = (Element) blank.cloneNode(true);
                var cells = children(row, "tc");
                for (int j = 1; j < labels.length; j++) set(cells.get(j), addition[j]);
                set(cells.get(0), data.size() + ".");
                table.appendChild(row);
                String[] entry = addition.clone();
                entry[0] = Integer.toString(data.size());
                data.add(entry);
            }
            return data;
        }
        throw new IllegalArgumentException("Shipping table template not found");
    }

    static String key(String[] row) {
        String vessel = row[1].toUpperCase(Locale.ROOT).replaceFirst("^MV[ .]+", "").replaceAll("[^A-Z0-9]", "");
        String voyage = row[2].toUpperCase(Locale.ROOT).trim().replaceFirst("^0+(?=[0-9])", "");
        return vessel + "|" + voyage + "|" + row[3].trim() + "|" + row[6].trim().toUpperCase(Locale.ROOT);
    }

    private static ArrayList<Element> children(Element parent, String name) {
        ArrayList<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element && W.equals(node.getNamespaceURI()) && name.equals(node.getLocalName())) result.add((Element) node);
        }
        return result;
    }

    private static String[] values(Element row) {
        var cells = children(row, "tc");
        String[] result = new String[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            var texts = cells.get(i).getElementsByTagNameNS(W, "t");
            StringBuilder value = new StringBuilder();
            for (int j = 0; j < texts.getLength(); j++) value.append(texts.item(j).getTextContent());
            result[i] = value.toString().trim();
        }
        return result;
    }

    private static void set(Element cell, String value) {
        var texts = cell.getElementsByTagNameNS(W, "t");
        if (texts.getLength() > 0) {
            texts.item(0).setTextContent(value);
            for (int i = 1; i < texts.getLength(); i++) texts.item(i).setTextContent("");
            return;
        }
        var doc = cell.getOwnerDocument();
        var paragraphs = children(cell, "p");
        Element paragraph = paragraphs.isEmpty() ? doc.createElementNS(W, "w:p") : paragraphs.get(0);
        if (paragraph.getParentNode() == null) cell.appendChild(paragraph);
        var runs = children(paragraph, "r");
        Element run = runs.isEmpty() ? doc.createElementNS(W, "w:r") : runs.get(0);
        Element text = doc.createElementNS(W, "w:t");
        text.setTextContent(value);
        run.appendChild(text);
        if (run.getParentNode() == null) paragraph.appendChild(run);
    }

    private static byte[] bytes(InputStream in) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = in.read(buffer)) != -1) output.write(buffer, 0, size);
        return output.toByteArray();
    }

    private static byte[] excel(ArrayList<String[]> rows) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        String rel = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String pkg = "http://schemas.openxmlformats.org/package/2006/relationships";
        StringBuilder sheet = new StringBuilder("<worksheet xmlns=\"" + S + "\"><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"2\" max=\"2\" width=\"30\" customWidth=\"1\"/><col min=\"3\" max=\"5\" width=\"16\" customWidth=\"1\"/></cols><sheetData>");
        for (int i = 0; i < rows.size(); i++) {
            sheet.append("<row r=\"").append(i+1).append("\">");
            for (int j = 0; j < rows.get(i).length; j++) {
                String value = rows.get(i)[j];
                sheet.append("<c r=\"").append((char) ('A'+j)).append(i+1).append("\"");
                if (i == 0) sheet.append(" s=\"1\"");
                if (i > 0 && (j == 0 || j == 5) && value.matches("[0-9]+")) {
                    sheet.append("><v>").append(value).append("</v></c>");
                } else {
                    sheet.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        .append(value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                        .append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");
        try (ZipOutputStream zip = new ZipOutputStream(file)) {
            part(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
            part(zip, "_rels/.rels", "<Relationships xmlns=\"" + pkg + "\"><Relationship Id=\"rId1\" Type=\"" + rel + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
            part(zip, "xl/workbook.xml", "<workbook xmlns=\"" + S + "\" xmlns:r=\"" + rel + "\"><sheets><sheet name=\"Shipping Bill List\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
            part(zip, "xl/_rels/workbook.xml.rels", "<Relationships xmlns=\"" + pkg + "\"><Relationship Id=\"rId1\" Type=\"" + rel + "/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"" + rel + "/styles\" Target=\"styles.xml\"/></Relationships>");
            part(zip, "xl/styles.xml", "<styleSheet xmlns=\"" + S + "\"><fonts count=\"2\"><font><sz val=\"10\"/><name val=\"Times New Roman\"/></font><font><b/><sz val=\"10\"/><name val=\"Times New Roman\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf xfId=\"0\" fontId=\"0\" applyFont=\"1\"/><xf xfId=\"0\" fontId=\"1\" applyFont=\"1\"/></cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
            part(zip, "xl/worksheets/sheet1.xml", sheet.toString());
        }
        return file.toByteArray();
    }

    private static void part(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
