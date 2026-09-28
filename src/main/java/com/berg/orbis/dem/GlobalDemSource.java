package com.berg.orbis.dem;

/** Global coverage source (Terrarium tiles, ~30 m native resolution, with bathymetry). */
public class GlobalDemSource implements DemSource {

    private final BicubicElevationSampler sampler;

    public GlobalDemSource(BicubicElevationSampler sampler) {
        this.sampler = sampler;
    }

    @Override
    public double sampleMeters(double lat, double lon) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return sampler.sampleMeters(lat, lon);
            } catch (RuntimeException e) {
                last = e;
                try {
                    Thread.sleep(1500L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new RuntimeException("interrupted") : last;
    }

    @Override
    public double nativeResolutionMeters() {
        return 30.0;
    }

    @Override
    public boolean hasCoverage(double lat, double lon) {
        return true; // global tile set, no gaps by design
    }
}
