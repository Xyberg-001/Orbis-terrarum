package com.berg.orbis.geology;

import com.berg.orbis.config.DataSources;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The rock under each place, from the geological maps that cover it: the first source with an answer for a place
 * wins (a national survey before the world map). Also the table that turns a map's rock names, in English and the
 * main European languages, into the rocks a world can show.
 */
public final class Rocks {

    /** The rocks a world can show, each with the block that stands for it. Code 0 is "unknown" (plain stone). */
    public enum Rock {
        UNKNOWN(Blocks.STONE.defaultBlockState()),
        GNEISS(Blocks.STONE.defaultBlockState()),
        GRANITE(Blocks.GRANITE.defaultBlockState()),
        DIORITE(Blocks.DIORITE.defaultBlockState()),
        VOLCANIC(Blocks.ANDESITE.defaultBlockState()),
        MAFIC(Blocks.DEEPSLATE.defaultBlockState()),
        SCHIST(Blocks.TUFF.defaultBlockState()),
        MARBLE(Blocks.CALCITE.defaultBlockState()),
        SANDSTONE(Blocks.SANDSTONE.defaultBlockState()),
        // (new rocks go last: the codes are kept in region rasters)
        BASALT(Blocks.SMOOTH_BASALT.defaultBlockState());

        public final BlockState block;
        private static final Rock[] ALL = values();

        Rock(BlockState block) {
            this.block = block;
        }

        public static Rock of(int code) {
            return code > 0 && code < ALL.length ? ALL[code] : UNKNOWN;
        }
    }

    /** One geological map. */
    public interface Source {
        /** Its id in {@link DataSources} (the name its answer and its copies are kept under). */
        String name();

        /** Where its downloads are kept (one folder per source, so each can be cleared on its own). */
        Path cacheDir();

        /** Whether it may have anything in this box (a cheap test on its known coverage). */
        boolean overlaps(double south, double west, double north, double east);

        /** Loads what the box needs, in parallel, before it is sampled column by column. */
        void prefetch(double south, double west, double north, double east);

        /** The rock at a place, or null where this map has nothing (outside it, at sea, offline). */
        Rock rockAt(double lat, double lon);
    }

    private final List<Source> sources;

    public Rocks(List<Source> sources) {
        this.sources = List.copyOf(sources);
    }

    public List<Source> sources() {
        return sources;
    }

    public boolean overlaps(double south, double west, double north, double east) {
        for (Source s : sources) if (s.overlaps(south, west, north, east) && DataSources.covers(s.name(), south, west, north, east)) return true;
        return false;
    }

    public void prefetch(double south, double west, double north, double east) {
        for (Source s : sources) if (s.overlaps(south, west, north, east) && DataSources.covers(s.name(), south, west, north, east)) s.prefetch(south, west, north, east);
    }

    public Rock rockAt(double lat, double lon) {
        for (Source s : sources) {
            Rock r = s.rockAt(lat, lon);
            if (r != null) return r;
        }
        return Rock.UNKNOWN;
    }

    // ------------------------------------------------------------------ rock names

    /**
     * Word stems per rock, in order: within one word the first rock whose stem it contains wins ("granodiorite" is a
     * granite, "Kalkschiefer" a limestone). Accents are dropped before matching. SEDIMENT (loose ground: alluvium,
     * till, sand) is plain stone underneath. A stem starting with ^ only matches at the start of a word.
     */
    private static final Object[][] STEMS = {
            {Rock.GNEISS, "gneis", "gnais", "migmatit", "granulit", "metamorf", "metamorph"},
            {Rock.MARBLE, "marbl", "marmor", "marmo", "limestone", "calcair", "caliz", "calcar", "kalkstein", "^kalk", "dolomi",
                    "dolostone", "carbonat", "chalk", "craie", "travertin", "^lime", "gyps", "^yeso", "gesso", "anhydrit", "evaporit"},
            {Rock.GRANITE, "granodiorit", "granit", "monzonit", "syenit", "sienit", "charnockit", "pegmatit", "aplit", "larvikit",
                    "plutoni", "batholit", "felsic", "intrusi"},
            {Rock.DIORITE, "quartzit", "quarzit", "cuarcit", "anorthosit", "anortosit", "diorit", "tonalit", "trondhjemit", "chert",
                    "^silex", "^flint", "radiolarit"},
            {Rock.BASALT, "basalt"},
            {Rock.MAFIC, "gabbro", "gabro", "norit", "amphibolit", "anfibolit", "greenstone", "diabas", "dolerit", "eclogit", "peridotit",
                    "serpentin", "pyroxenit", "piroxenit", "ultramafi", "ultrabasi", "komatiit", "dunit", "ophiolit", "ofiolit",
                    "hornblendit", "mafi"},
            {Rock.SCHIST, "schist", "schiefer", "esquist", "scist", "phyllit", "filit", "fillad", "slate", "ardois", "pizarr", "ardesi",
                    "shale", "argilit", "argillit", "mudstone", "siltstone", "claystone", "pelit", "lutit", "limolit", "lodolit",
                    "wacke", "grauvac", "grauwac", "turbidit", "flysch", "^marl", "^marn", "^marg", "mergel", "tuff", "^tuf", "toba"},
            {Rock.SANDSTONE, "sandstone", "sandstein", "^gres", "arenisc", "arenit", "arenari", "arkos", "arcos", "conglomer",
                    "onglomerat", "siliciclast", "^clastic"},
            {Rock.VOLCANIC, "rhyolit", "riolit", "dacit", "andesit", "trachyt", "traquit", "porphyr", "porfir", "porfid", "volcan",
                    "vulcan", "vulkan", "ignimbrit", "pyroclast", "piroclast", "brecci", "^brech", "brekzi", "latit", "phonolit",
                    "fonolit", "obsidian", "^lava"},
            {null, "alluvi", "aluvi", "^sand", "gravel", "gravi", "^grava", "^kies", "^clay", "argil", "arcill", "^silt", "^mud",
                    "^loess", "^loss", "^till", "morain", "morren", "^peat", "^tourb", "colluvi", "unconsolidat", "sediment", "deposit",
                    "^dune", "^beach", "^water", "^ice", "glacia", "fluvi", "lacustr", "eolian", "aeolian", "^soil"},
    };

    /** Adjective endings: "granitic gneiss" is a gneiss, "basaltic andesite" an andesite. */
    private static final Pattern ADJECTIVE = Pattern.compile(".*(ic|ics|ique|iques|ico|ica|icos|icas|isch|ische|ischer|ed|ive|ivo|iva|oid|ous|ose|oso|osa|ary|aire|ario|aria)$");
    private static final Pattern WORDS = Pattern.compile("[^\\p{L}]+");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    /** Stands for "loose ground" in the result of {@link #classify}: plain stone, but a known answer. */
    static final Rock SEDIMENT = Rock.UNKNOWN;

    /**
     * The rock a map's description names, or {@code fallback} when it names none it knows. The first noun that names
     * a rock wins (descriptions list the main rock first); without one, the last adjective, the one next to the noun
     * ("mafic-intermediate volcanic rocks" are volcanic, and mafic volcanic rocks basalt); loose ground only when no
     * rock is named at all ("pyroclastic flow deposits" are volcanic rock).
     */
    public static Rock classify(String text, Rock fallback) {
        if (text == null || text.isBlank()) return fallback;
        String plain = ACCENTS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
        Rock adjective = null;
        boolean loose = false, mafic = false;
        for (String word : WORDS.split(plain)) {
            if (word.length() < 3) continue;
            Object hit = match(word);
            if (hit == null) continue;
            if (hit == Boolean.FALSE) {
                loose = true;
            } else if (!ADJECTIVE.matcher(word).matches()) {
                return (Rock) hit;
            } else {
                mafic |= hit == Rock.MAFIC;
                adjective = (Rock) hit;
            }
        }
        if (adjective == Rock.VOLCANIC && mafic) return Rock.BASALT;
        if (adjective != null) return adjective;
        return loose ? SEDIMENT : fallback;
    }

    /** The rock (or FALSE for loose ground) a word names, or null. */
    private static Object match(String word) {
        for (Object[] row : STEMS) {
            for (int i = 1; i < row.length; i++) {
                String stem = (String) row[i];
                boolean hit = stem.charAt(0) == '^' ? word.startsWith(stem.substring(1)) : word.contains(stem);
                if (hit) return row[0] == null ? Boolean.FALSE : row[0];
            }
        }
        return null;
    }
}
