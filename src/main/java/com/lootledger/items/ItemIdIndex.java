package com.lootledger.items;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Loads a resource JSON mapping of ITEM_NAME -> [itemIds...] and offers
 * flexible lookups that handle both "Foo (Bar)" and "Foo#Bar" variants.
 */
@Slf4j
public final class ItemIdIndex
{
    private static final String RESOURCE_PATH = "/com/lootledger/Items.json"; // classpath resource
    private static volatile Gson GSON;
    private static final Type TYPE = new TypeToken<Map<String, List<Integer>>>() {}.getType();

    // normalized name -> primitive int[] (fast, heap-friendly)
    private static volatile Map<String, int[]> index = Collections.emptyMap();

    private ItemIdIndex() {}

    public static void setGson(Gson gson) {
        GSON = Objects.requireNonNull(gson, "gson");
    }

    public static synchronized void load() {
        if (GSON == null) {
            throw new IllegalStateException("ItemIdIndex Gson not set");
        }
        try (InputStream is = ItemIdIndex.class.getResourceAsStream(RESOURCE_PATH)) {
            if (is == null) {
                log.warn("Items.json not found on classpath at {}", RESOURCE_PATH);
                index = Collections.emptyMap();
                return;
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Map<String, List<Integer>> raw = GSON.fromJson(reader, TYPE);
                Map<String, int[]> tmp = new HashMap<>(raw.size());
                for (Map.Entry<String, List<Integer>> e : raw.entrySet()) {
                    String key = normalize(e.getKey());
                    List<Integer> ids = e.getValue();
                    if (ids == null || ids.isEmpty()) { continue; }
                    int[] arr = new int[ids.size()];
                    for (int i = 0; i < ids.size(); i++) arr[i] = ids.get(i);
                    tmp.put(key, arr);
                }
                index = Collections.unmodifiableMap(tmp);
                log.info("Loaded {} item-name keys from Items.json", index.size());
            }
        } catch (Exception ex) {
            log.error("Failed to load Items.json", ex);
            index = Collections.emptyMap();
        }
    }

    /** Returns candidate IDs for an item name, trying several key variations. */
    public static int[] findIdsFlex(String itemName)
    {
        if (itemName == null || itemName.isEmpty()) return new int[0];
        String n = normalize(itemName);

        // Try exact
        int[] ids = index.get(n);
        if (ids != null) return ids;

        // Try converting "Foo (Bar)" → "Foo#Bar"
        String hashVariant = toHashVariant(n);
        if (!hashVariant.equals(n))
        {
            ids = index.get(hashVariant);
            if (ids != null) return ids;
        }

        // Try removing trailing decorations after '#'
        int hash = n.indexOf('#');
        if (hash > 0)
        {
            String base = n.substring(0, hash).trim();
            ids = index.get(base);
            if (ids != null) return ids;
        }

        // As a last resort, try stripping all parentheses
        String parenStripped = n.replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (!parenStripped.equals(n))
        {
            ids = index.get(parenStripped);
            if (ids != null) return ids;
        }

        return new int[0];
    }

    /**
     * Picks the most suitable ID from a set, preferring canonical (non-noted/non-placeholder) IDs
     * using ItemManager#canonicalize to avoid API differences across RuneLite versions.
     */
    public static int pickBestId(ItemManager itemManager, int[] candidates)
    {
        if (candidates == null || candidates.length == 0) return 0;
        int fallback = candidates[0];
        if (itemManager == null) return fallback;

        int firstCanonical = 0;
        for (int id : candidates)
        {
            try
            {
                int canon = itemManager.canonicalize(id);
                if (firstCanonical == 0) firstCanonical = canon;
                // Prefer IDs that are already canonical (i.e., not noted/placeholders)
                if (canon == id)
                {
                    return id;
                }
            }
            catch (Exception ignored) {}
        }
        // If none were already canonical, return the first canonical version as a reasonable default
        return firstCanonical != 0 ? firstCanonical : fallback;
    }

    private static String normalize(String s)
    {
        return s.toLowerCase(Locale.ROOT).replace('\u00A0', ' ').trim(); // collapse nbsp → space
    }

    /** Convert "foo (bar)" to "foo#bar" for keys that use hash disambiguators. */
    private static String toHashVariant(String s)
    {
        // e.g. "adamant dagger (p++)" → "adamant dagger#(p++)"
        int i = s.indexOf('(');
        if (i > 0 && s.endsWith(")"))
        {
            String base = s.substring(0, i).trim();
            String paren = s.substring(i).replace(" (", "#");
            return (base + paren).trim();
        }
        return s;
    }
}
