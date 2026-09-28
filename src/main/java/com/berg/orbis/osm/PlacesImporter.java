package com.berg.orbis.osm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Turns an Overture Maps "place" GeoJSON file into the places store. Get the file with the Overture Python
 * client, for example:
 * <pre>
 *   pip install overturemaps
 *   python -m overturemaps download --bbox=5.05,60.20,5.65,60.55 -f geojson --type=place -o bergen-places.geojson
 * </pre>
 * then {@code /orbis import-places bergen-places.geojson} in game, or run this class from the command line:
 * {@code java -cp orbisterrarum-<version>.jar com.berg.orbis.osm.PlacesImporter bergen-places.geojson --out config/orbisterrarum/places}.
 *
 * Each place becomes an OSM-style tagged node (name + shop/amenity/tourism/office/leisure/healthcare) from
 * its Overture category; places without a name, with a category the furniture cannot use, or with a low
 * confidence are skipped. Overture Places are licensed CDLA-Permissive 2.0.
 */
public final class PlacesImporter {

    public record Summary(int read, int kept, int cells) {
        public String describe() {
            return String.format(Locale.ROOT, "%,d places read, %,d kept with a usable category and name, %d cells written", read, kept, cells);
        }
    }

    private static final double MIN_CONFIDENCE = 0.35;

    private PlacesImporter() {
    }

    public static Summary importFile(Path geojson, Path placesDir, Consumer<String> progress) throws IOException {
        Map<String, List<String>> cells = new HashMap<>();
        int read = 0, kept = 0;
        try (JsonReader jr = new JsonReader(open(geojson))) {
            jr.setLenient(true);
            // Walk to the "features" array without loading the whole file.
            jr.beginObject();
            while (jr.hasNext()) {
                String key = jr.nextName();
                if (!"features".equals(key)) {
                    jr.skipValue();
                    continue;
                }
                jr.beginArray();
                while (jr.hasNext()) {
                    JsonObject f = JsonParser.parseReader(jr).getAsJsonObject();
                    read++;
                    if (read % 20_000 == 0 && progress != null) progress.accept(String.format(Locale.ROOT, "Places: %,d read, %,d kept", read, kept));
                    String line = convert(f);
                    if (line == null) continue;
                    kept++;
                    double lat = lastLat, lon = lastLon;
                    String cell = PlacesStore.cellName((int) Math.floor(lat / PlacesStore.CELL_DEG), (int) Math.floor(lon / PlacesStore.CELL_DEG));
                    cells.computeIfAbsent(cell, k -> new ArrayList<>()).add(line);
                }
                jr.endArray();
            }
        }
        Files.createDirectories(placesDir);
        for (Map.Entry<String, List<String>> e : cells.entrySet()) {
            Path file = placesDir.resolve(e.getKey());
            Path tmp = file.resolveSibling(e.getKey() + ".tmp");
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8)) {
                for (String line : e.getValue()) {
                    w.write(line);
                    w.write('\n');
                }
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        PlacesStore.get(placesDir).rescan();
        return new Summary(read, kept, cells.size());
    }

    private static BufferedReader open(Path file) throws IOException {
        var in = Files.newInputStream(file);
        if (file.getFileName().toString().endsWith(".gz")) in = new GZIPInputStream(in);
        return new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    // The converter is single-threaded; the last converted position is handed back through these.
    private static double lastLat, lastLon;

    /** One GeoJSON feature to one JSON line {lat, lon, tags}, or null when unusable. */
    static String convert(JsonObject f) {
        JsonObject geom = f.has("geometry") && f.get("geometry").isJsonObject() ? f.getAsJsonObject("geometry") : null;
        if (geom == null || !"Point".equals(str(geom, "type"))) return null;
        JsonArray c = geom.getAsJsonArray("coordinates");
        if (c == null || c.size() < 2) return null;
        double lon = c.get(0).getAsDouble(), lat = c.get(1).getAsDouble();
        JsonObject props = f.has("properties") && f.get("properties").isJsonObject() ? f.getAsJsonObject("properties") : new JsonObject();
        String name = primaryName(props);
        if (name == null || name.isBlank()) return null;
        if (props.has("confidence") && props.get("confidence").isJsonPrimitive() && props.get("confidence").getAsJsonPrimitive().isNumber()
                && props.get("confidence").getAsDouble() < MIN_CONFIDENCE) return null;
        JsonObject cats = object(props.get("categories"));
        String primary = cats == null ? null : str(cats, "primary");
        List<String> alternates = new ArrayList<>();
        if (cats != null && cats.has("alternate") && cats.get("alternate").isJsonArray()) {
            for (JsonElement a : cats.getAsJsonArray("alternate")) if (a.isJsonPrimitive()) alternates.add(a.getAsString());
        }
        Map<String, String> tags = tagsFor(primary, alternates);
        if (tags == null) return null;
        tags.put("name", name.trim());
        tags.put("source", "overture");
        JsonObject out = new JsonObject();
        out.addProperty("lat", Math.round(lat * 1e7) / 1e7);
        out.addProperty("lon", Math.round(lon * 1e7) / 1e7);
        JsonObject t = new JsonObject();
        for (Map.Entry<String, String> e : tags.entrySet()) t.addProperty(e.getKey(), e.getValue());
        out.add("tags", t);
        lastLat = lat;
        lastLon = lon;
        return out.toString();
    }

    private static String primaryName(JsonObject props) {
        JsonObject names = object(props.get("names"));
        if (names != null) {
            String p = str(names, "primary");
            if (p != null) return p;
        }
        return str(props, "name");
    }

    /** Overture properties may arrive as objects or as JSON text inside a string; both are accepted. */
    private static JsonObject object(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) return el.getAsJsonObject();
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            try {
                JsonElement parsed = JsonParser.parseString(el.getAsString());
                return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** OSM-style tags for an Overture category, or null when the place is not something a building is used for. */
    static Map<String, String> tagsFor(String primary, List<String> alternates) {
        Map<String, String> t = map(primary);
        // "professional_services" with alternate "hotel" is a hotel: a specific alternate beats a generic primary.
        boolean generic = t == null || "yes".equals(t.get("office")) || "yes".equals(t.get("shop"));
        for (int i = 0; generic && i < alternates.size(); i++) {
            Map<String, String> a = map(alternates.get(i));
            if (a != null && !"yes".equals(a.get("office")) && !"yes".equals(a.get("shop"))) {
                t = a;
                generic = false;
            }
        }
        return t;
    }

    private static Map<String, String> tag(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static Map<String, String> map(String cat) {
        if (cat == null) return null;
        String c = cat.toLowerCase(Locale.ROOT);
        // eating and drinking
        if (c.contains("fast_food") || c.contains("burger") || c.contains("pizza") || c.contains("kebab") || c.contains("sandwich")) return tag("amenity", "fast_food");
        if (c.equals("bar") || c.endsWith("_bar") || c.contains("pub") || c.contains("beer_garden") || c.contains("brewery") || c.contains("lounge")) return tag("amenity", "bar");
        if (c.contains("night_club") || c.contains("nightclub") || c.contains("dance_club") || c.contains("casino")) return tag("amenity", "nightclub");
        if (c.contains("coffee") || c.equals("cafe") || c.contains("_cafe") || c.contains("tea_room") || c.contains("bubble_tea")) return tag("amenity", "cafe");
        if (c.contains("bakery") || c.contains("patisserie") || c.contains("pastry")) return tag("shop", "bakery");
        if (c.contains("ice_cream") || c.contains("dessert") || c.contains("chocolat")) return tag("amenity", "ice_cream");
        if (c.contains("restaurant") || c.contains("bistro") || c.contains("diner") || c.contains("steakhouse") || c.contains("sushi") || c.contains("food_court") || c.contains("cafeteria")) return tag("amenity", "restaurant");
        // sleeping
        if (c.contains("hostel")) return tag("tourism", "hostel");
        if (c.contains("hotel") || c.contains("motel") || c.contains("bed_and_breakfast") || c.contains("resort") || c.contains("guest_house") || c.contains("lodging")) return tag("tourism", "hotel");
        // culture
        if (c.contains("museum")) return tag("tourism", "museum");
        if (c.contains("art_gallery") || c.equals("gallery") || c.contains("art_museum")) return tag("tourism", "gallery");
        if (c.contains("aquarium")) return tag("tourism", "aquarium");
        if (c.contains("movie_theater") || c.contains("cinema")) return tag("amenity", "cinema");
        // "performing_arts" alone is a performer's page, not a venue
        if (c.contains("theater") || c.contains("theatre") || c.contains("performing_arts_venue") || c.contains("concert_hall") || c.contains("concert_venue")
                || c.contains("opera_house") || c.contains("music_venue") || c.contains("comedy_club")) return tag("amenity", "theatre");
        if (c.contains("library")) return tag("amenity", "library");
        if (c.contains("bookstore") || c.contains("book_store")) return tag("shop", "books");
        // health
        if (c.contains("pharmacy") || c.contains("drugstore") || c.contains("chemist")) return tag("amenity", "pharmacy");
        if (c.contains("hospital") || c.contains("emergency_room") || c.contains("medical_center") || c.contains("medical_centre")) return tag("amenity", "hospital");
        if (c.contains("dentist") || c.contains("dental")) return tag("amenity", "dentist");
        if (c.contains("doctor") || c.contains("physician") || c.contains("clinic") || c.contains("physiotherap") || c.contains("chiropract") || c.contains("psycholog") || c.contains("optometr")) return tag("amenity", "doctors");
        if (c.contains("veterinar") || c.contains("animal_hospital")) return tag("amenity", "veterinary");
        if (c.contains("optician") || c.contains("eyewear")) return tag("shop", "optician");
        // fitness
        if (c.contains("gym") || c.contains("fitness") || c.contains("yoga") || c.contains("pilates") || c.contains("crossfit") || c.contains("martial_arts") || c.contains("boxing")) return tag("leisure", "fitness_centre");
        if (c.contains("sports_club") || c.contains("sports_center") || c.contains("sports_centre") || c.contains("swimming_pool") || c.contains("climbing") || c.contains("bowling") || c.contains("ice_rink")) return tag("leisure", "sports_centre");
        // shopping
        if (c.contains("supermarket") || c.contains("grocery") || c.contains("hypermarket")) return tag("shop", "supermarket");
        if (c.contains("convenience")) return tag("shop", "convenience");
        if (c.contains("department_store")) return tag("shop", "department_store");
        if (c.contains("shopping_center") || c.contains("shopping_centre") || c.contains("shopping_mall") || c.equals("mall")) return tag("shop", "mall");
        if (c.contains("seafood") || c.contains("fish_market") || c.contains("fishmonger")) return tag("shop", "seafood");
        if (c.contains("butcher") || c.contains("meat_shop")) return tag("shop", "butcher");
        if (c.contains("liquor") || c.contains("wine_store") || c.contains("wine_shop") || c.contains("beverage_store")) return tag("shop", "alcohol");
        if (c.contains("florist") || c.contains("flower")) return tag("shop", "florist");
        if (c.contains("jewel") || c.contains("watch_store")) return tag("shop", "jewelry");
        if (c.contains("shoe")) return tag("shop", "shoes");
        if (c.contains("clothing") || c.contains("fashion") || c.contains("boutique") || c.contains("apparel") || c.contains("lingerie") || c.contains("menswear") || c.contains("womenswear")) return tag("shop", "clothes");
        if (c.contains("electronics") || c.contains("computer") || c.contains("mobile_phone") || c.contains("cell_phone") || c.contains("camera_store")) return tag("shop", "electronics");
        if (c.contains("hardware") || c.contains("home_improvement") || c.contains("paint_store") || c.contains("tool")) return tag("shop", "hardware");
        if (c.contains("furniture") || c.contains("home_decor") || c.contains("interior_design") || c.contains("kitchen") || c.contains("mattress")) return tag("shop", "furniture");
        if (c.contains("toy") || c.contains("game_store") || c.contains("hobby")) return tag("shop", "toys");
        if (c.contains("sporting_goods") || c.contains("sports_wear") || c.contains("outdoor_gear") || c.contains("bike_shop") || c.contains("bicycle")) return tag("shop", c.contains("bi") ? "bicycle" : "sports");
        if (c.contains("hair") || c.contains("barber") || c.contains("beauty") || c.contains("nail_salon") || c.contains("cosmetic") || c.contains("spa")) return tag("shop", "hairdresser");
        if (c.contains("tattoo") || c.contains("piercing")) return tag("shop", "tattoo");
        if (c.contains("pet_store") || c.contains("pet_shop")) return tag("shop", "pet");
        if (c.contains("gift") || c.contains("souvenir")) return tag("shop", "gift");
        if (c.contains("music_store") || c.contains("record_store") || c.contains("musical_instrument")) return tag("shop", "music");
        if (c.contains("art_supply") || c.contains("craft_store") || c.contains("stationery") || c.contains("office_supply")) return tag("shop", "stationery");
        if (c.contains("travel_agen")) return tag("shop", "travel_agency");
        if (c.contains("car_dealer") || c.contains("auto_dealer")) return tag("shop", "car");
        if (c.contains("tobacco") || c.contains("vape")) return tag("shop", "tobacco");
        if (c.contains("candy") || c.contains("confection")) return tag("shop", "confectionery");
        if (c.contains("thrift") || c.contains("second_hand") || c.contains("antique") || c.contains("vintage")) return tag("shop", "second_hand");
        if (c.endsWith("_store") || c.endsWith("_shop") || c.contains("retail")) return tag("shop", "yes");
        // money, learning, worship, public
        if (c.contains("bank") || c.contains("credit_union") || c.contains("currency_exchange")) return tag("amenity", "bank");
        if (c.contains("university") || c.contains("college")) return tag("amenity", "university");
        if (c.contains("kindergarten") || c.contains("preschool") || c.contains("day_care") || c.contains("child_care")) return tag("amenity", "kindergarten");
        if (c.contains("school") || c.contains("education") || c.contains("tutoring") || c.contains("driving_school")) return tag("amenity", "school");
        if (c.contains("church") || c.contains("cathedral") || c.contains("chapel") || c.contains("mosque") || c.contains("synagogue") || c.contains("temple") || c.contains("place_of_worship") || c.contains("religious")) return tag("amenity", "place_of_worship");
        if (c.contains("post_office")) return tag("amenity", "post_office");
        if (c.contains("police")) return tag("amenity", "police");
        if (c.contains("fire_station") || c.contains("fire_department")) return tag("amenity", "fire_station");
        if (c.contains("city_hall") || c.contains("town_hall") || c.contains("courthouse") || c.contains("embassy")) return tag("amenity", "townhall");
        if (c.contains("government") || c.contains("public_service") || c.contains("public_administration")) return tag("office", "government");
        if (c.contains("community_center") || c.contains("community_centre") || c.contains("cultural_center") || c.contains("cultural_centre") || c.contains("event_venue") || c.contains("conference")) return tag("amenity", "community_centre");
        if (c.contains("tourist_information") || c.contains("visitor_center") || c.contains("visitor_centre")) return tag("tourism", "information");
        // offices
        if (c.contains("lawyer") || c.contains("attorney") || c.contains("law_firm") || c.contains("accountant") || c.contains("real_estate") || c.contains("insurance")
                || c.contains("consult") || c.contains("advertising") || c.contains("marketing") || c.contains("architect") || c.contains("engineering") || c.contains("software")
                || c.contains("it_service") || c.contains("recruit") || c.contains("financial") || c.contains("office") || c.contains("professional_services") || c.contains("business_service")
                || c.contains("media") || c.contains("publisher") || c.contains("telecommunication") || c.contains("energy") || c.contains("shipping") || c.contains("logistics")) {
            return tag("office", "yes");
        }
        return null;
    }

    public static void main(String[] args) throws IOException {
        Path file = null, out = Path.of("config", "orbisterrarum", "places");
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) out = Path.of(args[++i]);
            else file = Path.of(args[i]);
        }
        if (file == null) {
            System.err.println("usage: java -cp orbisterrarum-<version>.jar com.berg.orbis.osm.PlacesImporter <places.geojson> [--out <places dir>]");
            System.exit(2);
        }
        Summary s = importFile(file, out, System.out::println);
        System.out.println("Done. " + s.describe() + " -> " + out.toAbsolutePath());
    }
}
