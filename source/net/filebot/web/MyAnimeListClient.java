package net.filebot.web;

import net.filebot.ResourceManager;

import javax.swing.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.*;

import static java.util.stream.Collectors.toList;
import static net.filebot.util.JsonUtilities.*;
import static net.filebot.web.WebRequest.encodeParameters;

public class MyAnimeListClient extends AbstractEpisodeListProvider {

    private final HttpClient client;

    public MyAnimeListClient() {
        this.client = HttpClient.newHttpClient();
    }

    @Override
    public String getIdentifier() {
        return "MyAnimeList";
    }

    @Override
    public Icon getIcon() {
        return ResourceManager.getIcon("search.myanimelist");
    }

    @Override
    public boolean hasSeasonSupport() {
        return false;
    }

    @Override
    protected SortOrder vetoRequestParameter(SortOrder order) {
        return SortOrder.Airdate;
    }

    @Override
    public URI getEpisodeListLink(SearchResult searchResult) {
        return URI.create("https://api.jikan.moe/v4/anime" + searchResult.getId() + "/episodes");
    }

    @Override
    protected List<SearchResult> fetchSearchResult(String query, Locale locale) throws Exception {
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("q", query);
        requestParams.put("order_by", "title");
        requestParams.put("sort", "asc");
        requestParams.put("limit", 25);
        Object response = readJson(getJikanResource("anime?" + encodeParameters(requestParams, true)));

        return streamJsonObjects(response, "data").map(anime -> {
            Integer id = getInteger(anime, "mal_id");
            String name = getString(anime, "title_english");
            if (name == null) {
                name = getString(anime, "title");
            }
            return new SearchResult(id, name);
        }).collect(toList());
    }

    @Override
    protected SeriesData fetchSeriesData(SearchResult series, SortOrder sortOrder, Locale locale) throws Exception {
        Object response = readJson(getJikanResource("anime/" + series.getId()));
        Object anime = getMap(response, "data");

        String tempName = getString(anime, "title_english");
        if (tempName == null) {
            tempName = getString(anime, "title");
        }

        final String name = tempName;
        String[] synonyms = Arrays.stream(getArray(anime, "title_synonyms")).map(Object::toString).toArray(String[]::new);
        String status = getString(anime, "status");

        SeriesInfo info = new SeriesInfo(this, sortOrder, locale, series.getId());
        info.setName(name);
        info.setAliasNames(synonyms);
        info.setStatus(status);
        info.setStartDate(getStringValue(anime, "start_date", SimpleDate::parse));

        info.setRating(getStringValue(anime, "score", Double::parseDouble));
        info.setRatingCount(getStringValue(anime, "scored_by", Integer::parseInt));
        info.setRuntime(0); // idc about this and data needs processing
        info.setGenres(streamJsonObjects(anime, "genres").map(it -> getString(it, "name")).collect(toList()));
        info.setNetwork(streamJsonObjects(anime, "studios").map(it -> getString(it, "name")).findFirst().orElse(null));

        List<Episode> episodes = new ArrayList<>();

        int page = 1;
        boolean hasNextPage = true;
        do {
            // System.out.println(series.getName() + " (" + page + ")");
            Object episodesResponse = readJson(getJikanResource("anime/" + series.getId() + "/episodes?page=" + page));

            streamJsonObjects(episodesResponse, "data").forEach(episode -> {
                Integer id = getInteger(episode, "mal_id");
                Integer episodeNumber = getInteger(episode, "mal_id");
                Integer seasonNumber = 1;
                String episodeTitle = getString(episode, "title");
                String aired = getString(episode, "aired");
                OffsetDateTime dt = OffsetDateTime.parse(aired);
                SimpleDate airdate = new SimpleDate(dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth());
                Integer absoluteNumber = getInteger(episode, "mal_id");
                episodes.add(new Episode(name, seasonNumber, episodeNumber, episodeTitle, absoluteNumber, null, airdate, id, info));
            });

            hasNextPage = Boolean.parseBoolean(getString(getMap(episodesResponse, "pagination"), "has_next_page"));
            page++;
            // System.out.println("hasNextPage: " + hasNextPage);
            Thread.sleep(350);
        } while (hasNextPage);
        return new SeriesData(info, episodes);
    }

    protected String getJikanResource(String resource) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.jikan.moe/v4/" + resource))
                .GET()
                .build();

        // System.out.println("Jikan Request Url: " + request.uri().toURL());
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP GET failed with code: " + response.statusCode() +
                    " and body: " + response.body());
        }

        return response.body();
    }

}