package com.sedmelluq.discord.lavaplayer.source.youtube;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;

import java.io.IOException;

import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.BROWSE_URL;
import static com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeConstants.NEXT_URL;

public class YoutubeLyricsLoader {
    public String getLyricsForVideo(HttpInterface httpInterface, String videoId) {
        JsonBrowser watchPlaylist = getWatchPlaylist(httpInterface, videoId);
        JsonBrowser tabRenderer = watchPlaylist.get("contents")
            .get("singleColumnMusicWatchNextResultsRenderer")
            .get("tabbedRenderer")
            .get("watchNextTabbedResultsRenderer")
            .get("tabs")
            .index(1)
            .get("tabRenderer");

        if (!tabRenderer.get("unselectable").isNull()) {
            return null; // No browseId
        }

        String browseId = tabRenderer.get("endpoint").get("browseEndpoint").get("browseId").text();

        if (browseId == null) { // shouldn't happen, but better safe than sorry.
            throw new IllegalStateException("Missing browseId for lyrics watch playlist response");
        }

        return getLyricsFromBrowseId(httpInterface, browseId);
    }

    protected JsonBrowser getWatchPlaylist(HttpInterface httpInterface, String videoId) {
        HttpPost post = new HttpPost(NEXT_URL);

        String jsonString = YoutubeClientConfig.MUSIC.copy()
            .withRootField("videoId", videoId)
            .withRootField("playlistId", "RDAMVM" + videoId)
            .toJsonString();

        StringEntity payload = new StringEntity(jsonString, "UTF-8");
        post.setEntity(payload);

        try (CloseableHttpResponse response = httpInterface.execute(post)) {
            HttpClientTools.assertSuccessWithContent(response, "playlist response");
            HttpClientTools.assertJsonContentType(response);

            return JsonBrowser.parse(response.getEntity().getContent());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    protected String getLyricsFromBrowseId(HttpInterface httpInterface, String browseId) {
        HttpPost post = new HttpPost(BROWSE_URL);

        String jsonString = YoutubeClientConfig.MUSIC.copy()
            .withRootField("browseId", browseId)
            .toJsonString();

        StringEntity payload = new StringEntity(jsonString, "UTF-8");
        post.setEntity(payload);

        try (CloseableHttpResponse response = httpInterface.execute(post)) {
            HttpClientTools.assertSuccessWithContent(response, "browse response");
            HttpClientTools.assertJsonContentType(response);

            JsonBrowser json = JsonBrowser.parse(response.getEntity().getContent());
            String lyrics = json.get("contents")
                .get("sectionListRenderer")
                .get("contents")
                .index(0)
                .get("musicDescriptionShelfRenderer")
                .get("description")
                .get("runs")
                .index(0)
                .get("text")
                .text();

            if (lyrics == null) {
                throw new IllegalStateException("Missing lyrics from browse response");
            }

            return lyrics;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
