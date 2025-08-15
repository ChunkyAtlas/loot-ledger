package com.lootledger.ui;

import com.lootledger.drops.DropCache;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
public class DropsMenuListener
{
    private final Client client;
    private final ClientThread clientThread;
    private final DropCache dropCache;
    private final MusicWidgetController widgetController;

    @Inject
    public DropsMenuListener(
            Client client,
            ClientThread clientThread,
            DropCache dropCache,
            MusicWidgetController widgetController
    )
    {
        this.client = client;
        this.clientThread = clientThread;
        this.dropCache = dropCache;
        this.widgetController = widgetController;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        final List<MenuEntry> entries = new ArrayList<>(Arrays.asList(event.getMenuEntries()));

        NPC target = null;
        MenuEntry anchorEntry = null; // fallback anchor (any NPC entry)
        int anchorIdx = -1;

        MenuEntry attackEntry = null;  // preferred anchor if present
        int attackIdx = -1;

        for (int i = 0; i < entries.size(); i++)
        {
            final MenuEntry e = entries.get(i);
            final MenuAction type = e.getType();

            // Consider any NPC option line as a valid anchor
            if (isNpcMenuAction(type))
            {
                // Resolve the NPC by identifier; guard for bad indices
                try
                {
                    NPC possible = client.getTopLevelWorldView().npcs().byIndex(e.getIdentifier());
                    if (possible != null)
                    {
                        // Record the first NPC entry as a general anchor
                        if (anchorEntry == null)
                        {
                            anchorEntry = e;
                            anchorIdx = i;
                            target = possible;
                        }

                        // If this line is Attack, prefer it
                        if ("Attack".equalsIgnoreCase(e.getOption()))
                        {
                            attackEntry = e;
                            attackIdx = i;
                            target = possible;
                            break; // Prefer Attack; stop scanning
                        }
                    }
                }
                catch (ArrayIndexOutOfBoundsException ex)
                {
                    // ignore invalid indices
                }
            }
        }

        // Choose the best available anchor
        final MenuEntry useEntry = (attackEntry != null ? attackEntry : anchorEntry);
        final int useIdx = (attackEntry != null ? attackIdx : anchorIdx);

        if (useEntry == null || target == null)
        {
            return; // no NPC line in the menu
        }

        final int id = target.getId();
        final String name = target.getName();
        final int level = target.getCombatLevel();

        // Build our custom menu entry; position near the anchor line
        final MenuEntry showDrops = WidgetUtils.createShowDropsEntry(
                client,
                Math.max(0, useIdx - 1),
                useEntry
        );
        showDrops.onClick(me -> fetchAndDisplayDrops(id, name, level, 1));

        entries.add(useIdx + 1, showDrops);
        event.setMenuEntries(entries.toArray(new MenuEntry[0]));
    }

    private static boolean isNpcMenuAction(MenuAction type)
    {
        return type == MenuAction.NPC_FIRST_OPTION
                || type == MenuAction.NPC_SECOND_OPTION
                || type == MenuAction.NPC_THIRD_OPTION
                || type == MenuAction.NPC_FOURTH_OPTION
                || type == MenuAction.NPC_FIFTH_OPTION
                || type == MenuAction.EXAMINE_NPC;
    }

    private void fetchAndDisplayDrops(int id, String name, int level, int attemptsLeft)
    {
        dropCache.get(id, name, level)
                .whenComplete((dropData, ex) ->
                {
                    if (dropData != null && ex == null)
                    {
                        clientThread.invokeLater(() -> widgetController.override(dropData));
                        return;
                    }

                    if (attemptsLeft > 0)
                    {
                        fetchAndDisplayDrops(id, name, level, attemptsLeft - 1);
                    }
                    else
                    {
                        if (ex != null) {
                            log.error("Failed to fetch drop data for {}", name, ex);
                        } else {
                            log.error("Failed to fetch drop data for {} (no error cause)", name);
                        }
                    }
                });
    }
}
