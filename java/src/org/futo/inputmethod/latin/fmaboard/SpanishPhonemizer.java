package org.futo.inputmethod.latin.fmaboard;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Spanish text to IPA in the form the Kokoro voices were trained on (espeak-ng "es", with the
 * ties Kokoro uses: ʧ, I = aɪ, A = eɪ, W = aʊ). Written from the Spanish spelling rules and checked
 * against espeak-ng on 20,000 common words (97% identical, the rest mostly English names), so the
 * app does not need espeak-ng, which is GPL.
 *
 * B, D and G stand for b/v, d and g until the phrase is known: they become a stop (b d ɡ) after a
 * pause or a nasal and a fricative (β ð ɣ) elsewhere.
 */
public final class SpanishPhonemizer {
    private SpanishPhonemizer() {}

    // Phoneme units for one word, before context rules.
    static final class Seg {
        String ph;          // consonant phoneme, or vowel letter a e i o u
        boolean vowel;
        boolean accent;     // written accent
        boolean weak;       // i/u without accent (or y as vowel)
        boolean diaeresis;  // ü
        boolean afterH;     // a silent h came before this vowel: no diphthong across it
        Seg(String ph, boolean vowel) { this.ph = ph; this.vowel = vowel; }
    }

    static boolean isVowelLetter(char c) { return "aeiouáéíóúü".indexOf(c) >= 0; }
    static boolean isFrontVowel(char c) { return "eiéí".indexOf(c) >= 0; }

    static List<Seg> letters(String w) {
        List<Seg> s = new ArrayList<>();
        int n = w.length();
        boolean hNext = false;
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            int before = s.size();
            char nx = i + 1 < n ? w.charAt(i + 1) : 0;
            char nx2 = i + 2 < n ? w.charAt(i + 2) : 0;
            switch (c) {
                case 'a': case 'e': case 'o': s.add(vow(String.valueOf(c), false, false)); break;
                case 'i': case 'u': s.add(vow(String.valueOf(c), false, true)); break;
                case 'á': s.add(vow("a", true, false)); break;
                case 'é': s.add(vow("e", true, false)); break;
                case 'í': s.add(vow("i", true, false)); break;
                case 'ó': s.add(vow("o", true, false)); break;
                case 'ú': s.add(vow("u", true, false)); break;
                case 'ü': { Seg v = vow("u", false, false); v.diaeresis = true; s.add(v); break; }
                case 'h':
                    if (isVowelLetter(nx)) hNext = true;
                    break;
                case 'c':
                    if (nx == 'h') { s.add(con("ʧ")); i++; }
                    else if (isFrontVowel(nx) || nx == 'y') s.add(con("θ"));
                    else s.add(con("k"));
                    break;
                case 'q':
                    s.add(con("k"));
                    if (nx == 'u') i++;
                    break;
                case 'g':
                    if (isFrontVowel(nx)) s.add(con("x"));
                    else if (nx == 'u' && isFrontVowel(nx2)) { s.add(con("G")); i++; }
                    else s.add(con("G"));
                    break;
                case 'l':
                    if (nx == 'l') { s.add(con("ʎ")); i++; } else s.add(con("l"));
                    break;
                case 'r':
                    if (nx == 'r') { s.add(con("r")); i++; }
                    else if (i == 0) s.add(con("r"));
                    else {
                        char pv = w.charAt(i - 1);
                        s.add(con(pv == 'n' || pv == 'l' || pv == 's' ? "r" : "ɾ"));
                    }
                    break;
                case 'y':
                    if (isVowelLetter(nx)) s.add(con("ʝ"));
                    else s.add(vow("i", false, true));
                    break;
                case 'ñ': s.add(con("ɲ")); break;
                case 'j': s.add(con("x")); break;
                case 'z': s.add(con("θ")); break;
                case 'b': case 'v': s.add(con("B")); break;
                case 'd': s.add(con("D")); break;
                case 'x':
                    if (i == 0) s.add(con("s"));
                    else { s.add(con("k")); s.add(con("s")); }
                    break;
                case 'w': s.add(con("w")); break;
                case 'k': if (i == 0 || w.charAt(i - 1) != 'c') s.add(con("k")); break;
                case 's':
                    if (nx == 'h') { s.add(con("ʃ")); i++; } else s.add(con("s"));
                    break;
                case 'p':
                    if (nx == 'h') { s.add(con("f")); i++; } else s.add(con("p"));
                    break;
                case 'f': case 'm': case 'n': case 't':
                    s.add(con(String.valueOf(c))); break;
                default: break; // ignore other characters
            }
            if (c != 'h' && hNext) {
                if (s.size() > before && s.get(before).vowel) s.get(before).afterH = true;
                hNext = false;
            }
        }
        return s;
    }

    static Seg vow(String v, boolean accent, boolean weak) {
        Seg s = new Seg(v, true); s.accent = accent; s.weak = weak; return s;
    }
    static Seg con(String c) { return new Seg(c, false); }

    /** Phonemes for one word, with stress marks. stressed=false drops the primary stress. */
    static String word(String w, boolean stressed) {
        List<Seg> s = letters(w);
        int n = s.size();
        // nucleus index for each vowel: group vowels into syllable nuclei
        int[] nucleusOf = new int[n];
        boolean[] glide = new boolean[n];
        List<Integer> nuclei = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (!s.get(i).vowel) { nucleusOf[i] = -1; i++; continue; }
            int j = i;
            while (j < n && s.get(j).vowel && (j == i || !s.get(j).afterH)) j++;
            // vowels i..j-1 form a run; split into syllable nuclei
            int k = i;
            while (k < j) {
                // pick the nucleus starting at k
                int start = k;
                int nuc = k;
                if (s.get(k).weak && k + 1 < j && !s.get(k + 1).diaeresis) {
                    // weak followed by vowel: weak is a glide
                    glide[k] = true;
                    nuc = k + 1;
                }
                int end = nuc + 1;
                // falling glide: strong (or nucleus) followed by weak not followed by another vowel nucleus start
                if (end < j && s.get(end).weak && !(end + 1 < j && !s.get(end + 1).weak)) {
                    glide[end] = true;
                    end++;
                }
                for (int m = start; m < end; m++) nucleusOf[m] = nuclei.size();
                nuclei.add(nuc);
                k = end;
            }
            i = j;
        }
        // stress
        int stress = -1;
        for (int m = 0; m < n; m++) if (s.get(m).accent) { stress = nucleusOf[m]; break; }
        if (stress < 0 && !nuclei.isEmpty()) {
            char last = w.charAt(w.length() - 1);
            boolean vowelY = last == 'y' && w.length() > 2 && !isVowelLetter(w.charAt(w.length() - 2));
            boolean penult = isVowelLetter(last) || last == 'n' || last == 's' || vowelY;
            stress = penult && nuclei.size() >= 2 ? nuclei.size() - 2 : nuclei.size() - 1;
        }
        StringBuilder out = new StringBuilder();
        for (int m = 0; m < n; m++) {
            Seg g = s.get(m);
            if (g.vowel) {
                int nu = nucleusOf[m];
                boolean isNuc = nuclei.get(nu) == m;
                if (isNuc && stressed && nu == stress) out.append('ˈ');
                // secondary stress on syllables 0, 2, 4... at least two before the primary
                if (isNuc && stressed && nu % 2 == 0 && stress - nu >= 2) out.append('ˌ');
                boolean before = m < nuclei.get(nu);
                // espeak keeps a full vowel after l/r (cliente, prueba, barrio) and after a written accent (habéis)
                boolean afterLiquid = false;
                if (m > 0 && !s.get(m - 1).vowel) {
                    String lq = s.get(m - 1).ph;
                    boolean clusterOrInitial = m == 1 || !s.get(m - 2).vowel;
                    afterLiquid = lq.equals("r") || (("l".equals(lq) || "ɾ".equals(lq)) && clusterOrInitial);
                }
                boolean afterAccent = !before && s.get(nuclei.get(nu)).accent;
                if (glide[m] && !(before && afterLiquid) && !afterAccent) {
                    if (before) out.append(g.ph.equals("i") ? "j" : "w");
                    else out.append(g.ph.equals("i") ? "ɪ" : (m > 0 && s.get(m - 1).ph.equals("o") ? "w" : "ʊ"));
                } else if (isNuc && stressed && nu == stress && g.ph.equals("e") && !g.accent
                        && m + 2 < n && s.get(m + 1).ph.equals("n") && !s.get(m + 2).vowel) {
                    out.append("ɛ");
                } else out.append(g.ph);
            } else out.append(g.ph);
        }
        String r = out.toString();
        r = r.replace("aɪ", "I").replace("aʊ", "W").replace("eɪ", "A");
        return r;
    }

    /** Context rules over a whole phrase: b/d/g allophones and nasal assimilation. */
    static String context(String p) {
        StringBuilder o = new StringBuilder();
        char prev = '#';  // previous phoneme ignoring stress marks and spaces; '#' = pause
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == 'B' || c == 'D' || c == 'G') {
                boolean stop;
                if (prev == '#') stop = true;
                else if (c == 'B') stop = prev == 'm' || prev == 'n';
                else if (c == 'D') stop = prev == 'n';
                else stop = prev == 'n' || prev == 'ŋ';
                char after = i + 1 < p.length() ? p.charAt(i + 1) : ' ';
                if (",.;:?!…".indexOf(after) >= 0 || i + 1 >= p.length()) stop = true;
                else if (after == ' ') {
                    // word-final: fricative only when the next word starts with a vowel
                    int k = i + 2;
                    while (k < p.length() && (p.charAt(k) == 'ˈ' || p.charAt(k) == 'ˌ')) k++;
                    stop = !(k < p.length() && isVowelPh(p.charAt(k)) && c == 'D');
                }
                if (c == 'G' && (after == 'n' || after == 'm')) stop = true;
                if (c == 'B' && "tB".indexOf(after) >= 0) stop = true;
                if (c == 'D' && (after == 'm' || after == 'n')) stop = true;
                String ph = c == 'B' ? (stop ? "b" : "β") : c == 'D' ? (stop ? "d" : "ð") : (stop ? "ɡ" : "ɣ");
                if (stop && (c == 'B') && prev == 'n') fixNasal(o, 'm');
                if (stop && (c == 'G') && prev == 'n') fixNasal(o, 'ŋ');
                o.append(ph);
                prev = ph.charAt(0);
                continue;
            }
            if (c == 'p' && prev == 'n') fixNasal(o, 'm');
            if (c == 'x' && prev == 'n') fixNasal(o, 'ŋ');
            o.append(c);
            if (c == ' ' || c == 'ˈ' || c == 'ˌ') continue;
            if (",.;:?!".indexOf(c) >= 0) prev = '#'; else prev = c;
        }
        return o.toString();
    }

    static boolean isVowelPh(char c) { return "aeiouɛɪʊIAWjwˈ".indexOf(c) >= 0; }

    static void fixNasal(StringBuilder o, char to) {
        for (int k = o.length() - 1; k >= 0; k--) {
            char c = o.charAt(k);
            if (c == ' ' || c == 'ˈ' || c == 'ˌ') continue;
            if (c == 'n') o.setCharAt(k, to);
            return;
        }
    }

    static final String[] LETTER_NAMES = {
        "a", "be", "ce", "de", "e", "efe", "ge", "hache", "i", "jota", "ka", "ele", "eme",
        "ene", "o", "pe", "cu", "erre", "ese", "te", "u", "uve", "uve doble", "equis", "i griega", "zeta"};

    static String lastName(String w) {
        char c = w.charAt(w.length() - 1);
        String name = c == 'ñ' ? "eñe" : (c >= 'a' && c <= 'z') ? LETTER_NAMES[c - 'a'] : "";
        int sp = name.lastIndexOf(' ');
        return sp >= 0 ? name.substring(sp + 1) : name;
    }

    static String wordFull(String w, boolean stressed) {
        if (w.equals("y")) return "i";
        boolean anyVowel = false;
        for (int k = 0; k < w.length(); k++) if (isVowelLetter(w.charAt(k)) || w.charAt(k) == 'y') anyVowel = true;
        if (!anyVowel) {
            StringBuilder sp = new StringBuilder();
            for (int k = 0; k < w.length(); k++) {
                char c = w.charAt(k);
                String name = c == 'ñ' ? "eñe" : (c >= 'a' && c <= 'z') ? LETTER_NAMES[c - 'a'] : null;
                if (name == null) continue;
                for (String part : name.split(" ")) sp.append(word(part, false));
            }
            String spelled = sp.toString();
            if (!stressed) return spelled;
            // stress only the last letter name, as espeak does
            String last = word(lastName(w), false);
            return spelled.substring(0, spelled.length() - last.length()) + word(lastName(w), true);
        }
        if (w.length() > 6 && w.endsWith("mente")) {
            return wordFull(w.substring(0, w.length() - 5), stressed) + (stressed ? "mˈente" : "mente");
        }
        if (w.length() > 2 && w.endsWith("uy") && !isVowelLetter(w.charAt(w.length() - 3))) {
            return word(w.substring(0, w.length() - 1), stressed) + "j";
        }
        return word(w, stressed);
    }

    static final java.util.Set<String> UNSTRESSED = new java.util.HashSet<>(java.util.Arrays.asList(
        "de", "que", "a", "la", "el", "y", "e", "en", "lo", "por", "me", "los", "se", "te", "con", "mi", "si",
        "su", "las", "del", "tu", "al", "le", "nos", "o", "u", "sus", "sin", "mis", "les", "tus", "quien", "os", "ni", "sobre", "hasta", "cuando", "pero", "para"));

    static final java.util.Map<String, String> ABBREVIATIONS = new java.util.HashMap<>();
    static {
        String[] pairs = {
            "q", "que", "k", "que", "xq", "porque", "pq", "porque", "porq", "porque", "x", "por",
            "tb", "también", "tmb", "también", "tbn", "también", "d", "de", "dnd", "donde", "bn", "bien",
            "ntp", "no te preocupes", "mñn", "mañana", "msj", "mensaje", "xfa", "por favor", "pls", "porfa",
            "xd", "equis de", "ok", "oquei", "tq", "te quiero", "tqm", "te quiero mucho",
            "sq", "es que", "salu2", "saludos", "aki", "aquí", "nse", "no sé", "nose", "no sé", "kiero", "quiero",
        };
        for (int i = 0; i < pairs.length; i += 2) ABBREVIATIONS.put(pairs[i], pairs[i + 1]);
    }

    static final String[] UNITS = {"cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve",
        "diez", "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
        "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro", "veinticinco", "veintiséis", "veintisiete",
        "veintiocho", "veintinueve"};
    static final String[] TENS = {"", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
    static final String[] HUNDREDS = {"", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos"};

    static String below1000(int n) {
        if (n == 100) return "cien";
        StringBuilder b = new StringBuilder();
        if (n >= 100) { b.append(HUNDREDS[n / 100]); n %= 100; if (n > 0) b.append(' '); }
        if (n >= 30) { b.append(TENS[n / 10]); if (n % 10 > 0) b.append(" y ").append(UNITS[n % 10]); }
        else if (n > 0) b.append(UNITS[n]);
        return b.toString();
    }

    static String number(long n) {
        if (n == 0) return "cero";
        long millions = n / 1_000_000, thousands = (n / 1000) % 1000, rest = n % 1000;
        if (millions >= 1000) return digits(Long.toString(n));
        StringBuilder b = new StringBuilder();
        if (millions > 0) {
            b.append(millions == 1 ? "un millón" : below1000((int) millions).replaceAll("uno$", "un") + " millones");
        }
        if (thousands > 0) {
            if (b.length() > 0) b.append(' ');
            if (thousands > 1) b.append(below1000((int) thousands).replaceAll("uno$", "un")).append(' ');
            b.append("mil");
        }
        if (rest > 0) { if (b.length() > 0) b.append(' '); b.append(below1000((int) rest)); }
        return b.toString();
    }

    static String digits(String d) {
        StringBuilder b = new StringBuilder();
        for (char c : d.toCharArray()) { if (b.length() > 0) b.append(' '); b.append(UNITS[c - '0']); }
        return b.toString();
    }

    // time (21:30) | number with thousands dots (2.000) | number with optional decimals | word | punctuation
    static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern
        .compile("\\b(\\d{1,2}):(\\d{2})\\b|(\\d{1,3}(?:\\.\\d{3})+)(?![.,]?\\d)|(\\d+)([.,]\\d+)?|[a-záéíóúüñ]+|[,.;:!?…()\"—]");

    static String integer(String num) {
        return num.length() > 9 || (num.length() > 1 && num.startsWith("0")) ? digits(num) : number(Long.parseLong(num));
    }

    /** "21:30" as people say it: "veintiuna y media". */
    static String time(int hours, int minutes) {
        String h = hours == 1 ? "una" : hours == 21 ? "veintiuna" : number(hours);
        if (minutes == 0) return h;
        if (minutes == 15) return h + " y cuarto";
        if (minutes == 30) return h + " y media";
        return h + " " + (minutes < 10 ? "cero " : "") + number(minutes);
    }

    /** Words, with numbers and chat abbreviations written out; punctuation as its own token. */
    static List<String> tokens(String text) {
        String t = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(new Locale("es"));
        t = t.replaceAll("https?://\\S+|www\\.\\S+|\\S+@\\S+", " ");
        t = t.replace('“', '"').replace('”', '"').replace('«', '"').replace('»', '"').replace("...", "…");
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = TOKEN.matcher(t);
        while (m.find()) {
            String tok = m.group();
            String spelled = null;
            if (m.group(1) != null) {
                int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
                spelled = h < 24 && min < 60 ? time(h, min) : integer(m.group(1)) + " " + integer(m.group(2));
            } else if (m.group(3) != null) {
                spelled = integer(m.group(3).replace(".", ""));
            } else if (m.group(4) != null) {
                spelled = integer(m.group(4));
                if (m.group(5) != null) {
                    String dec = m.group(5).substring(1);
                    spelled += " coma " + (dec.length() > 3 || dec.startsWith("0") ? digits(dec) : number(Long.parseLong(dec)));
                }
            }
            if (spelled != null) {
                for (String w : spelled.split(" ")) out.add(w);
            } else if (Character.isLetter(tok.charAt(0))) {
                String ab = ABBREVIATIONS.get(tok);
                if (ab != null) for (String w : ab.split(" ")) out.add(w);
                else out.add(tok);
            } else out.add(tok);
        }
        return out;
    }

    /** IPA for a sentence or a short text, in the form Kokoro was trained on (espeak-ng "es"). */
    public static String phonemize(String text) {
        StringBuilder b = new StringBuilder();
        boolean open = false; // an opening ( or " was just written: no space after it
        for (String tok : tokens(text)) {
            if (!Character.isLetter(tok.charAt(0))) {
                if (tok.equals("(") || (tok.equals("\"") && !open)) {
                    if (b.length() > 0 && b.charAt(b.length() - 1) != ' ') b.append(' ');
                    b.append(tok);
                    open = true;
                    continue;
                }
                while (b.length() > 0 && b.charAt(b.length() - 1) == ' ') b.setLength(b.length() - 1);
                b.append(tok).append(' ');
                open = false;
                continue;
            }
            if (b.length() > 0 && b.charAt(b.length() - 1) != ' ' && !open) b.append(' ');
            open = false;
            try {
                b.append(wordFull(tok, !UNSTRESSED.contains(tok)));
            } catch (RuntimeException e) {
                // never fail a whole message over one odd word
            }
        }
        return context(b.toString().trim());
    }
}
