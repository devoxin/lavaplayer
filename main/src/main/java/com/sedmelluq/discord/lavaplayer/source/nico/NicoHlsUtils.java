package com.sedmelluq.discord.lavaplayer.source.nico;

import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.util.EntityUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.fetchResponseLines;
import static com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.isSuccessWithContent;

public class NicoHlsUtils {
    public static List<HlsStreamSegment> parseFromUrl(HttpInterface httpInterface, String url) throws IOException {
        return parseFromLines(fetchResponseLines(httpInterface, new HttpGet(url), "stream segments list"));
    }

    public static List<HlsStreamSegment> parseFromLines(String[] lines) {
        List<HlsStreamSegment> segments = new ArrayList<>();
        ExtendedM3uParser.Line segmentInfo = null;

        for (String lineText : lines) {
            ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(lineText);

            if (line.isDirective()) {
                if ("EXTINF".equals(line.directiveName)) {
                    segmentInfo = line;
                } else if ("EXT-X-MAP".equals(line.directiveName)) {
                    segments.add(new HlsStreamSegment(line.directiveArguments.get("URI"), null, null));
                } else if ("EXT-X-KEY".equals(line.directiveName)) {
                    segments.add(new HlsDataSegment(line.directiveName, line.directiveArguments));
                }
            }

            if (line.isData()) {
                if (segmentInfo != null && !segmentInfo.extraData.contains(",")) {
                    String[] fields = segmentInfo.extraData.split(",", 2);
                    segments.add(new HlsStreamSegment(line.lineData, parseSecondDuration(fields[0]), fields[1]));
                } else {
                    segments.add(new HlsStreamSegment(line.lineData, null, null));
                }
            }
        }

        return segments;
    }

    private static Long parseSecondDuration(String value) {
        try {
            double asDouble = Double.parseDouble(value);
            return (long) (asDouble * 1000.0);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static byte[] fetchResponseByteArray(HttpInterface httpInterface, HttpUriRequest request, String name) throws IOException {
        try (CloseableHttpResponse response = httpInterface.execute(request)) {
            int statusCode = response.getStatusLine().getStatusCode();
            if (!isSuccessWithContent(statusCode)) {
                throw new IOException("Unexpected response code " + statusCode + " from " + name);
            }

            return EntityUtils.toByteArray(response.getEntity());
        }
    }
}
