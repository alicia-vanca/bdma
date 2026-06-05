package com.app.common.modules.media.services;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.app.common.modules.media.dtos.GpsCoordinate;
import com.app.common.modules.media.dtos.GpsPoint;
import com.app.common.modules.media.readers.ImageMetadataReader;
import com.app.common.modules.media.readers.VideoMetadataReader;

@Service
public class MediaMetadataService {

    private final ImageMetadataReader imageReader;
    private final VideoMetadataReader videoReader;

    public MediaMetadataService(ImageMetadataReader imageReader,
            VideoMetadataReader videoReader) {
        this.imageReader = imageReader;
        this.videoReader = videoReader;
    }

    public Optional<GpsCoordinate> readGps(Path filePath, String type) {
        return switch (type) {
            case "image" -> imageReader.readGps(filePath);
            case "video", "IMP" -> videoReader.readGps(filePath);
            default -> Optional.empty();
        };
    }

    public List<GpsPoint> readGpsTimeline(Path filePath, String type) {
        if (isVideo(type)) {
            return videoReader.readGpsTimeline(filePath);
        }
        return List.of();
    }

    private boolean isVideo(String type) {
        if (type == null) return false;
        return switch (type) {
            case "video", "IMP" -> true;
            default -> false;
        };
    }
}
