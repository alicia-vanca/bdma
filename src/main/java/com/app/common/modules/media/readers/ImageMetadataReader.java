package com.app.common.modules.media.readers;

import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.modules.media.dtos.GpsCoordinate;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.GpsDirectory;

@Component
public class ImageMetadataReader implements MediaMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(ImageMetadataReader.class);

    @Override
    public Optional<GpsCoordinate> readGps(Path filePath) {
        try {
            Metadata metadata = com.drew.imaging.ImageMetadataReader.readMetadata(filePath.toFile());
            GpsDirectory gpsDir = metadata.getFirstDirectoryOfType(GpsDirectory.class);

            if (gpsDir == null || !gpsDir.containsTag(GpsDirectory.TAG_LATITUDE)
                    || !gpsDir.containsTag(GpsDirectory.TAG_LONGITUDE)) {
                return Optional.empty();
            }

            com.drew.lang.GeoLocation location = gpsDir.getGeoLocation();
            if (location == null || location.isZero()) {
                return Optional.empty();
            }

            return Optional.of(new GpsCoordinate(location.getLatitude(), location.getLongitude()));

        } catch (Exception e) {
            log.warn("Cannot read GPS from image: {}", filePath, e);
            return Optional.empty();
        }
    }
}
