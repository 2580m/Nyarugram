package org.telegram.botgram;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class BotgramGeography {
    public static final class Region {
        public final String code, name, nameZh, abbreviation;
        Region(JSONObject json) throws Exception {
            code = json.getString("code"); name = json.getString("name");
            nameZh = json.getString("nameZh"); abbreviation = json.getString("abbreviation");
        }
        public String label() { return "zh".equals(LocaleController.getInstance().getCurrentLocale().getLanguage()) ? nameZh : name; }
    }
    private static List<Region> regions;
    public static synchronized List<Region> regions() {
        if (regions == null) {
            try (InputStream input = ApplicationLoader.applicationContext.getAssets().open("botgram-m49.json");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                JSONArray array = new JSONObject(output.toString("UTF-8")).getJSONArray("regions");
                ArrayList<Region> loaded = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) loaded.add(new Region(array.getJSONObject(i)));
                regions = java.util.Collections.unmodifiableList(loaded);
            } catch (Exception e) { throw new IllegalStateException("M49 data missing", e); }
        }
        return regions;
    }
    public static String format(String gid) {
        if (gid == null) return "";
        String digits = gid.replace("+", "").replace(" ", "");
        return digits.matches("[0-9]{11}") ? "+" + digits.substring(0, 3) + " " + digits.substring(3) : gid;
    }
}
