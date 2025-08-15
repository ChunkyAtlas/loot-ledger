package com.lootledger.ui;

import com.lootledger.drops.DropCache;
import com.lootledger.drops.NpcDropData;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Provides fuzzy search over available NPC drop data. The cache is consulted
 * first and any misses fall back to a wiki lookup. Results without drop tables
 * are discarded and lookups for multiple candidates are performed in parallel
 * to keep searches snappy.
 */
@Singleton
public class NpcSearchService
{
    private static final Pattern ID_LEVEL_PATTERN  = Pattern.compile("^(\\d+)\\s+(?:lvl|level)?\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_LVL_PATTERN  = Pattern.compile("^(.*)\\s+(?:lvl|level)\\s*(\\d+)$",       Pattern.CASE_INSENSITIVE);
    private static final Pattern LVL_NAME_PATTERN  = Pattern.compile("^(?:lvl|level)\\s*(\\d+)\\s+(.*)$",       Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_NUM_PATTERN  = Pattern.compile("^(.*\\D)\\s+(\\d+)$");
    private static final Pattern NUM_NAME_PATTERN  = Pattern.compile("^(\\d+)\\s+(\\D.*)$");

    private static final int NAME_FETCH_LIMIT = 10;

    private final DropCache dropCache;

    @Inject
    public NpcSearchService(DropCache dropCache)
    {
        this.dropCache = dropCache;
    }

    private static final class ParsedQuery
    {
        Integer npcId;
        Integer level;
        String  name;
    }

    private static ParsedQuery parse(String q)
    {
        if (q == null) return null;
        String s = q.trim();
        if (s.isEmpty()) return null;

        ParsedQuery pq = new ParsedQuery();
        Matcher m;

        // pure ID
        if (s.matches("\\d+"))
        {
            pq.npcId = Integer.valueOf(s);
            return pq;
        }
        // ID + level
        m = ID_LEVEL_PATTERN.matcher(s);
        if (m.matches())
        {
            pq.npcId = Integer.valueOf(m.group(1));
            pq.level = Integer.valueOf(m.group(2));
            return pq;
        }
        // name + level
        m = NAME_LVL_PATTERN.matcher(s);
        if (m.matches())
        {
            pq.name  = m.group(1).trim();
            pq.level = Integer.valueOf(m.group(2));
            return pq;
        }
        // level + name
        m = LVL_NAME_PATTERN.matcher(s);
        if (m.matches())
        {
            pq.level = Integer.valueOf(m.group(1));
            pq.name  = m.group(2).trim();
            return pq;
        }
        // trailing number = level
        m = NAME_NUM_PATTERN.matcher(s);
        if (m.matches())
        {
            pq.name  = m.group(1).trim();
            pq.level = Integer.valueOf(m.group(2));
            return pq;
        }
        // leading number = level
        m = NUM_NAME_PATTERN.matcher(s);
        if (m.matches())
        {
            pq.level = Integer.valueOf(m.group(1));
            pq.name  = m.group(2).trim();
            return pq;
        }

        // fallback to pure name
        pq.name = s;
        return pq;
    }

    /**
     * Search by partial name, level, or ID. Results are limited and ordered by
     * Levenshtein distance when appropriate.
     */
    public List<NpcDropData> search(String query)
    {
        ParsedQuery pq = parse(query);
        if (pq == null)
        {
            return Collections.emptyList();
        }

        // 1) name only → fetch all candidates by name
        if (pq.npcId == null && pq.level == null && pq.name != null)
        {
            List<String> names = safeJoin(dropCache.searchNpcNames(pq.name), Collections.emptyList());
            List<NpcDropData> fetched = fetchAll(names.stream().limit(NAME_FETCH_LIMIT).collect(Collectors.toList()), 0);
            final String key = pq.name.toLowerCase(Locale.ROOT);
            return dedupeById(fetched).stream()
                    .sorted(Comparator.comparingInt(d -> levenshtein(d.getName().toLowerCase(Locale.ROOT), key)))
                    .collect(Collectors.toList());
        }

        // 2) ID only → fetch by ID
        if (pq.npcId != null && pq.name == null)
        {
            int lvl = (pq.level != null ? pq.level : 0);
            NpcDropData d = safeJoin(dropCache.get(pq.npcId, "", lvl), null);
            if (d == null || d.getDropTableSections() == null || d.getDropTableSections().isEmpty())
            {
                return Collections.emptyList();
            }
            return Collections.singletonList(d);
        }

        // 3) mixed or partial → fuzzy search
        String nameFilter = (pq.name != null ? pq.name : "");
        int    lvlFilter  = (pq.level != null ? pq.level : -1);

        List<String> candidates = safeJoin(dropCache.searchNpcNames(nameFilter), Collections.emptyList());
        List<NpcDropData> all = fetchAll(candidates.stream().limit(NAME_FETCH_LIMIT).collect(Collectors.toList()),
                lvlFilter > -1 ? lvlFilter : 0);

        // if ID also provided, filter it
        if (pq.npcId != null)
        {
            all = all.stream().filter(d -> d.getNpcId() == pq.npcId).collect(Collectors.toList());
        }

        final int lvl = lvlFilter;
        final String key = nameFilter.toLowerCase(Locale.ROOT);
        return dedupeById(all).stream()
                .filter(d -> lvl < 0 || d.getLevel() == lvl)
                .sorted(Comparator.comparingInt(d -> levenshtein(d.getName().toLowerCase(Locale.ROOT), key)))
                .collect(Collectors.toList());
    }

    /** Fetch drop data for a list of names concurrently (exception-safe). */
    private List<NpcDropData> fetchAll(List<String> names, int level)
    {
        List<CompletableFuture<NpcDropData>> futures = names.stream()
                .map(n -> dropCache.get(0, n, level))
                .collect(Collectors.toList());

        // Wait for *all* without propagating the first failure
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        return futures.stream()
                .map(f -> f.exceptionally(ex -> null).join())
                .filter(Objects::nonNull)
                .filter(d -> d.getDropTableSections() != null && !d.getDropTableSections().isEmpty())
                .collect(Collectors.toList());
    }

    private static <T> T safeJoin(CompletableFuture<T> f, T fallback)
    {
        try { return f.join(); }
        catch (Exception ignored) { return fallback; }
    }

    private static List<NpcDropData> dedupeById(List<NpcDropData> list)
    {
        Map<Integer, NpcDropData> byId = new LinkedHashMap<>();
        for (NpcDropData d : list)
        {
            // Keep the first occurrence per NPC ID
            byId.putIfAbsent(d.getNpcId(), d);
        }
        return new ArrayList<>(byId.values());
    }

    // simple DP Levenshtein
    private static int levenshtein(String a, String b)
    {
        int[][] dp = new int[a.length()+1][b.length()+1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++)
            for (int j = 1; j <= b.length(); j++)
                dp[i][j] = Math.min(
                        Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1),
                        dp[i-1][j-1] + (a.charAt(i-1)==b.charAt(j-1) ? 0 : 1)
                );
        return dp[a.length()][b.length()];
    }
}
