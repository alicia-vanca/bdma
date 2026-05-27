package com.app.common.modules.media.dtos;

import org.jetbrains.annotations.NotNull;

public record GpsCoordinate(double latitude, double longitude) {

    @NotNull
    @Override
    public String toString() {
        String latDir = latitude >= 0 ? "N" : "S";
        String lonDir = longitude >= 0 ? "E" : "W";
        return String.format("%.6f° %s, %.6f° %s",
                Math.abs(latitude), latDir,
                Math.abs(longitude), lonDir);
    }
}
