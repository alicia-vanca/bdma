package com.app.common.modules.media.readers;

import java.nio.file.Path;
import java.util.Optional;

import com.app.common.modules.media.dtos.GpsCoordinate;

public interface MediaMetadataReader {
    Optional<GpsCoordinate> readGps(Path filePath);
}
