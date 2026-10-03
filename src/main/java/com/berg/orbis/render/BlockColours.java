package com.berg.orbis.render;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * The average colour of every block of the running Minecraft version (block name without namespace -> RGB), from the
 * table the build extracts from that version's textures ({@code assets/orbisterrarum/block_colours.json}); a server
 * has no textures to look at. Empty when the jar has no table (a build that did not make one).
 */
final class BlockColours {

    private static volatile Map<String, Integer> table;

    private BlockColours() {
    }

    static Map<String, Integer> table() {
        Map<String, Integer> map = table;
        if (map != null) return map;
        map = new HashMap<>();
        try (InputStream in = BlockColours.class.getResourceAsStream("/assets/orbisterrarum/block_colours.json")) {
            if (in != null) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (var e : json.entrySet()) map.put(e.getKey(), Integer.parseInt(e.getValue().getAsString(), 16));
            } else {
                System.out.println("[orbis] No block colour table in the mod jar: the hand-picked palette only");
            }
        } catch (Exception e) {
            System.err.println("[orbis] Block colour table unreadable (" + e + "): the hand-picked palette only");
        }
        table = Collections.unmodifiableMap(map);
        return table;
    }
}
