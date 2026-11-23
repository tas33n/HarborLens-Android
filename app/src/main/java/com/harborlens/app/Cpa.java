package com.harborlens.app;

import org.jsoup.Jsoup;
import org.jsoup.Connection;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import java.io.IOException;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Cpa {
    static final String ROOT = "https://cpatos.gov.bd/pcs/";
    static final String LOGIN = ROOT + "index.php/Login";
    private static final String INDEX = ROOT + "index.php/";
    private static final Pattern SLOT = Pattern.compile("(?i)\\bSlot\\s*:\\s*V-[A-Z0-9-]*?(20[0-9]{2})([0-9]{4})[0-9]-(?=[0-9])");
    private static final Pattern VOY = Pattern.compile("(?i)^[A-Z .-]*([0-9]{1,8})N$");

    static class Review extends Exception {
        Review(String message) { super(message); }
    }

    static final class Auth extends Review {
        Auth(String message) { super(message); }
    }

    static boolean loginRequired(Document doc) {
        return !doc.select("form input[name=password]").isEmpty();
    }

    private static Document post(String path, String ref, String cookie, String... fields) throws IOException, Review {
        Connection request = Jsoup.connect(INDEX + path).referrer(ref.isEmpty() ? ROOT : INDEX + ref)
            .header("Origin", "https://cpatos.gov.bd").method(Connection.Method.POST)
            .timeout(12000).maxBodySize(2_000_000).followRedirects(false).ignoreHttpErrors(true).data(fields);
        if (cookie != null && !cookie.isEmpty()) request.header("Cookie", cookie);
        Connection.Response response = request.execute();
        int status = response.statusCode();
        if (status == 401 || status == 403 || (status >= 300 && status < 400)) {
            throw new Auth("Sign in to CPA again");
        }
        if (status >= 500 || status == 429) throw new IOException("CPA temporarily unavailable");
        if (status != 200) throw new Review("CPA returned HTTP " + status);
        Document result = response.parse();
        if (cookie != null && loginRequired(result)) throw new Auth("CPA session expired");
        return result;
    }

    static String[] lookup(String code, String cookie, java.util.function.BiConsumer<Integer, String> progress) throws IOException, Review {
        progress.accept(1, "Requesting container location");
        Document container = post("Report/mySearchContainerLocation", "", null, "containerLocation", code, "submit_login", "Search");
        if (!code.equalsIgnoreCase(field(container, "Container NO"))) throw new Review("Container missing or mismatched at CPA");
        if (!field(container, "Category").toUpperCase(Locale.ROOT).startsWith("EX")) throw new Review("CPA result is not export cargo");
        Matcher slot = SLOT.matcher(field(container, "Position / Location"));
        if (!slot.find()) throw new Review("No export vessel slot at CPA");
        String rotation = slot.group(1) + "/" + slot.group(2);
        String mlo = field(container, "MLO").split(",", 2)[0].trim();
        if (mlo.isEmpty()) throw new Review("CPA did not return the MLO");
        if (cookie == null || cookie.isEmpty()) throw new Auth("Sign in to CPA in Settings");

        progress.accept(2, "Location found · Rotation " + rotation + " · MLO " + mlo);
        Document manifest = post("IgmViewController/myListSearch", "IgmViewController/myListSearch", cookie,
            "SearchCriteria", "Import", "Searchdata", rotation, "SearchD", "Go", "type", "GM");
        String vessel = "", voyage = "";
        for (Element table : manifest.select("table")) {
            int rot = -1, name = -1, voy = -1;
            for (Element row : table.select("tr")) {
                if (row.closest("table") != table) continue;
                var cells = row.children().select("td,th");
                if (rot < 0 || name < 0 || voy < 0) {
                    for (int i = 0; i < cells.size(); i++) {
                        String label = cells.get(i).text();
                        if (label.equalsIgnoreCase("Import Rotation No")) rot = i;
                        if (label.equalsIgnoreCase("Vessel Name")) name = i;
                        if (label.equalsIgnoreCase("Voy No")) voy = i;
                    }
                    continue;
                }
                if (cells.size() <= Math.max(rot, Math.max(name, voy)) || !rotation.equals(cells.get(rot).text())) continue;
                String nextVessel = cells.get(name).text(), nextVoy = cells.get(voy).text();
                if (!vessel.isEmpty() && (!vessel.equals(nextVessel) || !voyage.equals(nextVoy))) throw new Review("CPA returned conflicting sailings");
                vessel = nextVessel;
                voyage = nextVoy;
            }
        }
        if (vessel.isEmpty()) throw new Review("No exact CPA vessel match. Check sign-in and rotation");
        Matcher number = VOY.matcher(voyage);
        if (!number.matches()) throw new Review("Import voyage format needs review: " + voyage);
        String digits = number.group(1);
        String export = String.format(Locale.ROOT, "%0" + digits.length() + "dS", Integer.parseInt(digits) + 1);

        progress.accept(3, "Vessel found · " + vessel + " · Export voyage " + export);
        Document report = post("Report/myAgentWiseVesselInfoReport", "Report/myAgentWiseVesselInfo", cookie,
            "ddl_imp_rot_no", rotation, "options", "html");
        String date = "";
        for (Element heading : report.select("h5")) {
            String value = heading.text();
            if (!value.regionMatches(true, 0, "ETD:", 0, 4)) continue;
            Matcher dateOnly = Pattern.compile("^([0-9]{1,2}-[A-Z]{3} [0-9]{4})\\b", Pattern.CASE_INSENSITIVE).matcher(value.substring(4).trim());
            if (dateOnly.find()) {
                SimpleDateFormat input = new SimpleDateFormat("dd-MMM yyyy", Locale.ENGLISH);
                input.setLenient(false);
                Date parsed = input.parse(dateOnly.group(1), new ParsePosition(0));
                if (parsed != null) {
                    String found = new SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(parsed);
                    if (!date.isEmpty() && !date.equals(found)) throw new Review("CPA returned conflicting ETDs");
                    date = found;
                }
            }
        }
        if (date.isEmpty()) throw new Review("CPA ETD missing. Check sign-in or vessel report");
        return new String[]{"", vessel, export, rotation, date, "1", mlo};
    }

    private static String field(Document doc, String label) {
        for (Element row : doc.select("tr")) {
            var cells = row.children();
            if (cells.size() >= 3 && cells.get(0).text().equalsIgnoreCase(label) && cells.get(1).text().equals(":")) return cells.get(2).text();
        }
        return "";
    }
}
