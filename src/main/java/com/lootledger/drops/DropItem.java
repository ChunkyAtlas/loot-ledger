package com.lootledger.drops;

import lombok.Getter;
import lombok.Setter;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Setter
@Getter
public class DropItem
{
    private int itemId;
    private String name;
    private String rarity;

    // e.g., "12.5%"
    private static final Pattern PCT      = Pattern.compile("^(\\d+(?:\\.\\d+)?)%$");
    // e.g., "2 x 1 / 128"  or  "2x1/128"
    private static final Pattern MULT     = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*[xX]\\s*(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)$");
    // e.g., "1/128"
    private static final Pattern FRAC     = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)$");
    // strip trailing parenthetical, e.g., "1/128 (without ring)"
    private static final Pattern PAREN    = Pattern.compile("\\s*\\([^)]*\\)$", Pattern.UNICODE_CASE);
    // replace "in" with '/', e.g., "1 in 128"
    private static final Pattern IN_SYNT  = Pattern.compile("\\bin\\b", Pattern.CASE_INSENSITIVE);
    // remove bracketed annotations like "[confirmation needed]"
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]");

    public DropItem(int itemId, String name, String rarity)
    {
        this.itemId = itemId;
        this.name = name;
        this.rarity = rarity;
    }

    /**
     * Convert a raw rarity string like "2/128" (or "1 in 128", "12.5%", ranges) into
     * normalized one-over form, preserving ranges, e.g., "1/64" or "1/64–1/32".
     */
    public String getOneOverRarity()
    {
        if (rarity == null) return "";
        String[] parts = rarity.split("\\s*;\\s*|,\\s+");
        return Arrays.stream(parts)
                .map(this::normalizeSegment)
                .collect(Collectors.joining("; "));
    }

    /**
     * Parse the rarity and return its numeric denominator. Unknown/unsupported
     * values become POSITIVE_INFINITY so they sort as the rarest.
     */
    public double getRarityValue()
    {
        String oneOver = getOneOverRarity();
        if (oneOver.isEmpty())
        {
            return Double.POSITIVE_INFINITY;
        }

        Matcher m = Pattern.compile("1/(\\d+(?:\\.\\d+)?)").matcher(oneOver);
        if (m.find())
        {
            try { return Double.parseDouble(m.group(1)); }
            catch (NumberFormatException ex) { return Double.POSITIVE_INFINITY; }
        }

        if (oneOver.equalsIgnoreCase("Always"))
        {
            return 0d;
        }

        return Double.POSITIVE_INFINITY;
    }

    private String normalizeSegment(String raw)
    {
        String cleaned = raw == null ? "" : raw;
        cleaned = BRACKETS.matcher(cleaned).replaceAll("");
        cleaned = cleaned
                .replace("×", "x")
                .replace(",", "")
                .replace("≈", "")
                .replace("~", "")
                .replaceAll(PAREN.pattern(), "")
                .replaceAll(IN_SYNT.pattern(), "/")
                .trim();

        // Handle ranges like "1/128 – 1/64"
        String[] range = cleaned.split("\\s*[–—-]\\s*");
        if (range.length > 1)
        {
            return Arrays.stream(range)
                    .map(this::simplifySingle)
                    .collect(Collectors.joining("–"));
        }

        return simplifySingle(cleaned);
    }

    private String simplifySingle(String s)
    {
        if (s == null || s.isEmpty())
        {
            return "";
        }

        Matcher m;

        m = PCT.matcher(s);
        if (m.matches())
        {
            double pct = safeDouble(m.group(1));
            if (pct == 0) return "0";
            return formatOneOver(100.0 / pct);
        }

        m = MULT.matcher(s);
        if (m.matches())
        {
            double factor = safeDouble(m.group(1));
            double a = safeDouble(m.group(2));
            double b = safeDouble(m.group(3));
            if (factor != 0 && a != 0)
            {
                return formatOneOver(b / (a * factor));
            }
        }

        m = FRAC.matcher(s);
        if (m.matches())
        {
            double a = safeDouble(m.group(1));
            double b = safeDouble(m.group(2));
            if (a != 0)
            {
                return formatOneOver(b / a);
            }
        }

        // fallback to cleaned input
        return s;
    }

    private double safeDouble(String s)
    {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return Double.NaN; }
    }

    private String formatOneOver(double val)
    {
        if (Double.isNaN(val) || Double.isInfinite(val))
        {
            return "";
        }
        if (Math.abs(val - Math.round(val)) < 0.01)
        {
            return "1/" + Math.round(val);
        }
        return String.format(Locale.ROOT, "1/%.2f", val);
    }
}
