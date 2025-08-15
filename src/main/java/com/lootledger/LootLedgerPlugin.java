package com.lootledger;

import com.google.inject.Provides;
import com.google.gson.Gson;
import com.lootledger.account.AccountManager;
import com.lootledger.drops.DropCache;
import com.lootledger.drops.DropFetcher;
import com.lootledger.items.ItemIdIndex;
import com.lootledger.managers.ObtainedItemsManager;
import com.lootledger.ui.DropsMenuListener;
import com.lootledger.ui.MusicWidgetController;
import com.lootledger.ui.DropsTooltipOverlay;
import com.lootledger.ui.TabListener;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.TileItem;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ItemSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@PluginDescriptor(
        name = "Loot Ledger",
        description = "Show drop tables in the Music tab with obtained tracking",
        tags = {"drops","loot","wiki"}
)
public class LootLedgerPlugin extends Plugin
{
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private Gson gson;
    @Inject private ItemManager itemManager;
    @Inject private LootLedgerConfig config;
    @Inject private AccountManager accountManager;
    @Inject private DropFetcher dropFetcher;
    @Inject private DropCache dropCache;
    @Inject private MusicWidgetController musicWidgetController;
    @Inject private DropsMenuListener dropsMenuListener;
    @Inject private TabListener tabListener;
    @Inject private ObtainedItemsManager obtainedItems;
    @Inject private EventBus eventBus;
    @Inject private OverlayManager overlayManager;
    @Inject private DropsTooltipOverlay dropsTooltipOverlay;

    @Provides
    LootLedgerConfig provideConfig(ConfigManager cm) { return cm.getConfig(LootLedgerConfig.class); }

    @Override protected void startUp()
    {
        ItemIdIndex.setGson(gson);
        ItemIdIndex.load();
        accountManager.init();
        dropFetcher.startUp();
        dropCache.startUp();
        obtainedItems.load();
        eventBus.register(accountManager);
        eventBus.register(tabListener);
        eventBus.register(dropsMenuListener);
        overlayManager.add(dropsTooltipOverlay);
    }

    @Override protected void shutDown()
    {
        musicWidgetController.restore();
        obtainedItems.save();
        obtainedItems.shutdown();
        eventBus.unregister(accountManager);
        eventBus.unregister(tabListener);
        eventBus.unregister(dropsMenuListener);
        overlayManager.remove(dropsTooltipOverlay);
        dropFetcher.shutdown();
    }

    // Refresh the viewer live when relevant config toggles change
    @Subscribe
    public void onConfigChanged(ConfigChanged e)
    {
        if (!"lootledger".equals(e.getGroup()))
        {
            return;
        }
        // Any of these affect visibility/ordering of items; re-render if open
        String k = e.getKey();
        if ("trackObtained".equals(k)
                || "obtainedScope".equals(k)
                || "obtainedView".equals(k)
                || "showRareDropTable".equals(k)
                || "showGemDropTable".equals(k)
                || "sortDropsByRarity".equals(k))
        {
            refreshIfShowing();
        }
    }

    @Subscribe
    public void onItemSpawned(ItemSpawned event)
    {
        if (!config.trackObtained()) return;

        final TileItem tileItem = event.getItem();
        if (tileItem.getOwnership() != TileItem.OWNERSHIP_SELF)
        {
            return;
        }

        final int canonicalId = itemManager.canonicalize(tileItem.getId());
        final String account = accountManager.getPlayerName();
        if (account == null)
        {
            return;
        }

        final ObtainedItemsManager.Scope scope = mapScope(config.obtainedScope());
        final int npcIdContext = musicWidgetController.hasData() && musicWidgetController.getCurrentData() != null
                ? musicWidgetController.getCurrentData().getNpcId() : 0;

        if (!obtainedItems.isObtained(account, npcIdContext, canonicalId, scope))
        {
            obtainedItems.markObtained(account, npcIdContext, canonicalId, scope);
            refreshIfShowing();
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        if (!config.trackObtained()) return;

        // 93 = inventory
        if (event.getContainerId() != 93)
        {
            return;
        }

        final String account = accountManager.getPlayerName();
        if (account == null)
        {
            return;
        }

        final Set<Integer> processed = new HashSet<>();
        final ItemContainer c = event.getItemContainer();
        if (c == null)
        {
            return;
        }

        final ObtainedItemsManager.Scope scope = mapScope(config.obtainedScope());
        final int npcIdContext = musicWidgetController.hasData() && musicWidgetController.getCurrentData() != null
                ? musicWidgetController.getCurrentData().getNpcId() : 0;

        for (Item item : c.getItems())
        {
            if (item == null) continue;
            final int canonicalId = itemManager.canonicalize(item.getId());
            if (processed.contains(canonicalId))
            {
                continue;
            }

            if (!obtainedItems.isObtained(account, npcIdContext, canonicalId, scope))
            {
                obtainedItems.markObtained(account, npcIdContext, canonicalId, scope);
                processed.add(canonicalId);
            }
        }

        if (!processed.isEmpty())
        {
            refreshIfShowing();
        }
    }

    private void refreshIfShowing()
    {
        if (musicWidgetController.hasData() && musicWidgetController.getCurrentData() != null)
        {
            musicWidgetController.override(musicWidgetController.getCurrentData());
        }
    }

    private ObtainedItemsManager.Scope mapScope(LootLedgerConfig.Scope s)
    {
        return s == LootLedgerConfig.Scope.PER_NPC ?
                ObtainedItemsManager.Scope.PER_NPC :
                ObtainedItemsManager.Scope.PER_ACCOUNT;
    }
}
