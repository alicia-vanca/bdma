package com.app.common.modules.media.dtos;

public record GpsPoint(double timeSeconds, double latitude, double longitude) {

    public GpsCoordinate toCoordinate() {
        return new GpsCoordinate(latitude, longitude);
    }
}
