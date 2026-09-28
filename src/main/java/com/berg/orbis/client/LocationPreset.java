package com.berg.orbis.client;

import dev.isxander.yacl3.api.NameableEnum;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Well-known places offered in the location picker; CUSTOM leaves the coordinates as typed. */
public enum LocationPreset implements NameableEnum {
    CUSTOM(Double.NaN, Double.NaN),
    BERGEN(60.39299, 5.32415),
    OSLO(59.91390, 10.75220),
    STOCKHOLM(59.32930, 18.06860),
    COPENHAGEN(55.67610, 12.56830),
    LONDON(51.50740, -0.12780),
    PARIS(48.85660, 2.35220),
    AMSTERDAM(52.37020, 4.89520),
    BERLIN(52.52000, 13.40500),
    ROME(41.90280, 12.49640),
    BARCELONA(41.38510, 2.17340),
    ISTANBUL(41.00820, 28.97840),
    DUBAI(25.20480, 55.27080),
    MUMBAI(19.07600, 72.87770),
    SINGAPORE(1.35210, 103.81980),
    HONG_KONG(22.31930, 114.16940),
    TOKYO(35.67620, 139.65030),
    SYDNEY(-33.86880, 151.20930),
    NEW_YORK(40.71280, -74.00600),
    SAN_FRANCISCO(37.77490, -122.41940),
    CHICAGO(41.87810, -87.62980),
    TORONTO(43.65320, -79.38320),
    MEXICO_CITY(19.43260, -99.13320),
    RIO_DE_JANEIRO(-22.90680, -43.17290),
    CAPE_TOWN(-33.92490, 18.42410),
    CAIRO(30.04440, 31.23570),
    MOUNT_EVEREST(27.98810, 86.92500),
    MATTERHORN(45.97630, 7.65860),
    GRAND_CANYON(36.10690, -112.11290),
    NIAGARA_FALLS(43.07960, -79.07470);

    public final double lat, lon;

    LocationPreset(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("orbisterrarum.preset." + name().toLowerCase(Locale.ROOT));
    }
}
