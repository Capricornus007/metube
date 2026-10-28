package com.newtube.mobile.ui.common;

import android.content.Context;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoDownloads;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.NavTabs;

import java.util.ArrayList;
import java.util.List;

/**
 * METUBE(shorts): the single definition of the bottom pill's tabs.
 *
 * <p>The Shorts tab is the vertical player, which is a different activity from the browse page.
 * Keeping the bar on screen there meant copying the tab list, and two copies of "which sections
 * are tabs, in what order, with which icon" drift the moment either side changes - so both pages
 * ask this class instead. The browse page owns the extras (badges, long-press menus, the
 * highlight re-assert); this is the shared model underneath them.</p>
 */
public final class MobileNavModel {
    /** Menu ids must not collide with resource ids; the offsets keep the two spaces apart. */
    public static final int ITEM_ID_OFFSET = 1_000_000;
    public static final int YOU_ITEM_ID = 2_000_000;
    public static final int MAX_NAV_ITEMS = 5;

    private MobileNavModel() {}

    public static int toMenuItemId(int sectionId) {
        return sectionId + ITEM_ID_OFFSET;
    }

    /**
     * The enabled sections that get a tab, in tab order, capped at {@link #MAX_NAV_ITEMS}.
     *
     * <p>METUBE(nav tabs, issue #7 / BarEditor): Home is fixed on the bar; the other two tabs are
     * whatever Settings &gt; Customization &gt; Tabs put in the two slots ({@link NavTabs}), and the
     * rest live in the You panel. The bar and this model are the same list, so a drag in BarEditor
     * moves the pill in the Shorts player too.</p>
     */
    public static List<BrowseSection> selectNavSections(
            Context context, List<BrowseSection> sections) {
        List<BrowseSection> chosen = new ArrayList<>();

        if (sections == null) {
            return chosen;
        }

        List<BrowseSection> enabledSections = new ArrayList<>();

        for (BrowseSection section : sections) {
            if (section.isEnabled() && section.getId() != MediaGroup.TYPE_SETTINGS) {
                enabledSections.add(section);
            }
        }

        int[] ids = {MediaGroup.TYPE_HOME, NavTabs.get(context, NavTabs.FIRST),
                NavTabs.get(context, NavTabs.SECOND)};
        for (int id : ids) {
            if (chosen.size() >= MAX_NAV_ITEMS) {
                break;
            }
            BrowseSection match = id == NavTabs.NONE ? null : findPreferredSection(
                    context, enabledSections, chosen, id, id == MediaGroup.TYPE_HOME ? R.string.header_home : 0);

            if (match != null) {
                chosen.add(match);
            }
        }

        return chosen;
    }

    /** Matches primarily by section id; falls back to a title-string match ({@code titleResId} 0: none). */
    private static BrowseSection findPreferredSection(
            Context context, List<BrowseSection> candidates, List<BrowseSection> alreadyChosen,
            int id, int titleResId) {
        String fallbackTitle = titleResId != 0 ? context.getString(titleResId) : null;

        for (BrowseSection section : candidates) {
            if (alreadyChosen.contains(section)) {
                continue;
            }

            if (section.getId() == id
                    || (fallbackTitle != null && Helpers.equals(section.getTitle(), fallbackTitle))) {
                return section;
            }
        }

        return null;
    }

    public static int navIconOrResFor(BrowseSection section) {
        int navIcon = navIconFor(section.getId());
        return navIcon != 0 ? navIcon : Math.max(section.getResId(), 0);
    }

    /**
     * State-list icons (outline when idle, filled when selected - the YouTube bar's active-tab
     * signal, since both states tint plain white) for the curated bottom-nav sections. Sections
     * without a pair (drawer-only ones) keep their stock {@link BrowseSection#getResId()} icon.
     */
    private static int navIconFor(int sectionId) {
        switch (sectionId) {
            case MediaGroup.TYPE_HOME:
                return R.drawable.ic_nav_home;
            case MediaGroup.TYPE_SUBSCRIPTIONS:
                return R.drawable.ic_nav_subscriptions;
            case MediaGroup.TYPE_HISTORY:
                return R.drawable.ic_nav_history;
            case MediaGroup.TYPE_SHORTS:
                return R.drawable.ic_nav_shorts;
            case VideoDownloads.SECTION_ID:
                return R.drawable.ic_nav_downloads;
            default:
                return 0;
        }
    }

    /**
     * Fill {@code nav} with the tab list plus the synthetic You row. Caller wires the listeners;
     * this only builds items, so both pages get identical tabs without sharing a view tree.
     */
    public static void build(Context context, BottomNavigationView nav, List<BrowseSection> sections) {
        List<BrowseSection> navSections = selectNavSections(context, sections);
        android.view.Menu menu = nav.getMenu();

        menu.clear();

        for (int i = 0; i < navSections.size(); i++) {
            BrowseSection section = navSections.get(i);
            // METUBE(issue #81): "Subscriptions" wraps to two lines in a 5-tab bar; the bar says "Subs".
            CharSequence title = section.getId() == MediaGroup.TYPE_SUBSCRIPTIONS
                    ? context.getString(R.string.mobile_nav_subs) : section.getTitle();
            android.view.MenuItem item =
                    menu.add(android.view.Menu.NONE, toMenuItemId(section.getId()), i, title);
            int icon = navIconOrResFor(section);
            if (icon > 0) {
                item.setIcon(icon);
            }
        }

        menu.add(android.view.Menu.NONE, YOU_ITEM_ID, navSections.size(), R.string.mobile_nav_you)
                .setIcon(R.drawable.ic_nav_you);
    }
}
