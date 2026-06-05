package com.app.common.modules.media.readers;


import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.app.common.modules.media.dtos.GpsCoordinate;
import com.app.common.modules.media.dtos.GpsPoint;
import com.googlecode.mp4parser.authoring.Movie;
import com.googlecode.mp4parser.authoring.Sample;
import com.googlecode.mp4parser.authoring.Track;
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator;

@Component
public class VideoMetadataReader implements MediaMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(VideoMetadataReader.class);
    private static final Pattern MSG_PATTERN = Pattern.compile("<msg>(.*?)</msg>", Pattern.DOTALL);
    private static final Pattern LAT_PATTERN = Pattern.compile("<Latitude.*?>(.*?)</Latitude>");
    private static final Pattern LON_PATTERN = Pattern.compile("<Longitude.*?>(.*?)</Longitude>");

    // ── MediaMetadataReader ───────────────────────────────────────────────────
    @Override
    public Optional<GpsCoordinate> readGps(Path filePath) {
        List<GpsPoint> timeline = readGpsTimeline(filePath);
        if (timeline.isEmpty()) return Optional.empty();
        return Optional.of(timeline.getFirst().toCoordinate());
    }

    // ── GPS Timeline ──────────────────────────────────────────────────────────
    public List<GpsPoint> readGpsTimeline(Path filePath) {
        List<GpsPoint> result = new ArrayList<>();
        try {
            Movie movie = MovieCreator.build(filePath.toString());
            for (Track track : movie.getTracks()) {
                if ("meta".equals(track.getHandler())) {
                    processMetaTrack(track, result);
                }
            }
        } catch (Exception e) {
            log.warn("Cannot read GPS timeline: {}", filePath, e);
        }
        return result;
    }

    private void processMetaTrack(Track track, List<GpsPoint> result) {
        long[] durations = track.getSampleDurations();
        long timescale = track.getTrackMetaData().getTimescale();
        double currentTime = 0;

        List<Sample> samples = track.getSamples();
        for (int i = 0; i < samples.size(); i++) {
            currentTime += processSample(samples.get(i), currentTime, result);
            if (i < durations.length) {
                currentTime += (double) durations[i] / timescale;
            }
        }
    }

    private double processSample(Sample sample, double currentTime, List<GpsPoint> result) {
        ByteBuffer buffer = sample.asByteBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);

        String content = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher msgMatcher = MSG_PATTERN.matcher(content);

        while (msgMatcher.find()) {
            parseGpsPoint(msgMatcher.group(1), currentTime, result);
        }
        return 0;
    }

    private void parseGpsPoint(String msg, double currentTime, List<GpsPoint> result) {
        String lat = extract(msg, LAT_PATTERN);
        String lon = extract(msg, LON_PATTERN);

        if (lat == null || lon == null) return;

        try {
            double latitude = Double.parseDouble(lat.trim());
            double longitude = Double.parseDouble(lon.trim());
            result.add(new GpsPoint(currentTime, latitude, longitude));
        } catch (NumberFormatException e) {
            log.warn("Invalid GPS: lat={} lon={}", lat, lon);
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private String extract(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
