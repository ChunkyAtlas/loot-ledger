package com.lootledger.managers;

import com.google.gson.Gson;
import com.lootledger.account.AccountManager;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static net.runelite.client.RuneLite.RUNELITE_DIR;

/**
 * Obtained-items persistence with atomic writes and rotating backups (per account).
 *
 * <p>JSON lives at: ~/.runelite/lootledger/<account>/obtained.json
 * Backups are stored under ~/.runelite/lootledger/<account>/backups (keeps 10).
 */
@Slf4j
@Singleton
public class ObtainedItemsManager
{
    public enum Scope { PER_ACCOUNT, PER_NPC }

    @Inject private AccountManager accountManager;
    @Inject private Gson gson;

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // In-memory state keyed by account. Only ever load/save the current account.
    private final Map<String, AccountRecord> data = Collections.synchronizedMap(new LinkedHashMap<>());

    private static final String FILE_NAME = "obtained.json";
    private static final int MAX_BACKUPS = 10;

    public static final class AccountRecord {
        public final Set<Integer> all = Collections.synchronizedSet(new LinkedHashSet<>());
        public final Map<Integer, Set<Integer>> npcs = Collections.synchronizedMap(new LinkedHashMap<>()); // npcId -> itemIds
    }

    /** Load the current account's file into memory (creates empty file on first run). */
    public synchronized void load()
    {
        final String account = accountManager.getPlayerName();
        if (account == null || account.isEmpty()) {
            log.debug("ObtainedItemsManager.load: no account yet");
            return;
        }

        final Path file = fileFor(account);
        try {
            Files.createDirectories(file.getParent());
            AccountRecord rec = new AccountRecord();
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    AccountRecord loaded = gson.fromJson(r, AccountRecord.class);
                    if (loaded != null) rec = sanitize(loaded);
                }
            }
            data.put(account, rec);
        } catch (IOException e) {
            log.warn("Failed to load obtained file for {}", account, e);
            data.putIfAbsent(account, new AccountRecord());
        }
    }

    /** Persist the current account's record asynchronously with atomic replace + rotating backups. */
    public void save()
    {
        final String account = accountManager.getPlayerName();
        if (account == null || account.isEmpty()) return;
        final AccountRecord rec;
        synchronized (this) {
            rec = data.computeIfAbsent(account, k -> new AccountRecord());
        }

        io.submit(() -> doSave(account, rec));
    }

    private void doSave(String account, AccountRecord rec)
    {
        final Path file = fileFor(account);
        try {
            Files.createDirectories(file.getParent());

            // Rotate existing file to backups
            if (Files.exists(file)) {
                Path backups = file.getParent().resolve("backups");
                Files.createDirectories(backups);
                String ts = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                Path bak = backups.resolve(FILE_NAME + "." + ts + ".bak");
                safeMove(file, bak, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

                // Prune old backups
                try {
                    Files.list(backups)
                            .filter(p -> p.getFileName().toString().startsWith(FILE_NAME + "."))
                            .sorted(Comparator.comparing(Path::getFileName).reversed())
                            .skip(MAX_BACKUPS)
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                            });
                } catch (IOException ignore) { }
            }

            // Write JSON to tmp then atomically replace
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(rec, w);
            }
            safeMove(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e) {
            log.error("Failed to save obtained items for {}", account, e);
        }
    }

    private static void safeMove(Path source, Path target, CopyOption... opts) throws IOException
    {
        try {
            Files.move(source, target, opts);
        } catch (AtomicMoveNotSupportedException | AccessDeniedException ex) {
            // Retry without ATOMIC_MOVE but with REPLACE_EXISTING
            Set<CopyOption> fallback = new HashSet<>(Arrays.asList(opts));
            fallback.remove(StandardCopyOption.ATOMIC_MOVE);
            fallback.add(StandardCopyOption.REPLACE_EXISTING);
            Files.move(source, target, fallback.toArray(new CopyOption[0]));
        }
    }

    private static AccountRecord sanitize(AccountRecord r)
    {
        AccountRecord out = new AccountRecord();
        if (r == null) return out;
        // copy PER_ACCOUNT set
        if (r.all != null) {
            out.all.addAll(r.all);
        }
        // copy PER_NPC map (and wrap each set)
        if (r.npcs != null) {
            for (Map.Entry<Integer, Set<Integer>> e : r.npcs.entrySet()) {
                Set<Integer> src = (e.getValue() != null) ? e.getValue() : Collections.emptySet();
                out.npcs.put(e.getKey(), Collections.synchronizedSet(new LinkedHashSet<>(src)));
            }
        }
        return out;
    }

    private static Path baseDirFor(String account)
    {
        return RUNELITE_DIR.toPath().resolve("lootledger").resolve(account);
    }

    private static Path fileFor(String account)
    {
        return baseDirFor(account).resolve(FILE_NAME);
    }

    public synchronized boolean isObtained(String account, int npcId, int itemId, Scope scope)
    {
        AccountRecord r = data.computeIfAbsent(account, k -> new AccountRecord());
        if (scope == Scope.PER_ACCOUNT) {
            return r.all.contains(itemId);
        }
        return r.npcs.getOrDefault(npcId, Collections.emptySet()).contains(itemId);
    }

    public synchronized void markObtained(String account, int npcId, int itemId, Scope scope)
    {
        AccountRecord r = data.computeIfAbsent(account, k -> new AccountRecord());
        if (scope == Scope.PER_ACCOUNT) {
            r.all.add(itemId);
        } else {
            r.npcs.computeIfAbsent(npcId, x -> Collections.synchronizedSet(new LinkedHashSet<>())).add(itemId);
        }
        save();
    }

    public synchronized void unmarkObtained(String account, int npcId, int itemId, Scope scope)
    {
        AccountRecord r = data.computeIfAbsent(account, k -> new AccountRecord());
        if (scope == Scope.PER_ACCOUNT) {
            r.all.remove(itemId);
        } else {
            Set<Integer> set = r.npcs.get(npcId);
            if (set != null) {
                set.remove(itemId);
                if (set.isEmpty()) {
                    r.npcs.remove(npcId);
                }
            }
        }
        save();
    }

    /** Toggle obtained state; returns the new state (true if now obtained). */
    public synchronized boolean toggleObtained(String account, int npcId, int itemId, Scope scope)
    {
        boolean currently = isObtained(account, npcId, itemId, scope);
        if (currently) {
            unmarkObtained(account, npcId, itemId, scope);
            return false;
        } else {
            markObtained(account, npcId, itemId, scope);
            return true;
        }
    }

    public synchronized Set<Integer> getObtainedSet(String account, int npcId, Scope scope)
    {
        AccountRecord r = data.computeIfAbsent(account, k -> new AccountRecord());
        Set<Integer> out = new LinkedHashSet<>();
        if (scope == Scope.PER_ACCOUNT) {
            out.addAll(r.all);
        } else {
            out.addAll(r.npcs.getOrDefault(npcId, Collections.emptySet()));
        }
        return out;
    }

    public void shutdown()
    {
        // final synchronous save so we don't lose anything
        final String account = accountManager.getPlayerName();
        if (account != null && !account.isEmpty())
        {
            final AccountRecord rec;
            synchronized (this)
            {
                rec = data.getOrDefault(account, new AccountRecord());
            }
            // Synchronous write (does not use the executor)
            doSave(account, rec);
        }

        // stop the background IO thread
        io.shutdown();
        try
        {
            if (!io.awaitTermination(3, TimeUnit.SECONDS))
            {
                io.shutdownNow();
                io.awaitTermination(2, TimeUnit.SECONDS);
            }
        }
        catch (InterruptedException ie)
        {
            io.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
