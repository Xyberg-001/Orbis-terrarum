package com.berg.orbis.client.map;

import com.berg.orbis.net.MapAnswerPayload;

/** A screen that takes the server's answers to the world map's questions (the map itself, its World window). */
public interface MapAnswerSink {
    void answer(MapAnswerPayload p);
}
