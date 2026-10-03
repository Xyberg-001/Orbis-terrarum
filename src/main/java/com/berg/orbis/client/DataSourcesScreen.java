package com.berg.orbis.client;

import com.berg.orbis.OrbisMod;
import com.berg.orbis.config.DataSources;
import com.berg.orbis.config.DataSources.Kind;
import com.berg.orbis.config.DataSources.Source;
import com.berg.orbis.config.DataSources.Usage;
import com.berg.orbis.config.OrbisConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What every data source keeps on disk, by country (the countries around the world's location first): Re-import
 * data deletes a downloaded source's copies so they are fetched again, fresh, when new chunks need them; Delete
 * frees the space (imported data only comes back by importing it again). Which sources are used is automatic.
 */
public final class DataSourcesScreen extends Screen {

    private static final int ROW = 22, TOP = 56, BG = 0xF0101418, WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, GOLD = 0xFFFFC040;
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Orbis-data-sources");
        t.setDaemon(true);
        return t;
    });
    /** What each source keeps, from the last count (kept between visits: counting a big data folder takes a minute). */
    private static final Map<String, Usage> usage = new ConcurrentHashMap<>();
    private static final Set<String> busy = ConcurrentHashMap.newKeySet();
    private static volatile boolean everCounted;

    private final Screen parent;
    private final List<Source> sources;
    private final List<double[]> worldBoxes;
    private final Path root = OrbisMod.dataDir();
    private final List<Button> rowButtons = new ArrayList<>();
    /** What the list shows: a region heading (source null) or a source. */
    private final List<Object[]> lines = new ArrayList<>();
    private volatile boolean scanned;
    private boolean scanStarted;
    private boolean onlyThisWorld = true;
    /** Largest first, in one list, instead of grouped by country. */
    private static boolean bySize;
    /** The sizes the size order was worked out from (it is redone as the count fills them in). */
    private long sizesSeen = -1;
    private EditBox search;
    private int scroll;

    private DataSourcesScreen(Screen parent, List<Source> sources, OrbisConfig area) {
        super(Component.translatable("orbisterrarum.sources.title"));
        this.parent = parent;
        this.sources = sources;
        this.worldBoxes = DataSources.worldBoxes(area);
    }

    /** The data folder's size from the last full count this session ("1.3 GB"), or null before one. */
    public static String lastTotal() {
        if (!everCounted) return null;
        long total = 0;
        for (Usage u : usage.values()) total += u.bytes();
        return DataSources.bytes(total);
    }

    /** Every source of these settings; "this world" is around the given location (chosen on the World tab, saved or not). */
    public static DataSourcesScreen manage(Screen parent, OrbisConfig settings, double lat, double lon) {
        OrbisConfig area = settings.copy();
        area.originLat = lat;
        area.originLon = lon;
        List<Source> all = new ArrayList<>(DataSources.list(settings));
        // The worldwide sources, then the countries in alphabetical order, then what was imported.
        all.sort(java.util.Comparator.comparingInt((Source s) -> s.kind() == Kind.BUILT_IN ? 0 : s.kind() == Kind.NATIONAL ? 1 : 2)
                .thenComparing(s -> s.kind() == Kind.NATIONAL ? s.country() : ""));
        return new DataSourcesScreen(parent, all, area);
    }

    private int listWidth() {
        return Math.min(480, width - 20);
    }

    private int visibleRows() {
        return Math.max(1, (height - TOP - 34) / ROW);
    }

    @Override
    protected void init() {
        int w = listWidth(), x = (width - w) / 2;
        String query = search != null ? search.getValue() : "";
        search = new EditBox(font, x, 24, w - 248, 18, Component.translatable("orbisterrarum.pick.search"));
        search.setValue(query);
        search.setHint(Component.translatable("orbisterrarum.pick.search"));
        search.setResponder(s -> {
            scroll = 0;
            layout();
        });
        addRenderableWidget(search);
        addRenderableWidget(Button.builder(sortLabel(), b -> {
            bySize = !bySize;
            b.setMessage(sortLabel());
            scroll = 0;
            layout();
        }).bounds(x + w - 244, 23, 120, 20).build());
        addRenderableWidget(Button.builder(filterLabel(), b -> {
            onlyThisWorld = !onlyThisWorld;
            b.setMessage(filterLabel());
            scroll = 0;
            layout();
        }).bounds(x + w - 120, 23, 120, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(x, height - 26, w, 20).build());
        if (!scanStarted) {
            scanStarted = true;
            DISK.execute(() -> {
                // Folder by folder, so most sizes show at once; the photo services share one big folder and are
                // counted together, last.
                for (Source s : sources) if (s.prefix() == null) usage.putAll(DataSources.scan(root, List.of(s)));
                usage.putAll(DataSources.scan(root, sources.stream().filter(s -> s.prefix() != null).toList()));
                scanned = true;
                everCounted = true;
            });
        }
        layout();
    }

    private Component filterLabel() {
        return Component.translatable(onlyThisWorld ? "orbisterrarum.sources.filter.world" : "orbisterrarum.sources.filter.all");
    }

    private Component sortLabel() {
        return Component.translatable(bySize ? "orbisterrarum.sources.sort.size" : "orbisterrarum.sources.sort.country");
    }

    /** Bytes a source keeps, -1 while not counted yet. */
    private static long size(Source s) {
        Usage u = usage.get(s.id());
        return u == null ? -1 : u.bytes();
    }

    /** Sizes keep arriving while the folder is counted: the size order follows them. */
    @Override
    public void tick() {
        super.tick();
        if (!bySize) return;
        long seen = 0;
        for (Usage u : usage.values()) seen = seen * 31 + u.bytes();
        if (seen != sizesSeen) layout();
    }

    private boolean coversWorld(Source s) {
        if (s.kind() != Kind.NATIONAL) return true;
        for (double[] b : worldBoxes) if (s.overlaps(b[0], b[1], b[2], b[3])) return true;
        return false;
    }

    /** Downloaded sources come back by themselves (Re-import data); imported ones and old data can only be deleted. */
    private static boolean downloaded(Source s) {
        return s.kind() != Kind.IMPORTED && !s.id().startsWith("legacy-");
    }

    /** The lines matching the filter and search, then the buttons for the rows in view. */
    private void layout() {
        lines.clear();
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Source> shown = new ArrayList<>();
        for (Source s : sources) {
            if (onlyThisWorld && q.isEmpty() && !coversWorld(s)) continue;
            if (!q.isEmpty() && !(s.label() + " " + s.region()).toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(s);
        }
        if (bySize) {
            // One list, largest first (not counted yet last); each row says where the source is from.
            long seen = 0;
            for (Usage u : usage.values()) seen = seen * 31 + u.bytes();
            sizesSeen = seen;
            shown.sort(java.util.Comparator.comparingLong((Source s) -> size(s)).reversed());
            for (Source s : shown) lines.add(new Object[]{null, s});
        } else {
            String region = null;
            for (Source s : shown) {
                String heading = s.kind() == Kind.NATIONAL ? s.country() : s.region();
                if (!heading.equals(region)) {
                    lines.add(new Object[]{heading, null});
                    region = heading;
                }
                lines.add(new Object[]{null, s});
            }
        }
        scroll = Math.max(0, Math.min(scroll, lines.size() - visibleRows()));
        for (Button b : rowButtons) removeWidget(b);
        rowButtons.clear();
        int w = listWidth(), x = (width - w) / 2;
        for (int i = 0; i < visibleRows() && scroll + i < lines.size(); i++) {
            Source s = (Source) lines.get(scroll + i)[1];
            if (s == null) continue;
            int y = TOP + i * ROW, right = x + w;
            rowButtons.add(addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.sources.delete"), b -> ask(s, false))
                    .bounds(right - 52, y, 52, 20).build()));
            if (downloaded(s)) {
                rowButtons.add(addRenderableWidget(Button.builder(Component.translatable("orbisterrarum.sources.reimport"), b -> ask(s, true))
                        .bounds(right - 152, y, 98, 20).build()));
            }
        }
    }

    /** Asks before deleting a source's copies (re-import: they are fetched again when needed; delete: to free the space). */
    private void ask(Source s, boolean reimport) {
        Usage u = usage.get(s.id());
        String size = u == null ? "?" : DataSources.bytes(u.bytes());
        String key = reimport ? "orbisterrarum.sources.ask.reimport" : downloaded(s) ? "orbisterrarum.sources.ask.free" : "orbisterrarum.sources.ask.delete";
        minecraft.setScreenAndShow(new ConfirmScreen(yes -> {
            if (yes) {
                busy.add(s.id());
                DISK.execute(() -> {
                    DataSources.clear(root, s);
                    usage.putAll(DataSources.scan(root, List.of(s)));
                    busy.remove(s.id());
                });
            }
            minecraft.setScreenAndShow(this);
        }, Component.literal(s.label() + " · " + s.region()), Component.translatable(key, size)));
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        if (sy != 0 && lines.size() > visibleRows()) {
            scroll -= (int) Math.signum(sy);
            layout();
            return true;
        }
        return super.mouseScrolled(x, y, sx, sy);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BG);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int w = listWidth(), x = (width - w) / 2;
        g.centeredText(font, title, width / 2, 8, WHITE);
        long total = 0;
        for (Usage u : usage.values()) total += u.bytes();
        String sum = Component.translatable(scanned ? "orbisterrarum.sources.total" : "orbisterrarum.sources.counting", DataSources.bytes(total)).getString();
        g.text(font, sum, x, 45, GREY);
        String note = Component.translatable("orbisterrarum.sources.note").getString();
        int noteRoom = w - font.width(sum) - 12;
        g.text(font, font.plainSubstrByWidth(note, noteRoom), x + w - Math.min(font.width(note), noteRoom), 45, GREY);
        for (int i = 0; i < visibleRows() && scroll + i < lines.size(); i++) {
            Object[] line = lines.get(scroll + i);
            int y = TOP + i * ROW;
            if (line[1] == null) {
                g.text(font, (String) line[0], x, y + 8, GOLD);
                continue;
            }
            Source s = (Source) line[1];
            int textWidth = w - (downloaded(s) ? 158 : 58);
            String label = s.label();
            if (bySize) {
                label += " · " + s.region(); // no country headings in the size order
            } else if (s.kind() == Kind.NATIONAL && !s.region().equals(s.country())) {
                label += " · " + s.region().substring(s.country().length() + 2, s.region().length() - 1);
            }
            Usage u = usage.get(s.id());
            String size = busy.contains(s.id()) || u == null ? "…" : DataSources.bytes(u.bytes());
            g.text(font, font.plainSubstrByWidth(label, textWidth - 62), x + 8, y + 6, WHITE);
            g.text(font, size, x + textWidth - font.width(size), y + 6, GREY);
        }
        if (scroll > 0) g.centeredText(font, "▲", width / 2, TOP - 10, GREY);
        if (scroll + visibleRows() < lines.size()) g.centeredText(font, "▼", width / 2, TOP + visibleRows() * ROW, GREY);
        if (lines.isEmpty()) g.centeredText(font, Component.translatable("orbisterrarum.pick.none"), width / 2, TOP + 6, GREY);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }
}
