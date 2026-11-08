package me.wani4ka.concurrency.review.web;

import lombok.RequiredArgsConstructor;
import me.wani4ka.concurrency.review.ReviewService;
import me.wani4ka.concurrency.review.jobs.ReviewJobs;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
public class ReviewController {

    private static final Pattern PLUGIN_PATH =
            Pattern.compile("/plugin/([1-9][0-9]*)-[a-zA-Z0-9_-]+(?:/reviews)?/?");

    private final ReviewService service;
    private final ReviewJobs jobs;

    @PostMapping("/post")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CollectionResult collect(@RequestBody CollectionRequest request) {
        int pluginId = pluginIdFrom(request.url());
        if (!jobs.submit(pluginId)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Review collection queue is full");
        }
        return new CollectionResult(pluginId);
    }

    @GetMapping("/answer")
    public ReviewService.Answer answer(@RequestParam("pluginId") int pluginId) {
        if (pluginId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pluginId must be positive");
        }
        return service.answer(pluginId);
    }

    private static int pluginIdFrom(String value) {
        if (value == null) {
            throw unsupportedUrl();
        }
        try {
            URI url = URI.create(value);
            if ("https".equalsIgnoreCase(url.getScheme())
                    && "plugins.jetbrains.com".equalsIgnoreCase(url.getHost())
                    && url.getRawUserInfo() == null && (url.getPort() == -1 || url.getPort() == 443)) {
                var matcher = PLUGIN_PATH.matcher(url.getRawPath());
                if (matcher.matches()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        } catch (IllegalArgumentException error) {
            throw unsupportedUrl();
        }
        throw unsupportedUrl();
    }

    private static ResponseStatusException unsupportedUrl() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected a JetBrains Marketplace plugin URL");
    }

    public record CollectionRequest(String url) {
    }

    public record CollectionResult(int pluginId) {
    }
}
