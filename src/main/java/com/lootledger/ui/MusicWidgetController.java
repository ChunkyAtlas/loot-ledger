package com.lootledger.ui;

import com.lootledger.LootLedgerConfig;
import com.lootledger.account.AccountManager;
import com.lootledger.drops.DropItem;
import com.lootledger.drops.NpcDropData;
import com.lootledger.managers.ObtainedItemsManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptEvent;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.widgets.ItemQuantityMode;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class MusicWidgetController
{
    private static final int MUSIC_GROUP = 239;
    private static final int ICON_SIZE = 32;
    private static final int PADDING = 4;
    private static final int COLUMNS = 4;
    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 8;
    private static final int BAR_HEIGHT = 15;
    private static final float WIDTH_RATIO = 0.7f;
    private static final int EYE_SIZE = 20;
    private static final int SEARCH_SPRITE = 1113;

    private final Client client;
    private final ClientThread clientThread;
    private final ObtainedItemsManager obtainedItemsManager;
    private final AccountManager accountManager;
    private final SpriteOverrideManager spriteOverrideManager;
    private final ItemSpriteCache itemSpriteCache;
    private final LootLedgerConfig config;
    private final NpcSearchService searchService;

    private NpcDropData currentDrops = null;
    private List<Widget> backupRootStaticKids = null;
    private List<Widget> backupRootDynamicKids = null;
    private List<Widget> backupJukeboxStaticKids = null;
    private List<Widget> backupJukeboxDynamicKids = null;
    private List<Widget> backupScrollStaticKids = null;
    private List<Widget> backupScrollDynamicKids = null;
    private Integer originalRootType = null;
    private String originalTitleText = null;
    private final List<Widget> overrideRootWidgets = new ArrayList<>();
    private final List<Widget> overrideScrollWidgets = new ArrayList<>();
    @Getter private final Map<Widget, DropItem> iconItemMap = new LinkedHashMap<>();
    @Getter private boolean overrideActive = false;
    private boolean hideObtainedItems = false;

    @Inject
    public MusicWidgetController(
            Client client,
            ClientThread clientThread,
            ObtainedItemsManager obtainedItemsManager,
            AccountManager accountManager,
            SpriteOverrideManager spriteOverrideManager,
            ItemSpriteCache itemSpriteCache,
            LootLedgerConfig config,
            NpcSearchService searchService)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.obtainedItemsManager = obtainedItemsManager;
        this.accountManager = accountManager;
        this.spriteOverrideManager = spriteOverrideManager;
        this.itemSpriteCache = itemSpriteCache;
        this.config = config;
        this.searchService = searchService;
    }

    public boolean hasData() { return currentDrops != null; }
    public NpcDropData getCurrentData() { return currentDrops; }

    /**
     * If the music widget group reloads while we're overriding, re-apply the override.
     * This prevents stale widget references / listeners.
     */
    @Subscribe
    public void onWidgetLoaded(WidgetLoaded ev)
    {
        if (ev.getGroupId() != MUSIC_GROUP)
        {
            return;
        }

        if (!overrideActive || currentDrops == null)
        {
            return;
        }

        clientThread.invokeLater(() ->
        {
            // Chance Man hardening: re-check inside the runnable
            if (!overrideActive || currentDrops == null)
            {
                return;
            }

            resetWidgetCaches();
            applyOverride(currentDrops);
        });
    }

    /** Replace the music widget with a drop table view for the given NPC. */
    public void override(NpcDropData dropData)
    {
        if (dropData == null) { return; }
        currentDrops = dropData;
        hideObtainedItems = false;

        if (!overrideActive)
        {
            overrideActive = true;
            clientThread.invokeLater(() ->
            {
                applyOverride(dropData);
                spriteOverrideManager.register();
            });
        }
        else
        {
            clientThread.invokeLater(() -> applyOverride(dropData));
        }
    }

    /** Remove the drop table overlay and restore the original music widget. */
    public void restore()
    {
        if (!overrideActive) { return; }
        spriteOverrideManager.unregister();
        itemSpriteCache.clear();
        hideObtainedItems = false;
        clientThread.invokeLater(this::revertOverride);
    }

    private boolean isTracking() { return config.trackObtained(); }

    private Set<Integer> getObtainedIdsForCurrent()
    {
        if (!isTracking()) { return Collections.emptySet(); }
        if (currentDrops == null) { return Collections.emptySet(); }
        final String account = accountManager.getPlayerName();
        if (account == null) { return Collections.emptySet(); }
        return obtainedItemsManager.getObtainedSet(
                account,
                currentDrops.getName(),
                mapScope(config.obtainedScope())
        );
    }

    private void updateIconsVisibilityAndLayout()
    {
        if (currentDrops == null) { return; }

        final Set<Integer> obtainedIds = getObtainedIdsForCurrent();

        Widget scrollable = client.getWidget(MUSIC_GROUP, 4);
        Widget scrollbar = client.getWidget(MUSIC_GROUP, 7);

        int displayIndex = 0;
        for (Map.Entry<Widget, DropItem> e : iconItemMap.entrySet())
        {
            Widget icon = e.getKey();
            DropItem d = e.getValue();
            boolean obtained = obtainedIds.contains(d.getItemId());

            if (hideObtainedItems && obtained)
            {
                icon.setHidden(true);
            }
            else
            {
                icon.setHidden(false);
                int col = displayIndex % COLUMNS;
                int row = displayIndex / COLUMNS;
                int x = MARGIN_X + col * (ICON_SIZE + PADDING);
                int y = MARGIN_Y + row * (ICON_SIZE + PADDING);
                icon.setOriginalX(x);
                icon.setOriginalY(y);
                icon.setOpacity(!isTracking() ? 0 : (obtained ? 0 : 150));
                icon.revalidate();
                displayIndex++;
            }
        }

        int rows = (displayIndex + COLUMNS - 1) / COLUMNS;
        if (scrollable != null)
        {
            scrollable.setScrollHeight(MARGIN_Y * 2 + rows * (ICON_SIZE + PADDING));
            scrollable.revalidate();
        }
        if (scrollbar != null)
        {
            scrollbar.revalidateScroll();
        }
    }

    private static List<Widget> copyChildren(Widget parent, boolean dynamic)
    {
        if (parent == null)
        {
            return new ArrayList<>();
        }

        Widget[] kids = dynamic ? parent.getDynamicChildren() : parent.getChildren();
        if (kids == null || kids.length == 0)
        {
            return new ArrayList<>();
        }

        // filter nulls
        List<Widget> out = new ArrayList<>(kids.length);
        for (Widget w : kids)
        {
            if (w != null)
            {
                out.add(w);
            }
        }
        return out;
    }

    private static void restoreChildren(Widget parent, List<Widget> staticKids, List<Widget> dynamicKids)
    {
        if (parent == null) { return; }

        Widget[] currentStatic = parent.getChildren();
        if (currentStatic != null)
        {
            for (Widget w : currentStatic) { if (w != null) w.setHidden(true); }
        }

        Widget[] currentDyn = parent.getDynamicChildren();
        if (currentDyn != null)
        {
            for (Widget w : currentDyn) { if (w != null) w.setHidden(true); }
        }

        if (staticKids != null)
        {
            for (Widget w : staticKids)
            {
                if (w != null && w.getType() != 0) { w.setHidden(false); }
            }
        }
        if (dynamicKids != null)
        {
            for (Widget w : dynamicKids)
            {
                if (w != null && w.getType() != 0) { w.setHidden(false); }
            }
        }

        parent.revalidate();
    }

    private Widget updateTitle(NpcDropData dropData)
    {
        Widget title = client.getWidget(MUSIC_GROUP, 8);
        if (title != null)
        {
            if (originalTitleText == null) { originalTitleText = title.getText(); }
            title.setText(dropData.getName());
            title.revalidate();
        }
        return title;
    }

    private void drawProgressBarAndToggle(Widget root, Widget title, NpcDropData dropData, int obtainedCount, int totalDrops)
    {
        int fontId = title != null ? title.getFontId() : 0;
        boolean shadowed = title != null && title.getTextShadowed();

        final int CLOSE_SPRITE = 520;
        final int CLOSE_SIZE = 10;
        final int CLOSE_PAD = 4;

        Widget close = root.createChild(-1);
        overrideRootWidgets.add(close);
        close.setHidden(false);
        close.setType(WidgetType.GRAPHIC);
        close.setOriginalX(CLOSE_PAD);
        close.setOriginalY(CLOSE_PAD);
        close.setOriginalWidth(CLOSE_SIZE);
        close.setOriginalHeight(CLOSE_SIZE);
        close.setSpriteId(CLOSE_SPRITE);
        close.setAction(0, "Close");
        close.setOnOpListener((JavaScriptCallback) (ScriptEvent ev) -> restore());
        close.setHasListener(true);
        close.revalidate();

        int lvlX = Objects.requireNonNull(title).getOriginalX() + title.getOriginalWidth() + 83;
        int lvlY = title.getOriginalY();

        Widget lvl = root.createChild(-1);
        overrideRootWidgets.add(lvl);
        lvl.setHidden(false);
        lvl.setType(WidgetType.TEXT);
        lvl.setText(String.format("Lvl %d", dropData.getLevel()));
        lvl.setFontId(fontId);
        lvl.setTextShadowed(shadowed);
        lvl.setTextColor(0x00b33c);
        lvl.setOriginalX(lvlX);
        lvl.setOriginalY(lvlY);
        lvl.setOriginalWidth(title.getOriginalWidth());
        lvl.setOriginalHeight(title.getOriginalHeight());
        lvl.revalidate();

        Widget oldBar = client.getWidget(MUSIC_GROUP, 9);
        if (oldBar == null)
        {
            return;
        }

        int xOld = oldBar.getOriginalX();
        int yOld = oldBar.getOriginalY();
        int wOld = oldBar.getOriginalWidth();
        int hOld = oldBar.getOriginalHeight();

        int newW = Math.round(wOld * WIDTH_RATIO);
        int newY = yOld + (hOld - BAR_HEIGHT) / 2;

        Widget bg = root.createChild(-1);
        overrideRootWidgets.add(bg);
        bg.setHidden(false);
        bg.setType(WidgetType.RECTANGLE);
        bg.setOriginalX(xOld);
        bg.setOriginalY(newY);
        bg.setOriginalWidth(newW);
        bg.setOriginalHeight(BAR_HEIGHT);
        bg.setFilled(true);
        bg.setTextColor(0x000000);
        bg.revalidate();

        final int border = 1;
        int innerWidth = newW - border * 2;
        int fillW = totalDrops > 0 ? Math.round(innerWidth * (float) obtainedCount / totalDrops) : 0;

        Widget fill = root.createChild(-1);
        overrideRootWidgets.add(fill);
        fill.setHidden(false);
        fill.setType(WidgetType.RECTANGLE);
        fill.setOriginalX(xOld + border);
        fill.setOriginalY(newY + border);
        fill.setOriginalWidth(fillW);
        fill.setOriginalHeight(BAR_HEIGHT - border * 2);
        fill.setFilled(true);
        fill.setTextColor(0x00b33c);
        fill.revalidate();

        String txt = String.format("%d/%d", obtainedCount, totalDrops);
        Widget label = root.createChild(-1);
        overrideRootWidgets.add(label);
        label.setHidden(false);
        label.setType(WidgetType.TEXT);
        label.setText(txt);
        label.setTextColor(0xFFFFFF);
        label.setFontId(fontId);
        label.setTextShadowed(shadowed);
        label.setOriginalWidth(newW);
        label.setOriginalHeight(BAR_HEIGHT);
        label.setOriginalX(xOld + (newW / 2) - (txt.length() * 4));
        label.setOriginalY(newY + (BAR_HEIGHT / 2) - 6);
        label.revalidate();

        int eyeX = xOld + newW + 4;
        int eyeY = newY + (BAR_HEIGHT / 2) - (EYE_SIZE / 2);

        Widget eye = root.createChild(-1);
        overrideRootWidgets.add(eye);
        eye.setHidden(false);
        eye.setType(WidgetType.GRAPHIC);
        eye.setOriginalX(eyeX);
        eye.setOriginalY(eyeY);
        eye.setOriginalWidth(EYE_SIZE);
        eye.setOriginalHeight(EYE_SIZE);
        eye.setSpriteId(hideObtainedItems ? 2222 : 2221);
        eye.revalidate();

        if (isTracking())
        {
            eye.setAction(0, "Toggle obtained items");
            eye.setOnOpListener((JavaScriptCallback) (ScriptEvent ev) ->
            {
                hideObtainedItems = !hideObtainedItems;
                updateIconsVisibilityAndLayout();
                eye.setSpriteId(hideObtainedItems ? 2222 : 2221);
                eye.revalidate();
            });
            eye.setHasListener(true);
        }
        else
        {
            eye.setAction(0, "Enable tracking in settings to filter");
            eye.setHasListener(false);
        }

        int searchX = eyeX + EYE_SIZE + PADDING;

        Widget search = root.createChild(-1);
        overrideRootWidgets.add(search);
        search.setHidden(false);
        search.setType(WidgetType.GRAPHIC);
        search.setOriginalX(searchX);
        search.setOriginalY(eyeY);
        search.setOriginalWidth(EYE_SIZE);
        search.setOriginalHeight(EYE_SIZE);
        search.setSpriteId(SEARCH_SPRITE);
        search.revalidate();
        search.setAction(0, "Search Drops");
        search.setOnOpListener((JavaScriptCallback) ev -> showSearchDialog());
        search.setHasListener(true);

        root.revalidate();
    }

    /** Display a Swing dialog prompting the user for an NPC name or ID, then load and show. */
    private void showSearchDialog()
    {
        SwingUtilities.invokeLater(() ->
        {
            String query = JOptionPane.showInputDialog(
                    null,
                    "Enter NPC name or ID:",
                    "Search NPC",
                    JOptionPane.PLAIN_MESSAGE
            );
            if (query == null || query.trim().isEmpty()) { return; }

            final String q = query.trim();
            if (q.isEmpty()) { return; }

            Thread t = new Thread(() ->
            {
                List<NpcDropData> results = searchService.search(q);

                SwingUtilities.invokeLater(() ->
                {
                    if (results.isEmpty())
                    {
                        JOptionPane.showMessageDialog(
                                null,
                                "No NPCs found for: " + q,
                                "Search NPC",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                        return;
                    }

                    List<NpcDropData> limited = results.stream().limit(5).collect(Collectors.toList());
                    String[] choices = limited.stream()
                            .map(n -> String.format("%s (ID %d, Lvl %d)", n.getName(), n.getNpcId(), n.getLevel()))
                            .toArray(String[]::new);

                    int idx = JOptionPane.showOptionDialog(
                            null,
                            "Select NPC:",
                            "Search Results",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            choices,
                            choices[0]
                    );

                    if (idx >= 0 && idx < limited.size())
                    {
                        override(limited.get(idx));
                    }
                });
            }, "lootledger-search");

            t.setDaemon(true);
            t.start();
        });
    }

    private void drawDropIcons(Widget scrollable, Widget scrollbar, Widget jukebox, List<DropItem> drops, Set<Integer> obtainedIds)
    {
        if (scrollable == null || scrollbar == null) { return; }

        if (backupJukeboxStaticKids == null && jukebox != null) { backupJukeboxStaticKids = copyChildren(jukebox, false); }
        if (backupJukeboxDynamicKids == null && jukebox != null) { backupJukeboxDynamicKids = copyChildren(jukebox, true); }
        if (backupScrollStaticKids == null) { backupScrollStaticKids = copyChildren(scrollable, false); }
        if (backupScrollDynamicKids == null) { backupScrollDynamicKids = copyChildren(scrollable, true); }

        if (jukebox != null) WidgetUtils.hideAllChildrenSafely(jukebox);
        WidgetUtils.hideAllChildrenSafely(scrollable);

        boolean trackingOn = isTracking();

        for (DropItem d : drops)
        {
            final int itemId = d.getItemId();
            final boolean isObt = obtainedIds.contains(itemId);

            Widget icon = scrollable.createChild(-1);
            overrideScrollWidgets.add(icon);

            icon.setHidden(false);
            icon.setType(WidgetType.GRAPHIC);

            int spriteId = itemSpriteCache.getSpriteId(itemId);
            icon.setSpriteId(spriteId);
            icon.setItemQuantityMode(ItemQuantityMode.NEVER);

            icon.setOriginalX(MARGIN_X);
            icon.setOriginalY(MARGIN_Y);
            icon.setOriginalWidth(ICON_SIZE);
            icon.setOriginalHeight(ICON_SIZE);

            icon.setOpacity(trackingOn ? (isObt ? 0 : 150) : 0);
            icon.revalidate();

            if (trackingOn)
            {
                icon.setAction(0, isObt ? "Mark as Unobtained" : "Mark as Obtained");
                icon.setOnOpListener((JavaScriptCallback) (ScriptEvent ev) -> toggleObtained(itemId));
                icon.setHasListener(true);
            }
            else
            {
                icon.setAction(0, "Enable tracking in settings to toggle");
                icon.setHasListener(false);
            }

            iconItemMap.put(icon, d);
        }

        updateIconsVisibilityAndLayout();
    }

    private void toggleObtained(int itemId)
    {
        if (!isTracking()) { return; }
        if (currentDrops == null) { return; }
        final String account = accountManager.getPlayerName();
        if (account == null) { return; }

        final ObtainedItemsManager.Scope scope = mapScope(config.obtainedScope());
        final String npcName = currentDrops.getName();
        boolean already = obtainedItemsManager.isObtained(account, npcName, itemId, scope);

        if (already)
        {
            obtainedItemsManager.unmarkObtained(account, npcName, itemId, scope);
        }
        else
        {
            obtainedItemsManager.markObtained(account, npcName, itemId, scope);
        }

        override(currentDrops);
    }

    private void applyOverride(NpcDropData dropData)
    {
        purgeCreatedWidgets();
        iconItemMap.clear();

        Widget root = client.getWidget(MUSIC_GROUP, 0);
        Widget scrollable = client.getWidget(MUSIC_GROUP, 4);
        Widget jukebox = client.getWidget(MUSIC_GROUP, 6);
        Widget scrollbar = client.getWidget(MUSIC_GROUP, 7);

        if (backupRootStaticKids == null && root != null) { backupRootStaticKids = copyChildren(root, false); }
        if (backupRootDynamicKids == null && root != null) { backupRootDynamicKids = copyChildren(root, true); }
        if (originalRootType == null && root != null) { originalRootType = root.getType(); }

        int[] toHide = {9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};
        for (int childId : toHide)
        {
            Widget w = client.getWidget(MUSIC_GROUP, childId);
            if (w != null) { w.setHidden(true); }
        }

        List<DropItem> drops = dropData.getDropTableSections().stream()
                .filter(sec ->
                {
                    String h = sec.getHeader();
                    if (h == null) { return true; }
                    String lower = h.toLowerCase();
                    if (lower.contains("rare and gem drop table")) { return config.showRareDropTable() && config.showGemDropTable(); }
                    if (!config.showRareDropTable() && lower.contains("rare drop table")) { return false; }
                    if (!config.showGemDropTable() && lower.contains("gem drop table")) { return false; }
                    return true;
                })
                .flatMap(sec -> sec.getItems().stream())
                .collect(Collectors.toList());

        drops = WidgetUtils.dedupeAndSort(drops, config.sortDropsByRarity());

        final Set<Integer> obtainedIds = getObtainedIdsForCurrent();
        int totalDrops = drops.size();
        int obtainedCount = !isTracking()
                ? totalDrops
                : (int) drops.stream().filter(d -> obtainedIds.contains(d.getItemId())).count();

        Widget title = updateTitle(dropData);

        if (root != null)
        {
            root.setHidden(false);
            root.setType(WidgetType.LAYER);
            WidgetUtils.hideAllChildrenSafely(root);

            drawProgressBarAndToggle(root, title, dropData, obtainedCount, totalDrops);
        }

        drawDropIcons(scrollable, scrollbar, jukebox, drops, obtainedIds);

        if (root != null) { root.revalidate(); }
    }

    private void revertOverride()
    {
        if (!overrideActive) { return; }

        purgeCreatedWidgets();

        Widget root = client.getWidget(MUSIC_GROUP, 0);
        Widget scrollable = client.getWidget(MUSIC_GROUP, 4);
        Widget jukebox = client.getWidget(MUSIC_GROUP, 6);

        if (root != null)
        {
            if (originalRootType != null)
            {
                root.setType(originalRootType);
            }
            restoreChildren(root, backupRootStaticKids, backupRootDynamicKids);
        }

        restoreChildren(scrollable, backupScrollStaticKids, backupScrollDynamicKids);
        restoreChildren(jukebox, backupJukeboxStaticKids, backupJukeboxDynamicKids);

        Widget title = client.getWidget(MUSIC_GROUP, 8);
        Widget overlay = client.getWidget(MUSIC_GROUP, 5);
        Widget scrollbar = client.getWidget(MUSIC_GROUP, 7);
        Widget progress = client.getWidget(MUSIC_GROUP, 9);

        if (title != null && originalTitleText != null)
        {
            title.setText(originalTitleText);
            title.revalidate();
            for (int id = 9; id <= 19; id++)
            {
                Widget w = client.getWidget(MUSIC_GROUP, id);
                if (w != null)
                {
                    w.setHidden(false);
                    w.revalidate();
                }
            }
        }

        if (overlay != null) { overlay.setHidden(false); overlay.revalidate(); }
        if (scrollbar != null) { scrollbar.setHidden(false); scrollbar.revalidate(); }
        if (progress != null) { progress.setHidden(false); progress.revalidate(); }

        if (root != null && root.getOnLoadListener() != null)
        { client.createScriptEvent(root.getOnLoadListener()).setSource(root).run(); root.revalidate(); }
        if (overlay != null && overlay.getOnLoadListener() != null)
        { client.createScriptEvent(overlay.getOnLoadListener()).setSource(overlay).run(); overlay.revalidate(); }
        if (scrollbar != null && scrollbar.getOnLoadListener() != null)
        { client.createScriptEvent(scrollbar.getOnLoadListener()).setSource(scrollbar).run(); scrollbar.revalidate(); }
        if (jukebox != null && jukebox.getOnLoadListener() != null)
        { client.createScriptEvent(jukebox.getOnLoadListener()).setSource(jukebox).run(); jukebox.revalidate(); }

        originalTitleText = null;
        currentDrops = null;
        overrideActive = false;

        backupRootStaticKids = null;
        backupRootDynamicKids = null;
        backupJukeboxStaticKids = null;
        backupJukeboxDynamicKids = null;
        backupScrollStaticKids = null;
        backupScrollDynamicKids = null;

        originalRootType = null;

        iconItemMap.clear();
        overrideRootWidgets.clear();
        overrideScrollWidgets.clear();
    }

    private void resetWidgetCaches()
    {
        backupRootStaticKids = null;
        backupRootDynamicKids = null;
        backupJukeboxStaticKids = null;
        backupJukeboxDynamicKids = null;
        backupScrollStaticKids = null;
        backupScrollDynamicKids = null;

        originalRootType = null;
        originalTitleText = null;

        iconItemMap.clear();
        overrideRootWidgets.clear();
        overrideScrollWidgets.clear();
    }

    private void purgeCreatedWidgets()
    {
        for (Widget w : overrideRootWidgets)
        {
            safeNeutralizeWidget(w);
        }
        for (Widget w : overrideScrollWidgets)
        {
            safeNeutralizeWidget(w);
        }
        overrideRootWidgets.clear();
        overrideScrollWidgets.clear();
    }

    private static void safeNeutralizeWidget(Widget w)
    {
        if (w == null)
        {
            return;
        }

        try
        {
            w.setOnOpListener((JavaScriptCallback) null);
            w.setHasListener(false);
            w.setHidden(true);
            w.setType(0); // prevent resurrection by onLoad listeners
            w.revalidate();
        }
        catch (Exception ignored)
        {
            // Widget references can become invalid after reload; best-effort cleanup.
        }
    }

    private ObtainedItemsManager.Scope mapScope(LootLedgerConfig.Scope s)
    {
        return s == LootLedgerConfig.Scope.PER_NPC
                ? ObtainedItemsManager.Scope.PER_NPC
                : ObtainedItemsManager.Scope.PER_ACCOUNT;
    }
}
