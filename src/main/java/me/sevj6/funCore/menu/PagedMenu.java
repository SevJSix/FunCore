package me.sevj6.funCore.menu;

import me.sevj6.funCore.util.ItemBuilder;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu for lists longer than one screen (shop stock, crates, games). Every row but the last holds items; the last
 * row has previous/next arrows and a page counter.
 *
 * <pre>
 * PagedMenu menu = new PagedMenu(Utils.mm("&lt;gold&gt;All games"), 6);
 * menu.setItems(buttons);   // any number; split into pages automatically
 * menu.open(player);
 * </pre>
 *
 * The bottom row's other slots (columns 0-2 and 6-8) are yours: put buttons there (a close or back button, a sort
 * toggle) and paging leaves them alone. Empty ones are filled with a gray pane.
 */
public class PagedMenu extends Menu {

    private static final int PREVIOUS_COLUMN = 3, INFO_COLUMN = 4, NEXT_COLUMN = 5;

    private final int navigationRow;
    private final List<Integer> contentSlots = new ArrayList<>();
    private List<MenuButton> items = List.of();
    private int page;

    /**
     * @param rows 2 to 6 (one row for items at minimum, plus the navigation row)
     */
    public PagedMenu(Component title, int rows) {
        super(title, rows);
        if (rows < 2) throw new IllegalArgumentException("A paged menu needs at least 2 rows");
        this.navigationRow = rows - 1;
        for (int slot = 0; slot < navigationRow * 9; slot++) contentSlots.add(slot);
        render();
    }

    /** Replaces the items and redraws, staying on the current page if it still exists. */
    public PagedMenu setItems(List<MenuButton> items) {
        this.items = List.copyOf(items);
        page = Math.min(page, getPageCount() - 1);
        render();
        return this;
    }

    public List<MenuButton> getItems() {
        return items;
    }

    /** The current page, counted from 0. */
    public int getPage() {
        return page;
    }

    public int getPageCount() {
        return Math.max(1, (items.size() + contentSlots.size() - 1) / contentSlots.size());
    }

    public void setPage(int page) {
        this.page = Math.clamp(page, 0, getPageCount() - 1);
        render();
    }

    /** Items per page. */
    public int getPageSize() {
        return contentSlots.size();
    }

    protected void render() {
        int start = page * contentSlots.size();
        for (int i = 0; i < contentSlots.size(); i++) {
            int index = start + i;
            set(contentSlots.get(i), index < items.size() ? items.get(index) : null);
        }

        set(navigationRow, PREVIOUS_COLUMN, page > 0
                ? MenuButton.of(ItemBuilder.of(Material.ARROW).name("<yellow>Previous page").build(), click -> setPage(page - 1))
                : MenuButton.filler());
        set(navigationRow, NEXT_COLUMN, page < getPageCount() - 1
                ? MenuButton.of(ItemBuilder.of(Material.ARROW).name("<yellow>Next page").build(), click -> setPage(page + 1))
                : MenuButton.filler());
        set(navigationRow, INFO_COLUMN, MenuButton.display(ItemBuilder.of(Material.PAPER)
                .name(Utils.mm("<gold>Page <page> of <pages>",
                        Placeholder.unparsed("page", String.valueOf(page + 1)),
                        Placeholder.unparsed("pages", String.valueOf(getPageCount()))))
                .build()));
        for (int column = 0; column < 9; column++) {
            if (isEmpty(Menu.slot(navigationRow, column))) set(navigationRow, column, MenuButton.filler());
        }
    }
}
