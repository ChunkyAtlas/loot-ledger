package com.lootledger.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.events.VarClientIntChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import com.lootledger.LootLedgerConfig;

import javax.inject.Inject;
import javax.inject.Singleton;

@Slf4j
@Singleton
public class TabListener
{
    private final Client client;
    private final ClientThread clientThread;
    private final MusicWidgetController widgetController;
    private final LootLedgerConfig config;
    private static final int INVENTORY_TAB_VARC = 171;

    @Inject
    public TabListener(
            Client client,
            ClientThread clientThread,
            MusicWidgetController widgetController,
            LootLedgerConfig config
    )
    {
        this.client = client;
        this.clientThread = clientThread;
        this.widgetController = widgetController;
        this.config = config;
    }

    @Subscribe
    public void onVarClientIntChanged(VarClientIntChanged ev)
    {
        if (ev.getIndex() != INVENTORY_TAB_VARC) return;

        int newTab = client.getVarcIntValue(INVENTORY_TAB_VARC);
        if (widgetController.isOverrideActive() && newTab != 13)
        {
            if (!config.showDropsAlwaysOpen())
            {
                clientThread.invokeLater(widgetController::restore);
            }
            return;
        }

        if (!widgetController.isOverrideActive() && newTab == 13 && widgetController.hasData())
        {
            clientThread.invokeLater(() ->
                    widgetController.override(widgetController.getCurrentData())
            );
        }
    }
}
