package com.example.soyomesaj;

import android.text.TextUtils;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class NameCleaner {
    private static final Locale TR = new Locale("tr", "TR");

    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "önerilen", "önerilenler", "yeniler", "mesaj", "mesajlar", "gönder", "gonder",
            "soyo", "ara", "keşfet", "kesfet", "profil", "takip", "takip et", "beğen", "begen",
            "geri", "ayarlar", "bildirimler", "çevrimiçi", "cevrimici", "online", "yeni",
            "sohbet", "sohbete başla", "sohbete basla", "merhaba", "ana sayfa", "anlar",
            "parti", "ben", "game", "turkey", "türkiye", "kişilik benzerliği", "istek listesi",
            "sonraki", "tag", "etiket", "badge", "rozet", "vip", "level", "seviye", "id", "uid",
            "nickname", "kullanıcı adı", "kullanici adi", "emoji", "sticker", "çıkartma", "cikartma"
    ));

    private NameCleaner() {}

    public static String clean(String raw) {
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = foldSmallCaps(s);
        s = s.replaceAll("[\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF]", "");
        if (isMetadata(s)) return null;

        String rawLower = lower(s);
        String rawCompact = rawLower.replaceAll("\\s+", "");
        if (rawCompact.matches("\\d+(?:km|yaş|yas|y/o)?")
                || rawCompact.matches("(?:km|yaş|yas)\\d+")
                || (rawCompact.contains("km") && rawCompact.matches(".*\\d.*"))) {
            return null;
        }

        s = s.replaceAll("\\s*[\\[({<][^\\])}>]{1,28}[\\])}>]", " ");
        s = s.replaceAll("(?iu)(?:^|\\s)[@#][\\p{L}\\p{N}_.-]+", " ");
        s = s.replaceAll("(?iu)\\s+(?:VIP|LV\\.?|LEVEL|SEVİYE|SEVIYE|ROZET|BADGE|TAG|ID)\\s*[:#-]?\\s*\\d*.*$", "");
        s = s.replaceAll("[^\\p{L} '-]+", " ");
        s = removeDecorativeMarks(s).trim().replaceAll("\\s+", " ");

        if (s.length() < 2 || s.length() > 28) return null;
        String lower = lower(s);
        if (BLOCKED.contains(lower) || isMetadata(s)) return null;
        if (lower.contains("http") || lower.contains("vip") || lower.contains(" km")) return null;
        if (s.matches(".*\\d.*")) return null;
        if (!s.matches("[\\p{L}]+(?:[ '-][\\p{L}]+){0,2}")) return null;

        String[] parts = s.split("[ '-]");
        if (parts.length > 3 || parts[0].length() < 2) return null;
        return titleCase(s);
    }

    public static boolean same(String a, String b) {
        String ca = clean(a);
        String cb = clean(b);
        if (ca == null || cb == null) return false;
        return key(ca).equals(key(cb));
    }

    public static String key(String name) {
        String cleaned = clean(name);
        if (cleaned == null) return "";
        return lower(cleaned).replaceAll("[^\\p{L}]", "");
    }

    public static boolean isMetadata(String value) {
        if (TextUtils.isEmpty(value)) return true;
        String compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace('ı', 'i')
                .replaceAll("[\\s._:#-]+", "");
        return compact.matches("(?:tag|etiket|badge|rozet|vip\\d*|lv\\d*|level\\d*|seviye\\d*|id\\d*|uid\\d*)");
    }

    public static String lower(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(TR);
    }

    private static String titleCase(String raw) {
        StringBuilder out = new StringBuilder();
        for (String part : raw.trim().replaceAll("\\s+", " ").split(" ")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(TR));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(TR));
        }
        return out.toString();
    }

    private static String foldSmallCaps(String value) {
        String from = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        String to =   "abcdefghijklmnopqrstuvwxyz";
        StringBuilder out = new StringBuilder(value.length());
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            int idx = from.indexOf(ch);
            if (idx >= 0) out.append(to.charAt(idx)); else out.append(ch);
        }
        return out.toString();
    }

    private static String removeDecorativeMarks(String value) {
        String protectedTurkish = "ÇĞİÖŞÜçğıöşüÂâÎîÛû";
        StringBuilder out = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if (protectedTurkish.indexOf(cp) >= 0) {
                out.appendCodePoint(cp);
                continue;
            }
            String decomposed = Normalizer.normalize(new String(Character.toChars(cp)), Normalizer.Form.NFD);
            for (int i = 0; i < decomposed.length(); i++) {
                char c = decomposed.charAt(i);
                if (Character.getType(c) != Character.NON_SPACING_MARK) out.append(c);
            }
        }
        return Normalizer.normalize(out.toString(), Normalizer.Form.NFC);
    }
}
